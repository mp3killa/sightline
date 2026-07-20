package io.mp.sightline.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReportTest {

    private fun check(status: HealthStatus) = HealthCheck("x$status", "Check $status", status, "detail")

    @Test fun overallIsWorstOfAllChecks() {
        assertEquals(HealthStatus.OK, HealthReport(listOf(check(HealthStatus.OK), check(HealthStatus.OK))).overall)
        assertEquals(HealthStatus.WARN, HealthReport(listOf(check(HealthStatus.OK), check(HealthStatus.WARN))).overall)
        assertEquals(HealthStatus.FAIL, HealthReport(listOf(check(HealthStatus.WARN), check(HealthStatus.FAIL))).overall)
    }

    @Test fun unknownSitsBetweenWarnAndFail() {
        assertEquals(HealthStatus.UNKNOWN, HealthReport(listOf(check(HealthStatus.WARN), check(HealthStatus.UNKNOWN))).overall)
        assertEquals(HealthStatus.FAIL, HealthReport(listOf(check(HealthStatus.UNKNOWN), check(HealthStatus.FAIL))).overall)
    }

    @Test fun emptyReportIsOk() {
        assertEquals(HealthStatus.OK, HealthReport(emptyList()).overall)
    }

    @Test fun headlineCountsProblems() {
        val r = HealthReport(listOf(check(HealthStatus.FAIL), check(HealthStatus.FAIL), check(HealthStatus.OK)))
        assertEquals("2 problems found", r.headline)
        assertEquals("1 problem found", HealthReport(listOf(check(HealthStatus.FAIL))).headline)
    }

    @Test fun headlineNamesUnknownsWhenTheyAreTheWorst() {
        val r = HealthReport(listOf(check(HealthStatus.UNKNOWN), check(HealthStatus.OK)))
        assertTrue(r.headline.contains("could not be run"))
    }

    @Test fun reportTextRoundTripsStructureAndArrows() {
        val r = HealthReport(
            listOf(
                HealthCheck("cli", "Claude CLI", HealthStatus.OK, "Found."),
                HealthCheck("bridge", "IDE integration", HealthStatus.WARN, "Off.", hint = "Turn it on."),
            ),
        )
        val text = HealthSanitizer.toReportText(r)
        assertTrue(text.contains("[OK] Claude CLI: Found."))
        assertTrue(text.contains("[WARN] IDE integration: Off."))
        assertTrue("actionable hint is included", text.contains("→ Turn it on."))
        assertFalse("OK checks don't get an arrow", text.contains("→ Found"))
    }
}
