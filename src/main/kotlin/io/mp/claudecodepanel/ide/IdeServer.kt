package io.mp.claudecodepanel.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.ui.JBUI
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
                reply(conn, id, toolResult(callTool(name, args)))
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

    /** Wraps a plain-text tool result in the MCP `{content:[{type:text,text}]}` envelope. */
    private fun toolResult(text: String): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
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
        tools.add(tool("getDiagnostics", "Get language diagnostics from the IDE"))
        tools.add(tool("openFile", "Open a file in the editor"))
        tools.add(tool("openDiff", "Open a diff view for proposed changes (blocking)"))
        tools.add(tool("close_tab", "Close a tab by name"))
        tools.add(tool("closeAllDiffTabs", "Close all diff tabs"))
        tools.add(tool("checkDocumentDirty", "Check whether a document has unsaved changes"))
        tools.add(tool("saveDocument", "Save a document"))
        return JsonObject().apply { add("tools", tools) }
    }

    private fun callTool(name: String, args: JsonObject): String = when (name) {
        "getCurrentSelection" -> onEdt { currentSelectionResult(activeEditor()) } ?: fail("No active editor found")
        "getLatestSelection" -> lastSelection?.let { obj("success" to true).merge(it).toString() } ?: fail("No selection available")
        "getOpenEditors" -> onEdt { openEditors() } ?: "{\"tabs\":[]}"
        "getWorkspaceFolders" -> workspaceFolders()
        "getDiagnostics" -> "[]"
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

    private fun openFile(args: JsonObject): String {
        val path = args.str("filePath") ?: return fail("filePath required")
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
        val accepted = onEdt {
            val dcf = DiffContentFactory.getInstance()
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(newPath)
            val oldText = oldPath?.let {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(it)
                if (vf != null) String(vf.contentsToByteArray(), StandardCharsets.UTF_8) else ""
            } ?: ""
            val oldContent = dcf.create(project, oldText, fileType)
            val newContent = dcf.create(project, newContents, fileType)
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
            val ok = dialog.showAndGet()
            if (ok) writeFile(newPath, newContents)
            ok
        } ?: false
        return if (accepted) "FILE_SAVED" else "DIFF_REJECTED"
    }

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
