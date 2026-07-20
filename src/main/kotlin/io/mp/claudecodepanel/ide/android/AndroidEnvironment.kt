package io.mp.claudecodepanel.ide.android

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.AndroidSdk
import io.mp.claudecodepanel.android.AndroidSdkLocator
import io.mp.claudecodepanel.settings.ClaudeSettings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The result of running an SDK tool. [timedOut] is distinct from a non-zero exit: one is our fault. */
data class ToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** stdout when it worked, stderr when it didn't — what a caller almost always wants to show. */
    val output: String get() = if (ok) stdout else listOf(stderr, stdout).firstOrNull { it.isNotBlank() }.orEmpty()
}

/**
 * The one place that knows where the Android SDK is and how to run its tools — the thin platform half of
 * the CLI-first strategy (docs/ANDROID.md §1.1). Everything above it works in typed values; nothing above
 * it builds a command line by hand.
 *
 * Deliberately has **no Android Studio dependency**: `adb` and `emulator` are stable, documented
 * executables, so device work keeps functioning in a plain IntelliJ IDEA where `org.jetbrains.android`
 * isn't loaded. Android Studio's own model is an optional *upgrade* to these facts, never a prerequisite
 * ([StudioFactProvider]).
 *
 * Every call shells out, so **every method here must be called off the EDT.** Timeouts are mandatory
 * rather than optional: a device in a bad state makes adb hang indefinitely, and a hung adb on the EDT
 * freezes the whole IDE.
 */
@Service(Service.Level.PROJECT)
class AndroidEnvironment(private val project: Project) {

    /** Cached because locating the SDK touches the filesystem and the answer rarely changes. */
    private val cached = AtomicReference<AndroidSdk?>()

    /** Resolve the SDK, using the cached answer unless [refresh]. Null means no SDK was found at all. */
    fun sdk(refresh: Boolean = false): AndroidSdk? {
        if (!refresh) cached.get()?.let { return it }
        val found = locate()
        cached.set(found)
        return found
    }

    private fun locate(): AndroidSdk? {
        val configured = ClaudeSettings.getInstance().state.androidSdkPath?.takeIf { it.isNotBlank() }
        val candidates = AndroidSdkLocator.candidates(
            configured = configured,
            env = System.getenv(),
            localProperties = readLocalProperties(),
            userHome = System.getProperty("user.home"),
            osName = System.getProperty("os.name").orEmpty(),
        )
        return AndroidSdkLocator.resolve(candidates) { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
    }

    /**
     * `local.properties`' `sdk.dir`. Read straight off disk rather than through the VFS: this runs during
     * the health check and at session start, both of which can precede indexing, and a null here is a
     * benign fallthrough to the next ladder rung.
     */
    private fun readLocalProperties(): String? {
        val base = project.basePath ?: return null
        return runCatching { File(base, "local.properties").takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /** True when this project has anything Android about it — gates every Android affordance in the UI. */
    fun looksLikeAndroidProject(): Boolean {
        val base = project.basePath ?: return false
        return runCatching {
            val root = File(base)
            if (File(root, "local.properties").isFile && sdk() != null) return@runCatching true
            // A settings file plus any module declaring the Android plugin. Bounded to top-level dirs so
            // this stays cheap enough to call from the UI.
            val gradleFiles = (root.listFiles()?.filter { it.isDirectory }.orEmpty() + root)
                .flatMap { dir -> listOf("build.gradle.kts", "build.gradle").map { File(dir, it) } }
                .filter { it.isFile }
                .take(24)
            gradleFiles.any { f ->
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                text.contains("com.android.application") || text.contains("com.android.library") ||
                    text.contains("android {")
            }
        }.getOrDefault(false)
    }

    // ---------- running tools ----------

    /**
     * Run `adb` with [args]. Returns null only when no adb could be found, which callers must report as
     * "no SDK" rather than as an empty result — an empty device list and an unrunnable adb look identical
     * to a user otherwise, and the fixes are entirely different.
     */
    fun adb(vararg args: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ToolResult? {
        val adb = sdk()?.adb ?: return null
        return run(adb, args.toList(), timeoutMs)
    }

    fun emulator(vararg args: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ToolResult? {
        val emulator = sdk()?.emulator ?: return null
        return run(emulator, args.toList(), timeoutMs)
    }

    /** Off-EDT by contract; bounded by [timeoutMs] so a wedged device can't wedge the caller. */
    fun run(executable: String, args: List<String>, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ToolResult = try {
        val cmd = GeneralCommandLine(listOf(executable) + args)
            .withWorkDirectory(project.basePath)
            .withCharset(Charsets.UTF_8)
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        ToolResult(out.exitCode, out.stdout.trim(), out.stderr.trim(), out.isTimeout)
    } catch (e: Exception) {
        // Metadata only — the command line can carry a package name or serial, and this goes to idea.log.
        thisLogger().info("android: ${executable.substringAfterLast('/')} failed: ${e.javaClass.simpleName}")
        ToolResult(-1, "", e.message.orEmpty())
    }

    companion object {
        /** Long enough for `adb devices` on a cold adb server, short enough not to feel hung. */
        const val DEFAULT_TIMEOUT_MS = 10_000

        fun getInstance(project: Project): AndroidEnvironment = project.getService(AndroidEnvironment::class.java)
    }
}
