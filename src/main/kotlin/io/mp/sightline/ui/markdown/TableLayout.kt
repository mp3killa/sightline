package io.mp.sightline.ui.markdown

/**
 * Width rules for GFM tables. Cells wrap, so without a floor a wide table silently squeezes its columns
 * down to a few unreadable characters. Giving every column a minimum width means a wide table instead
 * overflows the panel — and the renderer puts it in a horizontal scroller. Platform-free so it is
 * unit-tested; the renderer supplies the measured viewport width.
 */
object TableLayout {

    /** Narrowest a column may get before the table starts scrolling instead of squeezing. */
    const val MIN_COLUMN_PX = 96

    /** Past this many columns, the floor shrinks — a 9-column table of short cells shouldn't force a scroll. */
    const val WIDE_COLUMN_COUNT = 5

    /** Floor for a single column, in unscaled px. Narrower for many-column tables. */
    fun minColumnWidth(columnCount: Int): Int =
        if (columnCount > WIDE_COLUMN_COUNT) MIN_COLUMN_PX * 2 / 3 else MIN_COLUMN_PX

    /** The width below which the table stops squeezing. Zero columns means no floor at all. */
    fun minTableWidth(columnCount: Int): Int =
        if (columnCount <= 0) 0 else columnCount * minColumnWidth(columnCount)

    /**
     * True when [availableWidth] can't hold the table at its floor, so it needs a horizontal scroller.
     * A non-positive [availableWidth] means "not laid out yet" — never claim a scroll is needed then.
     */
    fun needsHorizontalScroll(columnCount: Int, availableWidth: Int): Boolean =
        availableWidth > 0 && minTableWidth(columnCount) > availableWidth
}
