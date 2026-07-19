package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionSummaryTest {

    @Test fun formatsLabelDurationTurnsAndCost() {
        assertEquals(
            "Completed · 51.6s · 13 turns · $0.404",
            CompletionSummary.footer(costUsd = 0.404, durationMs = 51_600.0, numTurns = 13, isError = false),
        )
    }

    @Test fun erroredRunLeadsWithStopped() {
        assertEquals(
            "Stopped · 2.0s · 1 turn · $0.01",
            CompletionSummary.footer(costUsd = 0.01, durationMs = 2_000.0, numTurns = 1, isError = true),
        )
    }

    @Test fun longRunsUseMinutesAndSeconds() {
        assertEquals(
            "Completed · 1m 05s · 40 turns",
            CompletionSummary.footer(costUsd = null, durationMs = 65_000.0, numTurns = 40, isError = false),
        )
    }

    @Test fun omitsMissingOrZeroParts() {
        assertEquals("Completed", CompletionSummary.footer(null, null, null, isError = false))
        assertEquals("Completed", CompletionSummary.footer(costUsd = 0.0, durationMs = 0.0, numTurns = 0, isError = false))
    }

    @Test fun costKeepsAtLeastTwoDecimalsAndTrimsTrailingZeros() {
        assertEquals("Completed · $0.40", CompletionSummary.footer(0.4, null, null, isError = false))
        assertEquals("Completed · $0.4567", CompletionSummary.footer(0.4567, null, null, isError = false))
    }
}
