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

    @Volatile
    private var cached: String? = null

    fun resolve(configured: String): String {
        val c = configured.trim()
        // A non-default value (absolute path, or a command like "npx ...") is trusted as-is.
        if (c.isNotEmpty() && c != "claude") return c

        cached?.let { return it }

        for (dir in commonDirs) {
            val f = File(dir, "claude")
            if (f.canExecute()) {
                cached = f.absolutePath
                return f.absolutePath
            }
        }

        try {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val out = ExecUtil.execAndGetOutput(GeneralCommandLine(shell, "-lic", "command -v claude"))
            val path = out.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }
            if (!path.isNullOrBlank() && File(path).canExecute()) {
                cached = path
                return path
            }
        } catch (_: Exception) {
            // fall through
        }

        return "claude"
    }
}
