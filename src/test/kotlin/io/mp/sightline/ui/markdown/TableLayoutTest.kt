package io.mp.sightline.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLayoutTest {

    @Test fun narrowTablesKeepTheFullColumnFloor() {
        assertEquals(TableLayout.MIN_COLUMN_PX, TableLayout.minColumnWidth(3))
        assertEquals(3 * TableLayout.MIN_COLUMN_PX, TableLayout.minTableWidth(3))
    }

    @Test fun manyColumnTablesGetANarrowerFloor() {
        // Otherwise a 9-column table of one-word cells would scroll for no reason.
        assertTrue(TableLayout.minColumnWidth(9) < TableLayout.minColumnWidth(3))
    }

    @Test fun emptyTableHasNoFloor() {
        assertEquals(0, TableLayout.minTableWidth(0))
        assertFalse(TableLayout.needsHorizontalScroll(columnCount = 0, availableWidth = 100))
    }

    @Test fun wideTableInNarrowPanelScrolls() {
        assertTrue(TableLayout.needsHorizontalScroll(columnCount = 6, availableWidth = 300))
    }

    @Test fun tableThatFitsDoesNotScroll() {
        assertFalse(TableLayout.needsHorizontalScroll(columnCount = 2, availableWidth = 800))
    }

    @Test fun unlaidOutPanelNeverClaimsScroll() {
        // Width 0 means "no layout pass yet", not "infinitely narrow".
        assertFalse(TableLayout.needsHorizontalScroll(columnCount = 6, availableWidth = 0))
    }

    /**
     * The floor is not the whole test. A 3-column table of real prose clears the floor easily and still
     * doesn't fit — and squeezing it does not wrap the cells, it collapses them to a negative size and
     * paints an empty box (see WideTableReproTest). What the cells ask for has to count.
     */
    @Test fun tableWiderThanThePanelScrollsEvenWhenItClearsTheFloor() {
        assertFalse("the floor alone says this fits", TableLayout.needsHorizontalScroll(3, availableWidth = 754))
        assertTrue(
            "but the cells want 888px, so it must scroll rather than squeeze",
            TableLayout.needsHorizontalScroll(3, availableWidth = 754, naturalWidth = 888),
        )
    }

    @Test fun tableNarrowerThanThePanelStillDoesNotScroll() {
        assertFalse(TableLayout.needsHorizontalScroll(3, availableWidth = 754, naturalWidth = 400))
    }

    @Test fun unmeasuredNaturalWidthIsIgnoredRatherThanTreatedAsZeroWidth() {
        // 0 means "nothing measured yet"; it must not read as "the table wants no space".
        assertFalse(TableLayout.needsHorizontalScroll(2, availableWidth = 800, naturalWidth = 0))
    }
}
