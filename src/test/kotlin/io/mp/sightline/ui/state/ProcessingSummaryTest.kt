package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingSummaryTest {

    @Test fun emptyTurnSaysNothing() {
        val s = ProcessingSummary()
        assertTrue(s.isEmpty)
        assertEquals("", s.text())
    }

    @Test fun countsEveryOperation() {
        var s = ProcessingSummary()
        repeat(5) { s = s.plus("Read", ToolOutcome.OK, "a$it.kt") }
        assertEquals(5, s.operations)
        assertEquals("5 operations", s.text())
    }

    /** Three edits to one file is one file changed — claiming three overstates the work. */
    @Test fun filesEditedAreDistinct() {
        var s = ProcessingSummary()
        s = s.plus("Edit", ToolOutcome.OK, "A.kt")
        s = s.plus("Edit", ToolOutcome.OK, "A.kt")
        s = s.plus("Edit", ToolOutcome.OK, "B.kt")
        assertEquals(2, s.filesEdited)
        assertEquals(3, s.operations)
    }

    @Test fun failedEditsAreNotCountedAsFilesChanged() {
        val s = ProcessingSummary().plus("Edit", ToolOutcome.ERROR, "A.kt")
        assertEquals(0, s.filesEdited)
        assertEquals(1, s.operations)
    }

    @Test fun readsAreNotFilesEdited() {
        val s = ProcessingSummary().plus("Read", ToolOutcome.OK, "A.kt")
        assertEquals(0, s.filesEdited)
    }

    @Test fun commandsCountAsChecks() {
        var s = ProcessingSummary()
        s = s.plus("Bash", ToolOutcome.OK)
        s = s.plus("Bash", ToolOutcome.OK)
        s = s.plus("Bash", ToolOutcome.ERROR)
        assertEquals(2, s.checksPassed)
        assertEquals(1, s.checksFailed)
        assertTrue(s.text().contains("2 checks passed"))
        assertTrue(s.text().contains("1 failed"))
    }

    @Test fun deniedToolsAreReported() {
        val s = ProcessingSummary().plus("Bash", ToolOutcome.BLOCKED)
        assertTrue("a denial must not vanish from the summary", s.text().contains("1 denied"))
    }

    @Test fun singularsReadNaturally() {
        var s = ProcessingSummary()
        s = s.plus("Edit", ToolOutcome.OK, "A.kt")
        s = s.plus("Bash", ToolOutcome.OK)
        assertEquals("2 operations · 1 file edited · 1 check passed", s.text())
    }

    @Test fun aRealisticTurnReadsWell() {
        var s = ProcessingSummary()
        repeat(12) { s = s.plus("Read", ToolOutcome.OK, "r$it.kt") }
        listOf("A.kt", "B.kt", "C.kt", "D.kt").forEach { s = s.plus("Edit", ToolOutcome.OK, it) }
        repeat(3) { s = s.plus("Bash", ToolOutcome.OK) }
        assertEquals("19 operations · 4 files edited · 3 checks passed", s.text())
    }

    /** An edit with no path still happened; it just can't be de-duplicated into a file count. */
    @Test fun pathlessEditCountsAsWorkButNotAsAFile() {
        val s = ProcessingSummary().plus("Write", ToolOutcome.OK, null)
        assertEquals(1, s.operations)
        assertEquals(0, s.filesEdited)
    }
}
