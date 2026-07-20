package io.mp.claudecodepanel.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import io.mp.claudecodepanel.ide.android.AndroidMcpTools
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.awt.Dimension
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.swing.JComponent

/**
 * Runs the `ide` MCP server that the Claude CLI connects to (the same mechanism the official
 * plugin uses): a loopback WebSocket server speaking MCP-over-WebSocket. It writes a discovery
 * lock file to `~/.claude/ide/<port>.lock` and, once the CLI connects (authenticated with the
 * token), answers tool calls (selection, open editors, workspace, diff, diagnostics) and pushes
 * `selection_changed` notifications.
 *
 * Path-taking tools (`openFile`, `openDiff`, `saveDocument`) are gated by [PathAccessPolicy]:
 * sensitive locations are refused outright, and writes outside the project require an extra explicit
 * confirmation showing the full external path.
 */
@Service(Service.Level.PROJECT)
class IdeServer(private val project: Project) : Disposable {

    @Volatile var port: Int = -1
        private set
    @Volatile var authToken: String = ""
        private set

    private var server: Srv? = null
    private var lockFile: File? = null
    @Volatile private var lastSelection: JsonObject? = null

    /** path -> (document modification stamp, collected problems) so unchanged files aren't re-scanned. */
    private val diagnosticsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonArray>>()

    /** Guards which paths the bridge may open/diff/write. Built once from the project's content roots. */
    private val pathPolicy: PathAccessPolicy by lazy { PathAccessPolicy(projectRoots()) }

    /** Shared so the human diff dialog and the sandbox test bridge resolve the same review. */
    private val diffCoordinator: DiffReviewCoordinator by lazy { project.getService(DiffReviewCoordinator::class.java) }

    /** Sandbox-only automation tools; contributes nothing unless -Dsightline.testBridge=true. */
    private val testBridge: SightlineTestBridge by lazy { SightlineTestBridge(project) }

    /** `android.*` tools; contributes nothing outside an Android project or with the feature off. */
    private val androidTools: AndroidMcpTools by lazy { AndroidMcpTools(project) }

    private companion object {
        const val DIFF_TIMEOUT_MINUTES = 10L
    }

    private fun projectRoots(): List<String> {
        val roots = mutableListOf<String>()
        project.basePath?.let { roots.add(it) }
        try {
            ApplicationManager.getApplication().runReadAction {
                ProjectRootManager.getInstance(project).contentRoots.forEach { roots.add(it.path) }
            }
        } catch (_: Exception) {}
        return roots.distinct()
    }

    @Synchronized
    fun ensureStarted(): Boolean {
        if (server != null) return true
        return try {
            start(); true
        } catch (e: Exception) {
            thisLogger().warn("Claude ide server failed to start", e)
            false
        }
    }

    private fun start() {
        authToken = randomHex(16)
        val freePort = ServerSocket(0).use { it.localPort }
        val s = Srv(InetSocketAddress("127.0.0.1", freePort))
        s.isReuseAddr = true
        s.start()
        server = s
        port = freePort
        writeLockFile()
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                val json = try { selectionParams(e.editor) } catch (ex: Exception) { null } ?: return
                lastSelection = json
                broadcast(notification("selection_changed", json))
            }
        }, this)
        thisLogger().info("Claude ide server listening on 127.0.0.1:$port")
    }

    private fun writeLockFile() {
        val dir = File(configDir(), "ide")
        dir.mkdirs()
        val lock = JsonObject().apply {
            addProperty("pid", ProcessHandle.current().pid())
            add("workspaceFolders", JsonArray().apply { project.basePath?.let { add(it) } })
            addProperty("ideName", "Android Studio")
            addProperty("transport", "ws")
            addProperty("authToken", authToken)
        }
        val f = File(dir, "$port.lock")
        f.writeText(lock.toString())
        try { f.setReadable(false, false); f.setReadable(true, true) } catch (_: Exception) {}
        lockFile = f
    }

    private fun configDir(): File {
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(System.getProperty("user.home"), ".claude")
    }

    // ---------- websocket server ----------

    private inner class Srv(addr: InetSocketAddress) : WebSocketServer(addr) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val token = handshake.getFieldValue("x-claude-code-ide-authorization")
            if (token != authToken) {
                conn.close(1008, "unauthorized")
            }
        }
        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {
            try { handle(conn, message) } catch (e: Exception) { thisLogger().warn("ide message error", e) }
        }
        override fun onError(conn: WebSocket?, ex: Exception) { thisLogger().warn("ide ws error", ex) }
        override fun onStart() {}
    }

    private fun broadcast(text: String) {
        server?.connections?.forEach { c -> try { if (c.isOpen) c.send(text) } catch (_: Exception) {} }
    }

    // ---------- MCP dispatch ----------

    private fun handle(conn: WebSocket, message: String) {
        val root = JsonParser.parseString(message).asJsonObject
        val method = root.str("method") ?: return
        val id = root.get("id")
        when (method) {
            "initialize" -> reply(conn, id, initializeResult())
            "notifications/initialized" -> { /* no response */ }
            "tools/list" -> reply(conn, id, toolsList())
            "tools/call" -> {
                val params = root.getObj("params") ?: JsonObject()
                val name = params.str("name") ?: ""
                val args = params.getObj("arguments") ?: JsonObject()
                if (testBridge.handles(name)) {
                    val r = testBridge.call(name, args)
                    reply(conn, id, toolResult(r.text, r.imagePng))
                } else if (androidTools.handles(name)) {
                    // Already off the EDT (this is the WebSocket thread), which is what the resolver's
                    // file walks and adb round-trips require.
                    reply(conn, id, toolResult(androidTools.call(name, args)))
                } else {
                    reply(conn, id, toolResult(callTool(name, args)))
                }
            }
            else -> if (id != null && !id.isJsonNull) replyError(conn, id, -32601, "Method not found: $method")
        }
    }

    private fun reply(conn: WebSocket, id: com.google.gson.JsonElement?, result: JsonObject) {
        if (id == null || id.isJsonNull) return
        val msg = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id); add("result", result) }
        try { conn.send(msg.toString()) } catch (_: Exception) {}
    }
    private fun replyError(conn: WebSocket, id: com.google.gson.JsonElement, code: Int, message: String) {
        val err = JsonObject().apply { addProperty("code", code); addProperty("message", message) }
        val msg = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id); add("error", err) }
        try { conn.send(msg.toString()) } catch (_: Exception) {}
    }
    private fun notification(method: String, params: JsonObject): String =
        JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", method); add("params", params) }.toString()

    private fun initializeResult(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", "2025-03-26")
        add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
        add("serverInfo", JsonObject().apply { addProperty("name", "claude-code-panel-ide"); addProperty("version", "0.4.0") })
    }

    /** Wraps a tool result in the MCP `{content:[…]}` envelope; adds a base64 PNG image block if given. */
    private fun toolResult(text: String, imagePng: ByteArray? = null): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
            imagePng?.let { bytes ->
                add(JsonObject().apply {
                    addProperty("type", "image")
                    addProperty("data", java.util.Base64.getEncoder().encodeToString(bytes))
                    addProperty("mimeType", "image/png")
                })
            }
        })
    }

    private fun tool(name: String, desc: String, props: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", desc)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", props ?: JsonObject())
        })
    }

    private fun toolsList(): JsonObject {
        val tools = JsonArray()
        tools.add(tool("getCurrentSelection", "Get the current text selection in the active editor"))
        tools.add(tool("getLatestSelection", "Get the most recent text selection"))
        tools.add(tool("getOpenEditors", "Get information about currently open editors"))
        tools.add(tool("getWorkspaceFolders", "Get all workspace folders open in the IDE"))
        tools.add(tool("getDiagnostics", "Get IDE language diagnostics (errors/warnings) for a file. " +
            "Pass `uri` (or `filePath`) to scope to one file; omit to report the current + open editors. " +
            "Returns {available, files:[{path, problems:[{severity,message,line,column,source}]}]}. " +
            "`available:false` means the data could not be collected (e.g. indexing) — not that the code is clean.",
            JsonObject().apply { add("uri", JsonObject().apply { addProperty("type", "string") }) }))
        tools.add(tool("openFile", "Open a file in the editor"))
        tools.add(tool("openDiff", "Open a diff view for proposed changes (blocking)"))
        tools.add(tool("close_tab", "Close a tab by name"))
        tools.add(tool("closeAllDiffTabs", "Close all diff tabs"))
        tools.add(tool("checkDocumentDirty", "Check whether a document has unsaved changes"))
        tools.add(tool("saveDocument", "Save a document"))
        androidTools.addToolDefs(tools) // no-op unless the Android features are on
        testBridge.addToolDefs(tools) // no-op unless the sandbox test bridge is enabled
        return JsonObject().apply { add("tools", tools) }
    }

    private fun callTool(name: String, args: JsonObject): String = when (name) {
        "getCurrentSelection" -> onEdt { currentSelectionResult(activeEditor()) } ?: fail("No active editor found")
        "getLatestSelection" -> lastSelection?.let { obj("success" to true).merge(it).toString() } ?: fail("No selection available")
        "getOpenEditors" -> onEdt { openEditors() } ?: "{\"tabs\":[]}"
        "getWorkspaceFolders" -> workspaceFolders()
        "getDiagnostics" -> getDiagnostics(args)
        "openFile" -> openFile(args)
        "openDiff" -> openDiff(args)
        "close_tab" -> "TAB_CLOSED"
        "closeAllDiffTabs" -> "CLOSED_0_DIFF_TABS"
        "checkDocumentDirty" -> checkDirty(args)
        "saveDocument" -> saveDoc(args)
        else -> fail("Unknown tool: $name")
    }

    // ---------- tool implementations ----------

    private fun activeEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor

    private fun currentSelectionResult(editor: Editor?): String? {
        if (editor == null) return null
        val sel = editor.selectionModel
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val start = editor.offsetToLogicalPosition(sel.selectionStart)
        val end = editor.offsetToLogicalPosition(sel.selectionEnd)
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("text", sel.selectedText ?: "")
            addProperty("filePath", file?.path ?: "")
            add("selection", JsonObject().apply {
                add("start", pos(start.line, start.column))
                add("end", pos(end.line, end.column))
            })
        }.toString()
    }

    private fun selectionParams(editor: Editor): JsonObject {
        val sel = editor.selectionModel
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val start = editor.offsetToLogicalPosition(sel.selectionStart)
        val end = editor.offsetToLogicalPosition(sel.selectionEnd)
        val text = sel.selectedText ?: ""
        return JsonObject().apply {
            addProperty("text", text)
            addProperty("filePath", file?.path ?: "")
            addProperty("fileUrl", file?.url ?: "")
            add("selection", JsonObject().apply {
                add("start", pos(start.line, start.column))
                add("end", pos(end.line, end.column))
                addProperty("isEmpty", text.isEmpty())
            })
        }
    }

    private fun openEditors(): String {
        val fem = FileEditorManager.getInstance(project)
        val activeFile = fem.selectedTextEditor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val tabs = JsonArray()
        for (vf in fem.openFiles) {
            tabs.add(JsonObject().apply {
                addProperty("uri", vf.url)
                addProperty("isActive", vf == activeFile)
                addProperty("label", vf.name)
                addProperty("languageId", vf.fileType.name.lowercase())
                addProperty("isDirty", FileDocumentManager.getInstance().isFileModified(vf))
            })
        }
        return JsonObject().apply { add("tabs", tabs) }.toString()
    }

    private fun workspaceFolders(): String {
        val base = project.basePath
        val folders = JsonArray()
        if (base != null) {
            folders.add(JsonObject().apply {
                addProperty("name", project.name)
                addProperty("uri", "file://$base")
                addProperty("path", base)
            })
        }
        return JsonObject().apply {
            addProperty("success", true)
            add("folders", folders)
            addProperty("rootPath", base ?: "")
        }.toString()
    }

    /**
     * Honest, scoped diagnostics. Never returns an empty array to mean "clean": if the data can't be
     * collected (indexing, no target) it returns `available:false` with a reason. Scope is a single
     * requested file, else the current + open editors — never a project-wide sweep.
     */
    private fun getDiagnostics(args: JsonObject): String {
        if (DumbService.getInstance(project).isDumb) {
            return JsonObject().apply {
                addProperty("available", false); addProperty("reason", "IDE indexing is in progress")
            }.toString()
        }
        val requested = (args.str("uri") ?: args.str("filePath"))?.let { uriToPath(it) }
        val filesJson = onEdt {
            val fdm = FileDocumentManager.getInstance()
            val targets: List<VirtualFile> = if (requested != null) {
                listOfNotNull(LocalFileSystem.getInstance().findFileByPath(requested))
            } else {
                val fem = FileEditorManager.getInstance(project)
                val active = fem.selectedTextEditor?.let { fdm.getFile(it.document) }
                (listOfNotNull(active) + fem.openFiles.toList()).distinct()
            }
            JsonArray().apply {
                for (vf in targets) {
                    val doc = fdm.getDocument(vf) ?: continue
                    add(JsonObject().apply {
                        addProperty("path", vf.path)
                        add("problems", problemsFor(vf, doc))
                    })
                }
            }
        }
        if (requested != null && filesJson != null && filesJson.isEmpty) {
            return JsonObject().apply {
                addProperty("available", false)
                addProperty("reason", "File is not open in the IDE: $requested")
            }.toString()
        }
        return JsonObject().apply {
            addProperty("available", true)
            add("files", filesJson ?: JsonArray())
        }.toString()
    }

    /** Reads daemon highlights (>= weak-warning) from a document's markup model; cached by mod stamp. */
    private fun problemsFor(vf: VirtualFile, doc: Document): JsonArray {
        val stamp = doc.modificationStamp
        diagnosticsCache[vf.path]?.let { (cachedStamp, cached) -> if (cachedStamp == stamp) return cached }
        val out = JsonArray()
        val markup = DocumentMarkupModel.forDocument(doc, project, false)
        val source = vf.fileType.name.replaceFirstChar { it.uppercase() }
        for (h in markup.allHighlighters) {
            val info = h.errorStripeTooltip as? HighlightInfo ?: continue
            if (info.severity < HighlightSeverity.WEAK_WARNING) continue
            val offset = info.startOffset
            if (offset < 0 || offset > doc.textLength) continue
            val line = doc.getLineNumber(offset)
            out.add(JsonObject().apply {
                addProperty("severity", severityLabel(info.severity))
                addProperty("message", info.description ?: "")
                addProperty("line", line + 1)
                addProperty("column", offset - doc.getLineStartOffset(line) + 1)
                addProperty("source", source)
            })
            if (out.size() >= 200) break // cap per file — don't flood the model on a huge file
        }
        diagnosticsCache[vf.path] = stamp to out
        return out
    }

    private fun severityLabel(sev: HighlightSeverity): String = when {
        sev >= HighlightSeverity.ERROR -> "ERROR"
        sev >= HighlightSeverity.WARNING -> "WARNING"
        sev >= HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
        else -> "INFO"
    }

    private fun uriToPath(uriOrPath: String): String =
        if (uriOrPath.startsWith("file://")) File(java.net.URI(uriOrPath)).path else uriOrPath

    private fun openFile(args: JsonObject): String {
        val path = args.str("filePath") ?: return fail("filePath required")
        if (pathPolicy.classify(path) == PathAccess.SENSITIVE) return refused(path)
        val ok = onEdt {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@onEdt false
            FileEditorManager.getInstance(project).openFile(vf, true)
            true
        } ?: false
        return if (ok) "Opened file: $path" else fail("Could not open: $path")
    }

    private fun openDiff(args: JsonObject): String {
        val oldPath = args.str("old_file_path")
        val newPath = args.str("new_file_path") ?: oldPath ?: return fail("no path")
        val newContents = args.str("new_file_contents") ?: ""
        val tabName = args.str("tab_name") ?: "Proposed changes"
        val access = pathPolicy.classify(newPath)
        // A protected location is never written, regardless of permission mode or diff acceptance.
        if (access == PathAccess.SENSITIVE) return refused(newPath)

        val oldText = onEdt {
            oldPath?.let {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(it)
                if (vf != null) String(vf.contentsToByteArray(), StandardCharsets.UTF_8) else ""
            } ?: ""
        } ?: ""

        // Register the review so either the human's dialog or the sandbox test bridge can resolve it;
        // the handler thread waits on the future. Same external contract as before (blocking + write).
        val id = "diff_" + randomHex(6)
        val review = diffCoordinator.create(id, newPath, oldText, newContents)
        ApplicationManager.getApplication().invokeLater({ showDiffDialog(review, tabName, access) })

        val decision = try {
            review.future.get(DIFF_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            diffCoordinator.remove(id)
            return "DIFF_REJECTED"
        }
        return if (decision == DiffDecision.ACCEPT) { writeFile(newPath, newContents); "FILE_SAVED" } else "DIFF_REJECTED"
    }

    /** Shows the Accept/Reject diff dialog on the EDT; its result resolves the pending review. Skips if
     * the review was already resolved externally (test bridge), and auto-closes if resolved while open. */
    private fun showDiffDialog(review: PendingDiffReview, tabName: String, access: PathAccess) {
        if (review.future.isDone) return
        val dcf = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(review.path)
        val oldContent = dcf.create(project, review.currentText, fileType)
        val newContent = dcf.create(project, review.proposedText, fileType)
        val request = SimpleDiffRequest(tabName, oldContent, newContent, "Current", "Proposed by Claude")
        val dialog = object : DialogWrapper(project, true) {
            init { title = tabName; init(); setOKButtonText("Accept"); setCancelButtonText("Reject") }
            override fun createCenterPanel(): JComponent {
                val p = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                p.setRequest(request)
                val c = p.component
                c.preferredSize = Dimension(JBUI.scale(920), JBUI.scale(560))
                return c
            }
        }
        // If the review is resolved externally (test bridge) while the dialog is open, close it.
        review.future.whenComplete { _, _ -> ApplicationManager.getApplication().invokeLater { if (dialog.isShowing) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) } }
        val ok = dialog.showAndGet()
        // Writing outside the project needs a second, explicit confirmation of the full path.
        val decision = if (ok && (access != PathAccess.OUTSIDE_PROJECT || confirmExternalWrite(review.path))) {
            DiffDecision.ACCEPT
        } else {
            DiffDecision.REJECT
        }
        diffCoordinator.respond(review.id, decision)
    }

    /** Blocking yes/no shown on the EDT before writing outside the project; surfaces the full path. */
    private fun confirmExternalWrite(path: String): Boolean =
        Messages.showYesNoDialog(
            project,
            "Claude is asking to write to a file OUTSIDE this project:\n\n$path\n\nAllow this write?",
            "Write outside project?",
            "Write file", "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES

    private fun refused(path: String): String = JsonObject().apply {
        addProperty("success", false)
        addProperty("refused", true)
        addProperty("message", "Refused: \"$path\" is a protected location outside the workspace and cannot be accessed.")
    }.toString()

    private fun writeFile(path: String, contents: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            if (vf == null) {
                val parentPath = path.substringBeforeLast('/', "")
                val name = path.substringAfterLast('/')
                val parent = if (parentPath.isNotEmpty()) VfsUtil.createDirectoryIfMissing(parentPath) else null
                vf = parent?.let { it.findChild(name) ?: it.createChildData(this, name) }
            }
            val target = vf ?: return@runWriteCommandAction
            val doc = FileDocumentManager.getInstance().getDocument(target)
            if (doc != null) {
                doc.setText(StringUtil.convertLineSeparators(contents))
                FileDocumentManager.getInstance().saveDocument(doc)
            } else {
                target.setBinaryContent(contents.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    private fun checkDirty(args: JsonObject): String {
        val path = args.str("filePath") ?: return fail("filePath required")
        return onEdt {
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return@onEdt fail("Document not open: $path")
            JsonObject().apply {
                addProperty("success", true)
                addProperty("filePath", path)
                addProperty("isDirty", FileDocumentManager.getInstance().isFileModified(vf))
                addProperty("isUntitled", false)
            }.toString()
        } ?: fail("Document not open: $path")
    }

    private fun saveDoc(args: JsonObject): String {
        val path = args.str("filePath") ?: return fail("filePath required")
        if (pathPolicy.classify(path) == PathAccess.SENSITIVE) return refused(path)
        return onEdt {
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return@onEdt fail("Document not open: $path")
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@onEdt fail("Document not open: $path")
            FileDocumentManager.getInstance().saveDocument(doc)
            JsonObject().apply {
                addProperty("success", true); addProperty("filePath", path); addProperty("saved", true)
                addProperty("message", "Document saved successfully")
            }.toString()
        } ?: fail("Document not open: $path")
    }

    // ---------- helpers ----------

    private fun <T> onEdt(compute: () -> T): T? {
        var result: T? = null
        try {
            ApplicationManager.getApplication().invokeAndWait { result = compute() }
        } catch (e: Exception) {
            thisLogger().warn("ide onEdt failed", e)
        }
        return result
    }

    private fun pos(line: Int, character: Int): JsonObject =
        JsonObject().apply { addProperty("line", line); addProperty("character", character) }
    private fun fail(message: String): String =
        JsonObject().apply { addProperty("success", false); addProperty("message", message) }.toString()
    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
        pairs.forEach { (k, v) -> when (v) { is Boolean -> addProperty(k, v); is Number -> addProperty(k, v); else -> addProperty(k, v.toString()) } }
    }
    private fun JsonObject.merge(other: JsonObject): JsonObject { other.entrySet().forEach { add(it.key, it.value) }; return this }
    private fun JsonObject.str(k: String): String? = if (has(k) && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.getObj(k: String): JsonObject? = if (has(k) && get(k).isJsonObject) getAsJsonObject(k) else null

    private fun randomHex(nBytes: Int): String {
        val b = ByteArray(nBytes); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {
        try { server?.stop(500) } catch (_: Exception) {}
        server = null
        try { lockFile?.delete() } catch (_: Exception) {}
    }
}
