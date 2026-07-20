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
}
