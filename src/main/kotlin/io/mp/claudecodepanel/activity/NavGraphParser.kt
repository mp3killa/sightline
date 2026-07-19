package io.mp.claudecodepanel.activity

/**
 * Parses an Android **navigation graph** resource (an XML file under `res/navigation/`) for its destinations —
 * the `android:name` class of each `<fragment>` / `<activity>` / `<dialog>`. Platform-free and
 * unit-tested; the resolution of a destination class name to an actual project file happens in
 * `ProjectStructureEnricher` (index-based, package-aware).
 *
 * Conservative: only fully-qualified class names (at least one `.`) on real destination elements are
 * returned, so `<argument android:name="userId">` and other non-class `android:name` attributes are
 * never mistaken for a screen.
 */
object NavGraphParser {

    /** A navigation destination: its declared class [className] and the element [kind] (fragment/activity/dialog). */
    data class Destination(val className: String, val kind: String)

    private val NAV_ROOT = Regex("<\\s*navigation\\b")
    private val DEST = Regex(
        "<\\s*(fragment|activity|dialog)\\b[^>]*?\\bandroid:name\\s*=\\s*\"([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)+)\"",
    )

    /** True when [text] is (or contains) a `<navigation>` root — i.e. an Android nav graph resource. */
    fun isNavGraph(text: String): Boolean = NAV_ROOT.containsMatchIn(text)

    /** Destinations declared in the nav graph, de-duplicated by class name. Empty when not a nav graph. */
    fun parse(text: String): List<Destination> {
        if (!isNavGraph(text)) return emptyList()
        return DEST.findAll(text)
            .map { Destination(it.groupValues[2], it.groupValues[1].lowercase()) }
            .distinctBy { it.className }
            .take(50)
            .toList()
    }
}
