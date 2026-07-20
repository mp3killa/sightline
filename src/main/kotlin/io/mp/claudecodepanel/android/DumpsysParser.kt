package io.mp.claudecodepanel.android

/** What is on screen, as far as `dumpsys` can say. */
data class ScreenState(
    /** Fully-qualified activity — `com.example.driver/.MainActivity`. */
    val resumedActivity: String? = null,
    val resumedPackage: String? = null,
    /** Activities in the current task, front first. */
    val backStack: List<String> = emptyList(),
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val densityDpi: Int? = null,
    val orientation: Orientation? = null,
    val nightMode: Boolean? = null,
    val fontScale: Float? = null,
    val locale: String? = null,
) {
    enum class Orientation { PORTRAIT, LANDSCAPE }

    val isEmpty: Boolean get() = resumedActivity == null && screenWidthPx == null

    /** `MainActivity · 1080×2400 · portrait · dark` — the strip/summary form. */
    fun summary(): String = buildList {
        resumedActivity?.let { add(it.substringAfterLast('.').substringAfterLast('/')) }
        if (screenWidthPx != null && screenHeightPx != null) add("${screenWidthPx}×$screenHeightPx")
        orientation?.let { add(it.name.lowercase()) }
        nightMode?.let { add(if (it) "dark" else "light") }
        fontScale?.takeIf { it != 1.0f }?.let { add("font ${it}×") }
    }.joinToString(" · ")
}

/**
 * Parses `dumpsys activity activities` and `dumpsys window` for what is currently on screen.
 *
 * These are shell dumps, not APIs — the format shifts between Android releases, and it is verbose enough
 * that a naive regex finds the wrong occurrence of the right-looking string. So every parser here is
 * anchored to a labelled field, tries the shapes that different platform versions actually print, and
 * **returns null rather than a guess** for anything it doesn't find. A `ScreenState` with three nulls is
 * a useful answer; one with three wrong values is worse than nothing.
 *
 * The deliberate limit, stated because callers must not paper over it: this reports the **Activity**
 * level. It cannot see a Compose navigation route or which composable is on screen — those live in the
 * app's own memory, and pretending otherwise would be the confident-wrong-answer failure this codebase
 * is built to avoid. See [ComposeSourceAnalyzer] for what *can* be established from source.
 */
object DumpsysParser {

    fun parseActivities(output: String): ScreenState {
        val resumed = RESUMED_ACTIVITY.find(output)?.groupValues?.get(1)
            ?: TOP_RESUMED.find(output)?.groupValues?.get(1)
            ?: MRESUMED.find(output)?.groupValues?.get(1)

        return ScreenState(
            resumedActivity = resumed,
            resumedPackage = resumed?.substringBefore('/')?.takeIf { it.isNotBlank() },
            backStack = parseBackStack(output),
        )
    }

    /**
     * Activities in the focused task, front first — the order a back stack is discussed in: the thing
     * you are looking at, then what is behind it.
     *
     * Ordered by the `Hist #N` index rather than by print order. dumpsys does print top-to-bottom (its
     * own header says so), but relying on that means the list silently inverts if the format ever
     * changes — and an inverted back stack looks entirely plausible, so nothing would catch it.
     * The index is explicit: higher N is nearer the front.
     */
    private fun parseBackStack(output: String): List<String> =
        HIST_ENTRY.findAll(output)
            .mapNotNull { m ->
                val activity = m.groupValues[2].takeIf { it.contains('/') } ?: return@mapNotNull null
                (m.groupValues[1].toIntOrNull() ?: 0) to activity
            }
            .distinctBy { it.second }
            .sortedByDescending { it.first }
            .map { it.second }
            .take(12)
            .toList()

    fun parseWindow(output: String): ScreenState {
        val size = DISPLAY_SIZE.find(output)
        val orientation = when (ROTATION.find(output)?.groupValues?.get(1)?.toIntOrNull()) {
            0, 2 -> ScreenState.Orientation.PORTRAIT
            1, 3 -> ScreenState.Orientation.LANDSCAPE
            else -> null
        }
        return ScreenState(
            screenWidthPx = size?.groupValues?.get(1)?.toIntOrNull(),
            screenHeightPx = size?.groupValues?.get(2)?.toIntOrNull(),
            densityDpi = DENSITY.find(output)?.groupValues?.get(1)?.toIntOrNull(),
            orientation = orientation,
        )
    }

    /** `dumpsys uimode` / configuration line — night mode, font scale, locale. */
    fun parseConfiguration(output: String): ScreenState = ScreenState(
        nightMode = when {
            NIGHT_YES.containsMatchIn(output) -> true
            NIGHT_NO.containsMatchIn(output) -> false
            else -> null
        },
        fontScale = FONT_SCALE.find(output)?.groupValues?.get(1)?.toFloatOrNull(),
        locale = LOCALE.find(output)?.groupValues?.get(1),
    )

    /** Merge several dumps into one state, preferring the first non-null for each field. */
    fun merge(vararg states: ScreenState): ScreenState = states.fold(ScreenState()) { acc, s ->
        ScreenState(
            resumedActivity = acc.resumedActivity ?: s.resumedActivity,
            resumedPackage = acc.resumedPackage ?: s.resumedPackage,
            backStack = acc.backStack.ifEmpty { s.backStack },
            screenWidthPx = acc.screenWidthPx ?: s.screenWidthPx,
            screenHeightPx = acc.screenHeightPx ?: s.screenHeightPx,
            densityDpi = acc.densityDpi ?: s.densityDpi,
            orientation = acc.orientation ?: s.orientation,
            nightMode = acc.nightMode ?: s.nightMode,
            fontScale = acc.fontScale ?: s.fontScale,
            locale = acc.locale ?: s.locale,
        )
    }

    // Several shapes, because the field name has changed across platform versions and a device on an
    // older release is exactly the one someone is debugging.
    private val RESUMED_ACTIVITY = Regex("""ResumedActivity:?\s+ActivityRecord\{[^ ]+ [^ ]+ ([^ }]+)""")
    private val TOP_RESUMED = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ [^ ]+ ([^ }]+)""")
    private val MRESUMED = Regex("""mResumedActivity:?\s*=?\s*ActivityRecord\{[^ ]+ [^ ]+ ([^ }]+)""")
    private val HIST_ENTRY = Regex("""Hist\s+#(\d+):\s+ActivityRecord\{[^ ]+ [^ ]+ ([^ }]+)""")

    private val DISPLAY_SIZE = Regex("""(?:init|cur)=(\d+)x(\d+)""")
    private val ROTATION = Regex("""(?:mCurrentRotation|mRotation)=(?:ROTATION_)?(\d)""")
    private val DENSITY = Regex("""(?:mBaseDisplayDensity|density)=(\d+)""")

    private val NIGHT_YES = Regex("""(?:Night mode:\s*yes|uiMode=.*night-yes)""", RegexOption.IGNORE_CASE)
    private val NIGHT_NO = Regex("""(?:Night mode:\s*no|uiMode=.*night-no)""", RegexOption.IGNORE_CASE)
    private val FONT_SCALE = Regex("""fontScale=([\d.]+)""")
    private val LOCALE = Regex("""(?:mLocales|locales)=\[?([a-z]{2}[_-][A-Z]{2})""")

    /** The dumps worth taking, in the order they should run. */
    fun probes(): List<Pair<String, List<String>>> = listOf(
        "activities" to listOf("shell", "dumpsys", "activity", "activities"),
        "window" to listOf("shell", "dumpsys", "window", "displays"),
        "uimode" to listOf("shell", "cmd", "uimode", "night"),
        "config" to listOf("shell", "dumpsys", "activity", "config"),
    )
}
