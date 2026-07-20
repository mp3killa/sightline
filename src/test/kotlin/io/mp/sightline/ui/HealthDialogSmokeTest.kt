package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.health.HealthGatherer

/**
 * Constructs the Health dialog under the real platform (theme, EDT) and drives a synchronous gather to
 * assert the gatherer produces a coherent report on this machine. Visual correctness is verified live;
 * this guards against construction-time exceptions and a gatherer that throws.
 */
class HealthDialogSmokeTest : BasePlatformTestCase() {

    fun testDialogConstructsAndDisposes() {
        val dialog = HealthDialog(project) {
            HealthGatherer.SessionFacts(
                running = false, sawSession = false, activityEventCount = 0,
                diagnosticsAvailable = true, diagnosticsReason = null,
            )
        }
        try {
            // init() ran createCenterPanel() + the initial recheck() without throwing; these prove it.
            assertEquals("Sightline Health", dialog.title)
            assertFalse(dialog.isModal)
        } finally {
            dialog.close(0)
        }
    }

    fun testGathererProducesAJudgeableReport() {
        val inputs = HealthGatherer(project).gather(
            HealthGatherer.SessionFacts(
                running = false, sawSession = false, activityEventCount = 3,
                diagnosticsAvailable = true, diagnosticsReason = null,
            ),
        )
        // The IDE description should be populated under a real platform, and diagnostics should be OK.
        assertNotNull(inputs.ideDescription)
        val report = io.mp.sightline.health.HealthChecker.evaluate(inputs)
        assertEquals(7, report.checks.size)
        assertNotNull(report.byId("diagnostics"))
        // Whatever the machine's CLI state, every non-OK check must still carry a hint.
        report.actionable.forEach { assertNotNull("${it.id} needs a hint", it.hint) }
    }
}
