package io.mp.sightline.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import io.mp.sightline.process.ClaudePathResolver
import io.mp.sightline.settings.ClaudeSettings
import java.nio.charset.StandardCharsets

/**
 * Runs a **one-shot** `claude -p` to draft a commit message from a unified diff — deliberately separate
 * from the interactive [io.mp.sightline.process.ClaudeSession] (no MCP, no tools, no control protocol):
 * a commit message is a self-contained text task, and a fast model on a bare invocation keeps it snappy.
 *
 * The model, and any style guidance, come from [ClaudeSettings]; the default is a fast, low-effort model.
 * Blocking with a timeout — call it off the EDT.
 */
object CommitMessageGenerator {

    private const val TIMEOUT_MS = 60_000

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Err(val reason: String) : Result
    }

    /**
     * @param projectStyle commit-message guidance found in the project's own docs ([CommitStyleScanner]),
     *   or "" — combined with the user's settings guidance so a stated house style is honoured.
     */
    fun generate(basePath: String?, diff: String, projectStyle: String = ""): Result {
        if (diff.isBlank()) return Result.Err("No changes to describe.")
        val s = ClaudeSettings.getInstance().state
        val exe = try {
            ClaudePathResolver.resolve(s.claudeCommand ?: "claude")
        } catch (e: Exception) {
            return Result.Err("Couldn't find the claude CLI — set its path in Settings → Tools → Sightline.")
        }
        val model = (s.commitMessageModel ?: "").trim().ifEmpty { "haiku" }
        val guidance = buildList {
            (s.commitMessageInstructions ?: "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            projectStyle.trim().takeIf { it.isNotEmpty() }
                ?.let { add("This project states its own commit-message style below — follow it:\n$it") }
        }.joinToString("\n\n")
        val prompt = CommitMessagePrompt.build(diff, guidance)

        val cmd = GeneralCommandLine(exe, "-p", prompt, "--model", model, "--output-format", "text")
        basePath?.let { cmd.setWorkDirectory(it) }
        cmd.charset = StandardCharsets.UTF_8
        cmd.withEnvironment("CLAUDE_CODE_ENTRYPOINT", "claude-code-panel")

        return try {
            val output = CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS, true)
            when {
                output.isTimeout -> Result.Err("Timed out generating the commit message.")
                output.exitCode != 0 -> {
                    val why = output.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() }
                    Result.Err(why?.let { "claude exited ${output.exitCode}: $it" } ?: "claude exited ${output.exitCode}.")
                }
                else -> {
                    val msg = CommitMessagePrompt.clean(output.stdout)
                    if (msg.isBlank()) Result.Err("The model returned an empty message.") else Result.Ok(msg)
                }
            }
        } catch (e: Exception) {
            Result.Err(e.message ?: "Failed to run the claude CLI.")
        }
    }
}
