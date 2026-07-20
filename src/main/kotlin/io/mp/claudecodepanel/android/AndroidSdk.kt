package io.mp.claudecodepanel.android

/** A place the SDK might live, and how we came to look there — the source is shown in the Health panel. */
data class SdkCandidate(val path: String, val source: String)

/**
 * A located SDK. [adb] and [emulator] are null when the root exists but the tool doesn't — a real and
 * common state (a fresh SDK install without platform-tools), and one that must read as "SDK found, adb
 * missing" rather than "no SDK", because the fix is different.
 */
data class AndroidSdk(
    val root: String,
    val source: String,
    val adb: String?,
    val emulator: String?,
) {
    val hasAdb: Boolean get() = adb != null
}

/**
 * Finds the Android SDK without any IntelliJ or Android Studio API — the tier-2/3 half of the fact
 * ladder (docs/ANDROID.md §1.2), so device work keeps functioning in a plain IntelliJ IDEA.
 *
 * Split into a pure [candidates] (which places, in which order, and why) and a pure [resolve] that takes
 * an injected `exists` probe, so the whole ordering policy is unit-tested with no filesystem. Same shape
 * as `ui/markdown/FileRefDetector`'s injected resolver.
 *
 * Order matters and is deliberate: an explicit setting beats the environment, the environment beats the
 * project's `local.properties`, and only then do we guess at the per-OS default. Guessing last means a
 * machine with two SDKs uses the one the user actually configured.
 */
object AndroidSdkLocator {

    /** Relative locations of the tools we care about, per the SDK's stable layout. */
    private const val ADB_REL = "platform-tools/adb"
    private const val EMULATOR_REL = "emulator/emulator"

    fun candidates(
        configured: String? = null,
        env: Map<String, String> = System.getenv(),
        localProperties: String? = null,
        userHome: String? = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): List<SdkCandidate> {
        val out = LinkedHashMap<String, SdkCandidate>()
        fun add(path: String?, source: String) {
            val p = path?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return
            out.putIfAbsent(p, SdkCandidate(p, source))
        }

        add(configured, "configured in Settings")
        add(env["ANDROID_HOME"], "ANDROID_HOME")
        add(env["ANDROID_SDK_ROOT"], "ANDROID_SDK_ROOT")
        add(localProperties?.let { sdkDirFrom(it) }, "local.properties")

        if (!userHome.isNullOrBlank()) {
            val home = userHome.trimEnd('/')
            val os = osName.lowercase()
            when {
                os.contains("mac") || os.contains("darwin") -> add("$home/Library/Android/sdk", "default location")
                os.contains("win") -> {
                    add(env["LOCALAPPDATA"]?.let { "$it/Android/Sdk" }, "default location")
                    add("$home/AppData/Local/Android/Sdk", "default location")
                }
                else -> add("$home/Android/Sdk", "default location")
            }
        }
        return out.values.toList()
    }

    /** First candidate whose root exists. A root without adb still wins — see [AndroidSdk]. */
    fun resolve(candidates: List<SdkCandidate>, exists: (String) -> Boolean): AndroidSdk? {
        for (c in candidates) {
            if (!exists(c.path)) continue
            val adb = "${c.path}/$ADB_REL".takeIf(exists)
            val emulator = "${c.path}/$EMULATOR_REL".takeIf(exists)
            return AndroidSdk(c.path, c.source, adb, emulator)
        }
        return null
    }

    /**
     * `sdk.dir` out of a `local.properties` body. Hand-rolled rather than `java.util.Properties` because
     * the escaping is the whole point: Gradle writes Windows paths as `C\:\\Users\\me\\Sdk`, and a naive
     * read leaves the backslashes in, producing a path that exists nowhere.
     */
    fun sdkDirFrom(localProperties: String): String? {
        for (raw in localProperties.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            if (line.substring(0, eq).trim() != "sdk.dir") continue
            return unescapeProperties(line.substring(eq + 1).trim()).takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun unescapeProperties(v: String): String {
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val ch = v[i]
            if (ch == '\\' && i + 1 < v.length) {
                // `\\` → `\`, `\:` → `:`, and any other escape drops the backslash, which matches
                // java.util.Properties' behaviour for unrecognised escapes.
                sb.append(v[i + 1]); i += 2
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString().replace('\\', '/')
    }
}
