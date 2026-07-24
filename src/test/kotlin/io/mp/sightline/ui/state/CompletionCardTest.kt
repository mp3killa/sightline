package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionCardTest {

    private fun summary(ops: Int = 1, checksFailed: Int = 0, denied: Int = 0): ProcessingSummary {
        var s = ProcessingSummary()
        repeat(ops) { s = s.plus("Read", ToolOutcome.OK, null) }
        repeat(checksFailed) { s = s.plus("Bash", ToolOutcome.ERROR, null) }
        repeat(denied) { s = s.plus("Bash", ToolOutcome.BLOCKED, null) }
        return s
    }

    @Test fun cleanRunIsCompletedWithNoWarnings() {
        val v = CompletionCard.of(summary(), 0.404, 51_600.0, 13, isError = false, recoveredFailures = 0)
        assertEquals(CompletionCard.State.COMPLETED, v.state)
        assertEquals("Completed", v.headline)
        assertEquals("51.6s · 13 turns · $0.404", v.meta)
        assertTrue(v.warnings.isEmpty())
    }

    @Test fun recoveredFailureDowngradesToCompletedWithWarnings() {
        val v = CompletionCard.of(summary(), null, null, null, isError = false, recoveredFailures = 1)
        assertEquals(CompletionCard.State.COMPLETED_WITH_WARNINGS, v.state)
        assertEquals("Completed with warnings", v.headline)
        assertEquals(listOf("1 recovered command failure"), v.warnings)
    }

    @Test fun failedChecksAndDenialsAreWarnings() {
        val v = CompletionCard.of(summary(checksFailed = 2, denied = 1), null, null, null, isError = false, recoveredFailures = 0)
        assertEquals(CompletionCard.State.COMPLETED_WITH_WARNINGS, v.state)
        assertTrue(v.warnings.contains("2 checks failed"))
        assertTrue(v.warnings.contains("1 action denied"))
    }

    @Test fun erroredRunIsStoppedRegardlessOfWarnings() {
        // A hard stop is STOPPED even if there were also recovered failures — the terminal verdict wins.
        val v = CompletionCard.of(summary(), null, 2_000.0, 1, isError = true, recoveredFailures = 3)
        assertEquals(CompletionCard.State.STOPPED, v.state)
        assertEquals("Stopped", v.headline)
        assertTrue(v.warnings.isNotEmpty())
    }

    @Test fun pluralisesRecoveredFailures() {
        val v = CompletionCard.of(summary(), null, null, null, isError = false, recoveredFailures = 2)
        assertEquals(listOf("2 recovered command failures"), v.warnings)
    }
}
