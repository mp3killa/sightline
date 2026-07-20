package io.mp.claudecodepanel.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android rows of the Health report — see docs/ANDROID.md M0. */
class HealthCheckerAndroidTest {

    private fun inputs(
        androidProject: Boolean = true,
        featuresEnabled: Boolean = true,
        sdk: String? = "/Users/me/Library/Android/sdk",
        sdkSource: String? = "ANDROID_HOME",
        adb: String? = "/Users/me/Library/Android/sdk/platform-tools/adb",
        adbVersion: String? = "1.0.41",
        devices: List<AndroidDeviceSummary>? = listOf(AndroidDeviceSummary("Pixel 8", "ready", true)),
        studio: Boolean = true,
    ) = HealthInputs(
        claudePath = "/usr/local/bin/claude",
        androidProject = androidProject,
        androidFeaturesEnabled = featuresEnabled,
        androidSdkPath = sdk,
        androidSdkSource = sdkSource,
        adbPath = adb,
        adbVersion = adbVersion,
        androidDevices = devices,
        studioModelAvailable = studio,
    )

    private fun androidIds(i: HealthInputs) =
        HealthChecker.evaluate(i).checks.map { it.id }.filter { it.startsWith("android") }

    // ---- the rows appear only where they could be the user's problem ----

    @Test
    fun `a non-Android project gets no Android rows at all`() {
        assertTrue(androidIds(inputs(androidProject = false)).isEmpty())
    }

    @Test
    fun `an Android project gets the SDK, device and variant rows`() {
        assertEquals(listOf("android.sdk", "android.devices", "android.model"), androidIds(inputs()))
    }

    @Test
    fun `features turned off collapses to one honest row`() {
        val report = HealthChecker.evaluate(inputs(featuresEnabled = false))
        assertEquals(listOf("android"), androidIds(inputs(featuresEnabled = false)))
        assertEquals(HealthStatus.WARN, report.byId("android")!!.status)
    }

    // ---- SDK ----

    @Test
    fun `a resolved SDK with adb is OK and names where it came from`() {
        val c = HealthChecker.evaluate(inputs()).byId("android.sdk")!!
        assertEquals(HealthStatus.OK, c.status)
        assertTrue(c.detail.contains("ANDROID_HOME"))
        assertTrue(c.detail.contains("adb 1.0.41"))
    }

    @Test
    fun `no SDK is a failure with an actionable hint`() {
        val c = HealthChecker.evaluate(inputs(sdk = null, adb = null)).byId("android.sdk")!!
        assertEquals(HealthStatus.FAIL, c.status)
        assertNotNull(c.hint)
        assertTrue(c.hint!!.contains("Settings"))
    }

    /** A different problem with a different fix — it must not read as "no SDK". */
    @Test
    fun `an SDK without platform-tools warns about adb specifically`() {
        val c = HealthChecker.evaluate(inputs(adb = null, adbVersion = null)).byId("android.sdk")!!
        assertEquals(HealthStatus.WARN, c.status)
        assertTrue(c.detail.contains("adb"))
        assertTrue(c.hint!!.contains("Platform-Tools"))
    }

    // ---- devices: "couldn't ask" and "none connected" are different answers ----

    @Test
    fun `a ready device is OK`() {
        val c = HealthChecker.evaluate(inputs()).byId("android.devices")!!
        assertEquals(HealthStatus.OK, c.status)
        assertTrue(c.detail.contains("Pixel 8"))
    }

    @Test
    fun `no devices warns and says to start one`() {
        val c = HealthChecker.evaluate(inputs(devices = emptyList())).byId("android.devices")!!
        assertEquals(HealthStatus.WARN, c.status)
        assertTrue(c.hint!!.contains("emulator"))
    }

    @Test
    fun `an unanswerable adb is UNKNOWN, not zero devices`() {
        val c = HealthChecker.evaluate(inputs(devices = null)).byId("android.devices")!!
        assertEquals(HealthStatus.UNKNOWN, c.status)
        assertTrue(c.hint!!.contains("kill-server"))
    }

    @Test
    fun `devices cannot be checked without adb`() {
        val c = HealthChecker.evaluate(inputs(adb = null, devices = null)).byId("android.devices")!!
        assertEquals(HealthStatus.UNKNOWN, c.status)
        assertTrue(c.detail.contains("adb wasn't found"))
    }

    @Test
    fun `an unauthorised device points at the on-device prompt`() {
        val devices = listOf(AndroidDeviceSummary("SM A546B", "unauthorised", false))
        val c = HealthChecker.evaluate(inputs(devices = devices)).byId("android.devices")!!
        assertEquals(HealthStatus.WARN, c.status)
        assertTrue(c.hint!!.contains("USB debugging prompt"))
    }

    @Test
    fun `a mix of ready and not-ready still reads OK but names the state`() {
        val devices = listOf(
            AndroidDeviceSummary("Pixel 8", "ready", true),
            AndroidDeviceSummary("SM A546B", "offline", false),
        )
        val c = HealthChecker.evaluate(inputs(devices = devices)).byId("android.devices")!!
        assertEquals(HealthStatus.OK, c.status)
        assertTrue(c.detail.contains("SM A546B (offline)"))
    }

    // ---- the optional dependency: absent is normal, never a failure ----

    @Test
    fun `the Studio model present reads OK`() {
        val c = HealthChecker.evaluate(inputs(studio = true)).byId("android.model")!!
        assertEquals(HealthStatus.OK, c.status)
    }

    @Test
    fun `the Studio model absent warns about staleness and never fails`() {
        val c = HealthChecker.evaluate(inputs(studio = false)).byId("android.model")!!
        assertEquals(HealthStatus.WARN, c.status)
        assertTrue(c.detail.contains("stale"))
        assertTrue(c.hint!!.contains("expected outside Android Studio"))
    }

    /**
     * Running in plain IntelliJ IDEA is a supported configuration, not a broken one — so no Android row
     * may report FAIL merely because Android Studio's model is absent. Asserted over the Android rows
     * only: the rest of the report has its own reasons to be unhappy in a bare fixture.
     */
    @Test
    fun `a healthy project without Android Studio produces no Android failure`() {
        val android = HealthChecker.evaluate(inputs(studio = false))
            .checks.filter { it.id.startsWith("android") }
        assertTrue(android.isNotEmpty())
        assertTrue(
            "no Android row should FAIL: ${android.filter { it.status == HealthStatus.FAIL }}",
            android.none { it.status == HealthStatus.FAIL },
        )
    }

    /**
     * The Android rows put a filesystem path on the report, and the report is copyable. The sanitiser
     * has to reach them too — this is the round-trip that proves the new rows didn't open a leak.
     */
    @Test
    fun `sanitising the report scrubs the SDK path but keeps the rows`() {
        val sanitized = HealthSanitizer.sanitize(HealthChecker.evaluate(inputs()))
        val sdk = sanitized.byId("android.sdk")!!
        assertEquals("Android SDK", sdk.name)
        assertNotNull(sanitized.byId("android.devices"))
        assertNull("home path leaked into the report", Regex("/Users/me").find(sdk.detail))
        assertTrue("path should survive as a ~ form: ${sdk.detail}", sdk.detail.contains("~"))
    }
}
