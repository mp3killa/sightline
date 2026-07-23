package io.mp.sightline.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.mp.sightline.settings.ClaudeSettings
import java.nio.charset.StandardCharsets

/**
 * Owns one persistent `claude` CLI process running in bidirectional streaming-JSON mode.
 * The same process serves multiple turns: each user message is written as a JSON line to
 * stdin, and every stdout line (a stream-json event) is forwarded verbatim via [onLine].
 *
 * Lifecycle synthetic events are also sent through [onLine] as `{"type":"__panel", ...}`.
 */
class ClaudeSession(
    private val project: Project,
    private val onLine: (String) -> Unit,
) {
    private companion object {
        /** Enough stderr to explain an exit, capped so a chatty CLI can't grow the buffer unbounded. */
        const val MAX_STDERR_LINES = 20

        /** Stderr chunks written to idea.log per launch, before logging goes quiet. */
        const val MAX_STDERR_LOGGED = 50
    }

    @Volatile private var handler: OSProcessHandler? = null

    /**
     * Reassembly buffer for stdout. Written from the process reader thread ([ingestStdout]) and drained
     * on termination ([flushStdout]), which the platform may deliver on a different thread — so every
     * access holds [stdoutLock] rather than the instance lock, which is already held across process
     * start-up and would deadlock the reader.
     */
    private val stdoutLock = Any()
    private val stdoutBuf = StringBuilder()

    /** The last few stderr lines, kept so a non-zero exit can say *why* rather than just the code. */
    private val stderrLock = Any()
    private val recentStderr = ArrayDeque<String>()
    @Volatile private var stderrLogged = 0

    /** Owner-only file holding the `--mcp-config` payload for the running process; see [writeMcpConfigFile]. */
    @Volatile private var mcpConfigFile: java.io.File? = null

    @Volatile private var lastSessionId: String? = null
    @Volatile private var resumeNext = false

    /**
     * Cheap pre-filter for the session-id scrape. A match is only a *candidate*: [dispatchLine] confirms
     * it against the parsed top-level object, because this pattern matches anywhere in the line —
     * including inside a `tool_result` echoing a log or config that happens to contain a `session_id`
     * key, which would otherwise silently retarget a later `--resume` at someone else's conversation.
     */
    private val sessionIdRegex = Regex("\"session_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

    val isRunning: Boolean
        get() = handler?.let { !it.isProcessTerminated } ?: false

    /** True once the CLI has reported a `session_id` this launch — i.e. it authenticated and responded. */
    val sawSession: Boolean
        get() = lastSessionId != null

    @Synchronized
    fun sendUserMessage(text: String, images: List<UserMessageJson.ImageBlock> = emptyList()) {
        if (!isRunning) startProcess()
        writeLine(UserMessageJson.userLine(text, images))
    }

    @Synchronized
    private fun writeLine(json: String) {
        val h = handler ?: return
        try {
            val os = h.processInput ?: return
            os.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            onLine(panelJson("error", "Write failed: ${e.message}"))
        }
    }

    // ---- tool-permission control protocol (can_use_tool) ----

    fun respondAllow(requestId: String, updatedInputJson: String, updatedPermissionsJson: String?) {
        val perms = if (updatedPermissionsJson != null) ""","updatedPermissions":$updatedPermissionsJson""" else ""
        writeLine("""{"type":"control_response","response":{"subtype":"success","request_id":"${jsonEscape(requestId)}","response":{"behavior":"allow","updatedInput":$updatedInputJson$perms}}}""")
    }

    fun respondDeny(requestId: String, message: String) {
        writeLine("""{"type":"control_response","response":{"subtype":"success","request_id":"${jsonEscape(requestId)}","response":{"behavior":"deny","message":"${jsonEscape(message)}"}}}""")
    }

    fun respondControlError(requestId: String, error: String) {
        writeLine("""{"type":"control_response","response":{"subtype":"error","request_id":"${jsonEscape(requestId)}","error":"${jsonEscape(error)}"}}""")
    }

    @Synchronized
    private fun startProcess() {
        val s = ClaudeSettings.getInstance().state
        val exe = ClaudePathResolver.resolve(s.claudeCommand ?: "claude")

        // A relaunch that never saw processTerminated would otherwise strand the previous token file.
        deleteMcpConfigFile()

        val cmd = GeneralCommandLine(exe)
        cmd.addParameters("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")
        if (s.includePartialMessages) cmd.addParameter("--include-partial-messages")
        // Route tool-permission prompts to us over the control protocol (can_use_tool).
        if (s.interactiveApproval) cmd.addParameters("--permission-prompt-tool", "stdio")
        (s.permissionMode ?: "").takeIf { it.isNotBlank() }?.let { cmd.addParameters("--permission-mode", it) }
        (s.model ?: "").takeIf { it.isNotBlank() }?.let { cmd.addParameters("--model", it) }
        if (resumeNext && lastSessionId != null) {
            cmd.addParameters("--resume", lastSessionId!!)
            resumeNext = false
        }
        ArgTokenizer.tokenize(s.extraArgs ?: "").takeIf { it.isNotEmpty() }?.let { cmd.addParameters(it) }
        project.basePath?.let { cmd.setWorkDirectory(it) }
        cmd.charset = StandardCharsets.UTF_8
        cmd.withEnvironment("CLAUDE_CODE_ENTRYPOINT", "claude-code-panel")

        // Start the `ide` MCP server and register it with the CLI over a ws MCP config.
        // (In headless -p mode the CLI ignores the CLAUDE_CODE_SSE_PORT/lockfile discovery
        // used interactively, but it does connect to an explicit ws MCP server named "ide".)
        if (s.ideIntegration) {
            val ide = project.getService(io.mp.sightline.ide.IdeServer::class.java)
            if (ide != null && ide.ensureStarted() && ide.port > 0) {
                val cfg = """{"mcpServers":{"ide":{"type":"ws","url":"ws://127.0.0.1:${ide.port}","headers":{"x-claude-code-ide-authorization":"${ide.authToken}"}}}}"""
                // The config carries the bridge's auth token, so it goes in an owner-only file rather
                // than on the command line: process arguments are readable by other users on most
                // systems, which would let a different local user on a shared machine read the token
                // and reach the bridge — wider than the owner-only lock file the design relies on.
                // `--mcp-config` takes a path or a literal; the path form is verified against this CLI.
                val cfgFile = writeMcpConfigFile(cfg)
                if (cfgFile != null) {
                    mcpConfigFile = cfgFile
                    cmd.addParameters("--mcp-config", cfgFile.absolutePath)
                } else {
                    // Fail closed. Falling back to the inline form would put the token back on the
                    // command line at exactly the moment we could not secure it, and silently — the
                    // bridge is a capability, so losing it is the safer of the two failures.
                    thisLogger().warn("ide bridge disabled: could not write an owner-only MCP config file")
                }
            }
        }

        try {
            val h = OSProcessHandler(cmd)
            handler = h
            // Per-launch state. The buffer reset takes the same lock the reader thread uses: the
            // previous process's reader may still be draining as this one starts.
            synchronized(stdoutLock) { stdoutBuf.setLength(0) }
            synchronized(stderrLock) { recentStderr.clear() }
            stderrLogged = 0
            // ProcessListener directly, not the deprecated ProcessAdapter: the interface has had
            // default methods since 2023, so the adapter base class exists only for old Java callers
            // and the Plugin Verifier flags it.
            h.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> ingestStdout(event.text)
                        ProcessOutputTypes.STDERR -> ingestStderr(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    flushStdout()
                    // The CLI has read the config by now; the token in it should not outlive the
                    // process that needed it.
                    deleteMcpConfigFile()
                    onLine(panelJson("exited", code = event.exitCode, text = takeRecentStderr()))
                }
            })
            onLine(panelJson("started"))
            h.startNotify()
            // Control-protocol handshake so the CLI will route can_use_tool prompts to us.
            if (s.interactiveApproval) {
                writeLine("""{"type":"control_request","request_id":"init-${java.util.UUID.randomUUID()}","request":{"subtype":"initialize"}}""")
            }
        } catch (e: Exception) {
            onLine(panelJson("error", "Could not launch \"$exe\": ${e.message}"))
        }
    }

    /**
     * Writes the `--mcp-config` payload to a temp file only this user can read, returning null if that
     * cannot be guaranteed.
     *
     * Permissions are narrowed **before** the content is written, so the token never exists inside a
     * world-readable file even briefly. `setReadable` is used rather than POSIX file attributes because
     * it is also meaningful on Windows, and its return value is checked: on a filesystem that cannot
     * express owner-only permission (a FAT volume, some network mounts) the restriction silently does
     * nothing, and writing the token there anyway would be worse than the command line it replaced.
     */
    private fun writeMcpConfigFile(json: String): java.io.File? = try {
        val f = java.io.File.createTempFile("sightline-mcp", ".json")
        val restricted = f.setReadable(false, false) && f.setReadable(true, true) &&
            f.setWritable(false, false) && f.setWritable(true, true)
        if (!restricted) {
            f.delete()
            null
        } else {
            f.writeText(json)
            f.deleteOnExit() // backstop for a hard IDE kill, where processTerminated never runs
            f
        }
    } catch (e: Exception) {
        thisLogger().warn("could not write MCP config file", e)
        null
    }

    private fun deleteMcpConfigFile() {
        mcpConfigFile?.let { runCatching { it.delete() } }
        mcpConfigFile = null
    }

    private fun ingestStdout(text: String) {
        // Split under the lock, dispatch outside it: onLine hops to the EDT, and holding a lock across
        // that invites the reader thread and the UI to wait on each other.
        val lines = synchronized(stdoutLock) {
            stdoutBuf.append(text)
            val out = mutableListOf<String>()
            var nl = stdoutBuf.indexOf("\n")
            while (nl >= 0) {
                val line = stdoutBuf.substring(0, nl)
                stdoutBuf.delete(0, nl + 1)
                if (line.isNotBlank()) out.add(line)
                nl = stdoutBuf.indexOf("\n")
            }
            out
        }
        lines.forEach { dispatchLine(it) }
    }

    private fun flushStdout() {
        val rest = synchronized(stdoutLock) {
            val s = stdoutBuf.toString()
            stdoutBuf.setLength(0)
            s
        }
        if (rest.isNotBlank()) dispatchLine(rest)
    }

    private fun dispatchLine(line: String) {
        captureSessionId(line)
        onLine(line)
    }

    /**
     * The CLI's stderr carries the things a user most needs when nothing works — an authentication
     * failure, an unknown flag from [ClaudeSettings.extraArgs], a deprecation warning. It is not part of
     * the stream-json protocol, so it is logged and held rather than forwarded as a protocol line, and
     * the last few lines are attached to the exit event so a non-zero exit can say why.
     */
    private fun ingestStderr(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Logged, but not without limit: a CLI that chatters on stderr (a progress indicator, a runtime
        // deprecation notice) would otherwise fill idea.log with the same line. The buffer below still
        // captures the latest regardless, which is what the exit message actually needs.
        if (stderrLogged < MAX_STDERR_LOGGED) {
            stderrLogged++
            thisLogger().warn("claude stderr: $trimmed")
            if (stderrLogged == MAX_STDERR_LOGGED) thisLogger().warn("claude stderr: further lines suppressed this launch")
        }
        synchronized(stderrLock) {
            trimmed.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                recentStderr.addLast(line)
                while (recentStderr.size > MAX_STDERR_LINES) recentStderr.removeFirst()
            }
        }
    }

    private fun takeRecentStderr(): String = synchronized(stderrLock) {
        val s = recentStderr.joinToString("\n")
        recentStderr.clear()
        s
    }

    /**
     * Records the CLI's session id, but only from the **top level** of the event object. The regex is a
     * pre-filter to keep the common case cheap — most lines contain no `session_id` at all and never
     * reach the parser.
     */
    private fun captureSessionId(line: String) {
        if (sessionIdRegex.find(line) == null) return
        val id = try {
            com.google.gson.JsonParser.parseString(line)
                .takeIf { it.isJsonObject }?.asJsonObject
                ?.get("session_id")
                ?.takeIf { it.isJsonPrimitive }?.asString
        } catch (_: Exception) {
            null
        }
        if (id != null && id.isNotBlank()) lastSessionId = id
    }

    /** Stops the current turn/process. The next message resumes the same session. */
    @Synchronized
    fun stop() {
        val h = handler ?: return
        if (!h.isProcessTerminated) {
            resumeNext = true
            h.destroyProcess()
        }
    }

    /** Ends the conversation and forgets the session id, so the next message starts fresh. */
    @Synchronized
    fun newConversation() {
        handler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
        handler = null
        lastSessionId = null
        resumeNext = false
        onLine(panelJson("cleared"))
    }

    fun dispose() {
        handler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
        handler = null
        // Not left to processTerminated: closing one project of several is not JVM exit, so neither the
        // listener nor deleteOnExit is guaranteed to run before the IDE is closed.
        deleteMcpConfigFile()
    }

    private fun panelJson(subtype: String, text: String = "", code: Int? = null): String {
        val sb = StringBuilder("""{"type":"__panel","subtype":"$subtype"""")
        if (text.isNotEmpty()) sb.append(""","text":"${jsonEscape(text)}"""")
        if (code != null) sb.append(""","code":$code""")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEscape(s: String): String = UserMessageJson.escape(s)
}
