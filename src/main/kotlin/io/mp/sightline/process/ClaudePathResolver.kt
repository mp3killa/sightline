package io.mp.sightline.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import java.io.File

/**
 * Resolves the `claude` executable. GUI-launched IDEs often don't inherit the shell PATH,
 * so "claude" alone frequently fails to launch. We check common install dirs and then fall
 * back to a login shell (which picks up version managers and custom PATH entries).
 */
object ClaudePathResolver {

    private val home: String = System.getProperty("user.home") ?: ""

    private val commonDirs = listOf(
        "$home/.local/bin",
        "$home/.claude/local",
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "$home/.bun/bin",
        "$home/.npm-global/bin",
        "$home/.volta/bin",
    )

    /** How long the login shell gets. A user's rc file can do arbitrary work; this call is on the send
     *  path, so it is bounded rather than trusted. */
    private const val SHELL_TIMEOUT_MS = 3_000

    @Volatile
    private var cached: String? = null

    /**
     * Set once a resolution attempt has run, whether or not it found anything. A failed probe costs a
     * full login shell, and without this the bare-`"claude"` fallback re-ran it on **every** send —
     * freezing the EDT repeatedly on exactly the machine where the binary can't be found.
     */
    @Volatile
    private var attempted = false

    fun resolve(configured: String): String {
        val c = configured.trim()
        // A non-default value (absolute path, or a command like "npx ...") is trusted as-is.
        if (c.isNotEmpty() && c != "claude") return c

        cached?.let { return it }
        if (attempted) return "claude"

        for (dir in commonDirs) {
            val f = File(dir, "claude")
            if (f.canExecute()) {
                cached = f.absolutePath
                attempted = true
                return f.absolutePath
            }
        }

        try {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val out = ExecUtil.execAndGetOutput(GeneralCommandLine(shell, "-lic", "command -v claude"), SHELL_TIMEOUT_MS)
            val path = out.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }
            if (!path.isNullOrBlank() && File(path).canExecute()) {
                cached = path
                attempted = true
                return path
            }
        } catch (_: Exception) {
            // fall through
        }

        attempted = true
        return "claude"
    }

    /**
     * Forgets the resolution, so the next [resolve] probes again. Called when the user changes the
     * command in settings or reruns the Health check — the two moments where they may have just
     * installed the CLI and would otherwise have to restart the IDE for it to be found.
     */
    fun invalidate() {
        cached = null
        attempted = false
    }
}
