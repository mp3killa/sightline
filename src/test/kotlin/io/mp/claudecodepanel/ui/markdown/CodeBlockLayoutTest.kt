package io.mp.claudecodepanel.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockLayoutTest {

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test fun countsLines() {
        assertEquals(0, CodeBlockLayout.lineCount(""))
        assertEquals(1, CodeBlockLayout.lineCount("one"))
        assertEquals(2, CodeBlockLayout.lineCount("one\ntwo"))
    }

    @Test fun trailingNewlineIsNotAPhantomLine() {
        // Fenced code almost always ends with a newline; counting it would report every block one line long.
        assertEquals(2, CodeBlockLayout.lineCount("one\ntwo\n"))
        assertEquals(1, CodeBlockLayout.lineCount("\n"))
    }

    @Test fun shortBlocksAreNotCollapsible() {
        assertFalse(CodeBlockLayout.isCollapsible(lines(5)))
        assertFalse(CodeBlockLayout.isCollapsible(lines(CodeBlockLayout.COLLAPSE_THRESHOLD_LINES)))
    }

    @Test fun longBlocksAreCollapsible() {
        assertTrue(CodeBlockLayout.isCollapsible(lines(CodeBlockLayout.COLLAPSE_THRESHOLD_LINES + 1)))
    }

    @Test fun collapsedHeightIsLinesTimesLineHeightPlusPadding() {
        assertEquals(14 * 16 + 8, CodeBlockLayout.collapsedHeight(lineHeight = 16, verticalPadding = 8))
    }

    @Test fun collapsedHeightSurvivesBadMetrics() {
        // A zero/negative font metric must not collapse the block to an invisible sliver.
        assertTrue(CodeBlockLayout.collapsedHeight(lineHeight = 0) >= CodeBlockLayout.COLLAPSED_LINES)
        assertTrue(CodeBlockLayout.collapsedHeight(lineHeight = -4, verticalPadding = -9) > 0)
    }

    @Test fun toggleLabelStatesTheCost() {
        assertEquals("Expand (40 lines)", CodeBlockLayout.toggleLabel(expanded = false, code = lines(40)))
        assertEquals("Collapse", CodeBlockLayout.toggleLabel(expanded = true, code = lines(40)))
    }
}
