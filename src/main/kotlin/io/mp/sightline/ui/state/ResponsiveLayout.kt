package io.mp.sightline.ui.state

/** Coarse width classes the panel adapts to (docked narrow, normal, or wide/floating). */
enum class LayoutProfile { NARROW, MEDIUM, WIDE }

/**
 * Maps a component's current width to a [LayoutProfile] and derives which affordances stay visible.
 * Widths are compared in *unscaled* units (callers divide the pixel width by the presentation
 * scale) so breakpoints track logical size, not DPI. Platform-free/testable.
 */
object ResponsiveLayout {

    const val NARROW_MAX = 520
    const val MEDIUM_MAX = 900

    fun profile(widthPx: Int, scale: Float = 1f): LayoutProfile {
        val logical = if (scale > 0f) widthPx / scale else widthPx.toFloat()
        return when {
            logical < NARROW_MAX -> LayoutProfile.NARROW
            logical < MEDIUM_MAX -> LayoutProfile.MEDIUM
            else -> LayoutProfile.WIDE
        }
    }

    /** Header: show the "Claude Code" wordmark and the state text label (icon-only when narrow). */
    fun showHeaderLabels(p: LayoutProfile): Boolean = p != LayoutProfile.NARROW

    /** Whether SPLIT is offered as a layout (only meaningful when there's room for two panes). */
    fun allowSplit(p: LayoutProfile): Boolean = p != LayoutProfile.NARROW

    /** SPLIT is only a sensible *default* on a genuinely wide/floating panel. */
    fun allowSplitDefault(p: LayoutProfile): Boolean = p == LayoutProfile.WIDE

    /** Narrow panels can't afford a side-by-side inspector, so it overlays/replaces instead. */
    fun inspectorAsOverlay(p: LayoutProfile): Boolean = p == LayoutProfile.NARROW

    /** Constrain the transcript to a comfortable reading width once the panel gets wide. */
    fun maxReadableWidth(p: LayoutProfile): Int = when (p) {
        LayoutProfile.NARROW -> Int.MAX_VALUE
        LayoutProfile.MEDIUM -> Int.MAX_VALUE
        LayoutProfile.WIDE -> 760
    }

    /** Breathing room at the edge of the transcript when no reading cap applies. */
    const val BASE_PADDING = 14

    /**
     * Never squeeze the conversation below this, whatever the cap arithmetic says. A safety floor:
     * text wrapping every three or four words is far worse than a column that runs slightly wide.
     */
    const val MIN_CONTENT_WIDTH = 360

    /**
     * Symmetric padding that centres a readable column inside [available] logical px.
     *
     * [available] must be the width of the **column the text actually occupies** — in a split layout
     * that is the chat pane, not the whole panel. Measuring the panel here produced padding sized for a
     * width the text never had, collapsing the conversation into a sliver.
     */
    fun readablePadding(available: Int): Int {
        if (available <= 0) return BASE_PADDING
        val cap = maxReadableWidth(profile(available))
        if (cap == Int.MAX_VALUE) return BASE_PADDING
        val ceiling = ((available - MIN_CONTENT_WIDTH) / 2).coerceAtLeast(BASE_PADDING)
        return ((available - cap) / 2).coerceIn(BASE_PADDING, ceiling)
    }
}
