package io.mp.sightline.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckerTest {

    private val healthy = HealthInputs(
        claudePath = "/opt/homebrew/bin/claude",
        claudePathSource = "common install dir",
        claudeVersion = "1.2.3 (Claude Code)",
        sessionRunning = true,
        sawSession = true,
        ideIntegrationEnabled = true,
        ideServerPort = 51234,
        diagnosticsAvailable = true,
        permissionMode = "auto",
        model = "sonnet",
        activityEventCount = 12,
    )

    @Test fun aFullyHealthySetupIsAllGreen() {
        val r = HealthChecker.evaluate(healthy)
        assertEquals(HealthStatus.OK, r.overall)
        assertTrue(r.actionable.isEmpty())
        assertEquals("Everything checks out", r.headline)
    }

    @Test fun missingCliFailsAndCascadesToUnknownNotGreen() {
        val r = HealthChecker.evaluate(healthy.copy(claudePath = null, claudeVersion = null, sawSession = false))
        assertEquals(HealthStatus.FAIL, r.byId("cli")!!.status)
        // The dependent checks must not claim OK when their precondition is gone.
        assertEquals(HealthStatus.UNKNOWN, r.byId("version")!!.status)
        assertEquals(HealthStatus.UNKNOWN, r.byId("auth")!!.status)
        assertEquals(HealthStatus.FAIL, r.overall)
        assertNotNull("a failure must carry a next step", r.byId("cli")!!.hint)
    }

    @Test fun unknownDoesNotMasqueradeAsFailButBlocksAllGood() {
        // Binary present, but version probe produced nothing: that's UNKNOWN, and the headline reflects it.
        val r = HealthChecker.evaluate(healthy.copy(claudeVersion = null, sawSession = false, sessionRunning = false))
        assertEquals(HealthStatus.UNKNOWN, r.byId("version")!!.status)
        assertTrue(r.overall == HealthStatus.UNKNOWN || r.overall == HealthStatus.WARN)
        assertTrue("not a clean bill of health", r.overall != HealthStatus.OK)
    }

    @Test fun disabledBridgeWarnsRatherThanFails() {
        val r = HealthChecker.evaluate(healthy.copy(ideIntegrationEnabled = false, ideServerPort = null))
        val bridge = r.byId("bridge")!!
        assertEquals(HealthStatus.WARN, bridge.status)
        assertNotNull(bridge.hint)
    }

    @Test fun enabledButUnboundBridgeIsAFailure() {
        val r = HealthChecker.evaluate(healthy.copy(ideIntegrationEnabled = true, ideServerPort = -1))
        assertEquals(HealthStatus.FAIL, r.byId("bridge")!!.status)
    }

    @Test fun bridgeDetailNeverLeaksTheActualPort() {
        // The port is ephemeral and not useful in a report; describe it, don't print it.
        val r = HealthChecker.evaluate(healthy.copy(ideServerPort = 51234))
        assertTrue(r.byId("bridge")!!.status == HealthStatus.OK)
        assertTrue("port number should not appear", !r.byId("bridge")!!.detail.contains("51234"))
    }

    @Test fun indexingDiagnosticsWarnWithAWaitHint() {
        val r = HealthChecker.evaluate(healthy.copy(diagnosticsAvailable = false, diagnosticsReason = "indexing"))
        val d = r.byId("diagnostics")!!
        assertEquals(HealthStatus.WARN, d.status)
        assertTrue(d.hint!!.contains("indexing", true))
    }

    @Test fun unknownDiagnosticsAreNotAWarning() {
        val r = HealthChecker.evaluate(healthy.copy(diagnosticsAvailable = null))
        assertEquals(HealthStatus.UNKNOWN, r.byId("diagnostics")!!.status)
    }

    @Test fun autoModeFallbackIsSurfacedAsAWarning() {
        val r = HealthChecker.evaluate(healthy.copy(permissionMode = "auto", permissionModeFellBack = true, model = "haiku"))
        val p = r.byId("permission")!!
        assertEquals(HealthStatus.WARN, p.status)
        assertTrue(p.hint!!.contains("Sonnet") || p.hint!!.contains("Opus"))
    }

    @Test fun runningButUnconfirmedSessionIsUnknownNotOk() {
        val r = HealthChecker.evaluate(healthy.copy(sawSession = false, sessionRunning = true))
        assertEquals(HealthStatus.UNKNOWN, r.byId("auth")!!.status)
    }

    @Test fun activityIsAlwaysInformationalNeverAProblem() {
        assertEquals(HealthStatus.OK, HealthChecker.evaluate(healthy.copy(activityEventCount = 0)).byId("activity")!!.status)
        assertTrue(HealthChecker.evaluate(healthy.copy(activityEventCount = 0)).byId("activity")!!.detail.contains("No activity"))
    }

    @Test fun actionableIsSortedWorstFirst() {
        val r = HealthChecker.evaluate(
            healthy.copy(
                ideIntegrationEnabled = false, ideServerPort = null, // WARN
                claudePath = null, claudeVersion = null, sawSession = false, // FAIL + UNKNOWNs
            ),
        )
        val statuses = r.actionable.map { it.status }
        assertEquals("worst first", statuses.sortedByDescending { it.ordinal }, statuses)
        assertEquals(HealthStatus.FAIL, statuses.first())
    }

    @Test fun everyNonOkCheckHasAHint() {
        val r = HealthChecker.evaluate(
            healthy.copy(claudePath = null, ideIntegrationEnabled = false, diagnosticsAvailable = false, diagnosticsReason = "indexing"),
        )
        r.actionable.filter { it.status == HealthStatus.FAIL || it.status == HealthStatus.WARN }
            .forEach { assertNotNull("${it.id} needs a hint", it.hint) }
    }

    @Test fun worseOfOrdersStatusesCorrectly() {
        assertEquals(HealthStatus.FAIL, HealthStatus.OK.worseOf(HealthStatus.FAIL))
        assertEquals(HealthStatus.FAIL, HealthStatus.UNKNOWN.worseOf(HealthStatus.FAIL))
        assertEquals(HealthStatus.UNKNOWN, HealthStatus.WARN.worseOf(HealthStatus.UNKNOWN))
        assertEquals(HealthStatus.WARN, HealthStatus.OK.worseOf(HealthStatus.WARN))
    }
}
