package io.mp.sightline.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
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
    private var handler: OSProcessHandler? = null
    private val stdoutBuf = StringBuilder()

    @Volatile private var lastSessionId: String? = null
    @Volatile private var resumeNext = false

    private val sessionIdRegex = Regex("\"session_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

    val isRunning: Boolean
        get() = handler?.let { !it.isProcessTerminated } ?: false

    /** True once the CLI has reported a `session_id` this launch — i.e. it authenticated and responded. */
    val sawSession: Boolean
        get() = lastSessionId != null

    @Synchronized
    fun sendUserMessage(text: String) {
        if (!isRunning) startProcess()
        writeLine("""{"type":"user","message":{"role":"user","content":"${jsonEscape(text)}"}}""")
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
        (s.extraArgs ?: "").trim().takeIf { it.isNotEmpty() }?.let { extra ->
            cmd.addParameters(*extra.split(Regex("\\s+")).toTypedArray())
        }
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
                cmd.addParameters("--mcp-config", cfg)
            }
        }

        try {
            val h = OSProcessHandler(cmd)
            handler = h
            stdoutBuf.setLength(0)
            // ProcessListener directly, not the deprecated ProcessAdapter: the interface has had
            // default methods since 2023, so the adapter base class exists only for old Java callers
            // and the Plugin Verifier flags it.
            h.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> ingestStdout(event.text)
                        ProcessOutputTypes.STDERR -> onLine(panelJson("stderr", event.text))
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    flushStdout()
                    onLine(panelJson("exited", code = event.exitCode))
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

    private fun ingestStdout(text: String) {
        stdoutBuf.append(text)
        var nl = stdoutBuf.indexOf("\n")
        while (nl >= 0) {
            val line = stdoutBuf.substring(0, nl)
            stdoutBuf.delete(0, nl + 1)
            if (line.isNotBlank()) dispatchLine(line)
            nl = stdoutBuf.indexOf("\n")
        }
    }

    private fun flushStdout() {
        if (stdoutBuf.isNotBlank()) {
            dispatchLine(stdoutBuf.toString())
            stdoutBuf.setLength(0)
        }
    }

    private fun dispatchLine(line: String) {
        sessionIdRegex.find(line)?.let { lastSessionId = it.groupValues[1] }
        onLine(line)
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
    }

    private fun panelJson(subtype: String, text: String = "", code: Int? = null): String {
        val sb = StringBuilder("""{"type":"__panel","subtype":"$subtype"""")
        if (text.isNotEmpty()) sb.append(""","text":"${jsonEscape(text)}"""")
        if (code != null) sb.append(""","code":$code""")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // Control chars (backspace, form-feed, etc.) become valid \uXXXX escapes below.
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
