package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows a user reads before approving an edit. Until this was extracted from `ClaudePanel` it was
 * the one real algorithm in the codebase with no coverage.
 */
class LineDiffTest {

    private fun kinds(rows: List<Pair<String, String>>) = rows.joinToString("") {
        when (it.first) { LineDiff.ADD -> "+"; LineDiff.DEL -> "-"; else -> "=" }
    }

    @Test
    fun `identical text is all context`() {
        val rows = LineDiff.diff("a\nb\nc", "a\nb\nc")
        assertEquals("===", kinds(rows))
        assertEquals(listOf("a", "b", "c"), rows.map { it.second })
    }

    @Test
    fun `a replaced line reads as delete then add`() {
        val rows = LineDiff.diff("a\nb\nc", "a\nX\nc")
        assertEquals("=-+=", kinds(rows))
        assertEquals(LineDiff.DEL to "b", rows[1])
        assertEquals(LineDiff.ADD to "X", rows[2])
    }

    @Test
    fun `an inserted line is an addition and keeps the surrounding context`() {
        val rows = LineDiff.diff("a\nc", "a\nb\nc")
        assertEquals("=+=", kinds(rows))
        assertEquals(LineDiff.ADD to "b", rows[1])
    }

    @Test
    fun `a deleted line is a deletion`() {
        assertEquals("=-=", kinds(LineDiff.diff("a\nb\nc", "a\nc")))
    }

    @Test
    fun `unchanged lines are never duplicated across both sides`() {
        val rows = LineDiff.diff("keep\nold", "keep\nnew")
        assertEquals(1, rows.count { it.second == "keep" })
    }

    /** A `Write` to a new file passes "" as the old text; it must read as an addition, not as nothing. */
    @Test
    fun `writing a new file renders as additions`() {
        val rows = LineDiff.diff("", "line one\nline two")
        assertTrue(rows.any { it == LineDiff.ADD to "line one" })
        assertTrue(rows.any { it == LineDiff.ADD to "line two" })
        assertTrue(rows.none { it.first == LineDiff.CTX && it.second.isNotEmpty() })
    }

    @Test
    fun `deleting all content renders as deletions`() {
        val rows = LineDiff.diff("gone\nalso gone", "")
        assertEquals(2, rows.count { it.first == LineDiff.DEL })
    }

    @Test
    fun `every input line appears in the output exactly once per side`() {
        val a = "one\ntwo\nthree\nfour"
        val b = "one\ntwo prime\nthree\nfive"
        val rows = LineDiff.diff(a, b)
        val kept = rows.filter { it.first != LineDiff.ADD }.map { it.second }
        val produced = rows.filter { it.first != LineDiff.DEL }.map { it.second }
        assertEquals(a.split("\n"), kept)
        assertEquals(b.split("\n"), produced)
    }

    /**
     * Past [LineDiff.MAX_CELLS] the LCS table is abandoned. The result must still describe the whole
     * change — degrading to a coarser diff is acceptable, losing lines is not.
     */
    @Test
    fun `past the cell ceiling it degrades to a whole-file replacement without losing lines`() {
        val a = (1..700).joinToString("\n") { "old $it" }
        val b = (1..700).joinToString("\n") { "new $it" }
        assertTrue(700L * 700L > LineDiff.MAX_CELLS)
        val rows = LineDiff.diff(a, b)
        assertEquals(700, rows.count { it.first == LineDiff.DEL })
        assertEquals(700, rows.count { it.first == LineDiff.ADD })
        assertEquals(0, rows.count { it.first == LineDiff.CTX })
    }

    @Test
    fun `sign markers match what the unified view and the clipboard copy render`() {
        assertEquals("+ ", LineDiff.sign(LineDiff.ADD))
        assertEquals("- ", LineDiff.sign(LineDiff.DEL))
        assertEquals("  ", LineDiff.sign(LineDiff.CTX))
        assertEquals("  ", LineDiff.sign("something unexpected"))
    }

    /** DiffPresentation counts these strings; a rename here silently breaks the line counts. */
    @Test
    fun `row kinds are the strings DiffPresentation counts`() {
        assertEquals("add", LineDiff.ADD)
        assertEquals("del", LineDiff.DEL)
        assertEquals("ctx", LineDiff.CTX)
    }
}
