package io.mp.claudecodepanel.ui.markdown

/**
 * Sizing rules for fenced code blocks. A long block would otherwise push the rest of the turn — and the
 * assistant's following prose — off-screen, so blocks past [COLLAPSE_THRESHOLD_LINES] render capped at
 * [COLLAPSED_LINES] with an Expand/Collapse toggle. Platform-free so it is unit-tested; the renderer
 * supplies the measured line height and does the actual clamping.
 */
object CodeBlockLayout {

    /** Blocks longer than this get a toggle. Short blocks always render whole — a toggle there is noise. */
    const val COLLAPSE_THRESHOLD_LINES = 24

    /** How much of a collapsed block stays visible: enough to recognise it, short enough to scan past. */
    const val COLLAPSED_LINES = 14

    /** Lines in [code], ignoring a single trailing newline so `"a\nb\n"` counts as 2, not 3. */
    fun lineCount(code: String): Int {
        if (code.isEmpty()) return 0
        val body = code.removeSuffix("\n")
        if (body.isEmpty()) return 1
        return body.count { it == '\n' } + 1
    }

    fun isCollapsible(code: String): Boolean = lineCount(code) > COLLAPSE_THRESHOLD_LINES

    /**
     * Pixel height for the collapsed view. [lineHeight] is the rendered mono line height; [verticalPadding]
     * is the block's own inset. Never returns less than one line so a bad font metric can't collapse to zero.
     */
    fun collapsedHeight(lineHeight: Int, verticalPadding: Int = 0): Int =
        (lineHeight.coerceAtLeast(1) * COLLAPSED_LINES) + verticalPadding.coerceAtLeast(0)

    /** Toggle text. Collapsed shows the size so the reader knows what expanding costs. */
    fun toggleLabel(expanded: Boolean, code: String): String =
        if (expanded) "Collapse" else "Expand (${lineCount(code)} lines)"
}
