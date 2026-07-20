package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffPresentationTest {

    private fun rows(add: Int, del: Int, ctx: Int = 0): List<Pair<String, String>> =
        List(add) { "add" to "+$it" } + List(del) { "del" to "-$it" } + List(ctx) { "ctx" to " $it" }

    @Test fun countsOnlyChangedLines() {
        val s = DiffPresentation.stat(rows(add = 33, del = 8, ctx = 100))
        assertEquals(33, s.added)
        assertEquals(8, s.removed)
        assertFalse(s.isEmpty)
    }

    @Test fun headerReadsNaturally() {
        assertEquals("Added 33 lines · Removed 8 lines", DiffPresentation.headerText(DiffStat(33, 8)))
        assertEquals("Added 5 lines", DiffPresentation.headerText(DiffStat(5, 0)))
        assertEquals("Removed 2 lines", DiffPresentation.headerText(DiffStat(0, 2)))
        assertEquals("No changes", DiffPresentation.headerText(DiffStat(0, 0)))
    }

    /** "Added 1 lines" in a header a reader scans looks like a bug in the tool. */
    @Test fun singularIsNotPluralised() {
        assertEquals("Added 1 line", DiffPresentation.headerText(DiffStat(1, 0)))
        assertEquals("Removed 1 line", DiffPresentation.headerText(DiffStat(0, 1)))
        assertEquals("Added 1 line · Removed 1 line", DiffPresentation.headerText(DiffStat(1, 1)))
    }

    @Test fun sideBySideOnlyWhenThereIsRoom() {
        assertEquals(DiffLayout.UNIFIED, DiffPresentation.layout(400))
        assertEquals(DiffLayout.UNIFIED, DiffPresentation.layout(DiffPresentation.SIDE_BY_SIDE_MIN_WIDTH - 1))
        assertEquals(DiffLayout.SIDE_BY_SIDE, DiffPresentation.layout(DiffPresentation.SIDE_BY_SIDE_MIN_WIDTH))
        assertEquals(DiffLayout.SIDE_BY_SIDE, DiffPresentation.layout(1400))
    }

    /** Breakpoints are logical, so a HiDPI panel doesn't get a two-column diff it has no room for. */
    @Test fun layoutBreakpointIsScaleAware() {
        assertEquals(DiffLayout.UNIFIED, DiffPresentation.layout(1400, scale = 2f))
        assertEquals(DiffLayout.SIDE_BY_SIDE, DiffPresentation.layout(1700, scale = 2f))
    }

    @Test fun shortDiffsRenderWhole() {
        assertFalse(DiffPresentation.isCollapsible(10))
        assertEquals(10, DiffPresentation.visibleRows(10, expanded = false))
        assertEquals(0, DiffPresentation.overflow(10, expanded = false))
    }

    @Test fun longDiffsCollapseButExpandFully() {
        assertTrue(DiffPresentation.isCollapsible(200))
        assertEquals(DiffPresentation.COLLAPSED_ROWS, DiffPresentation.visibleRows(200, expanded = false))
        assertEquals(200, DiffPresentation.visibleRows(200, expanded = true))
    }

    /** A whole-file Write used to render unbounded; past the ceiling the rest is reported, not dropped. */
    @Test fun hugeDiffsAreCappedEvenWhenExpanded() {
        val huge = 5_000
        assertEquals(DiffPresentation.MAX_ROWS, DiffPresentation.visibleRows(huge, expanded = true))
        val hidden = DiffPresentation.overflow(huge, expanded = true)
        assertEquals(huge - DiffPresentation.MAX_ROWS, hidden)
        val text = DiffPresentation.overflowText(hidden)
        assertTrue("the cap must be stated, not silent", text!!.contains("$hidden more lines"))
    }

    @Test fun noOverflowNoticeWhenNothingIsHidden() {
        assertNull(DiffPresentation.overflowText(0))
        assertNull(DiffPresentation.overflowText(-3))
    }
}
