package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionsTest {

    private val serial = "emulator-5554"

    @Test
    fun `the serial is applied to every device-scoped action`() {
        val a = DeviceActions.forceStop(serial, "com.example.driver")
        assertEquals(listOf("-s", serial, "shell", "am", "force-stop", "com.example.driver"), a.args)
    }

    @Test
    fun `no serial means no device flags`() {
        assertEquals(listOf("devices", "-l"), DeviceActions.listDevices().args)
    }

    /**
     * The structural guarantee: building an action classifies it, so a new action cannot be added that
     * bypasses the gate by forgetting to ask.
     */
    @Test
    fun `every action is risk-classified by construction`() {
        val actions = listOf(
            DeviceActions.install(serial, "app.apk"),
            DeviceActions.uninstall(serial, "com.x"),
            DeviceActions.launch(serial, "com.x"),
            DeviceActions.forceStop(serial, "com.x"),
            DeviceActions.clearData(serial, "com.x"),
            DeviceActions.screenshot(serial),
            DeviceActions.logcat(serial),
            DeviceActions.setFontScale(serial, 1.5f),
        )
        assertTrue(actions.all { it.verdict.summary.isNotBlank() })
    }

    @Test
    fun `data-destroying actions require confirmation`() {
        assertTrue(DeviceActions.clearData(serial, "com.x").needsConfirmation)
        assertTrue(DeviceActions.uninstall(serial, "com.x").needsConfirmation)
        assertTrue(DeviceActions.revokePermission(serial, "com.x", "android.permission.CAMERA").needsConfirmation)
    }

    @Test
    fun `observation and reversible changes do not`() {
        assertFalse(DeviceActions.listDevices().needsConfirmation)
        assertFalse(DeviceActions.screenshot(serial).needsConfirmation)
        assertFalse(DeviceActions.currentActivity(serial).needsConfirmation)
        assertFalse(DeviceActions.forceStop(serial, "com.x").needsConfirmation)
        assertFalse(DeviceActions.setFontScale(serial, 1.5f).needsConfirmation)
        assertFalse(DeviceActions.setDarkMode(serial, true).needsConfirmation)
        assertFalse(DeviceActions.install(serial, "app.apk").needsConfirmation)
    }

    @Test
    fun `a destructive action says what will be lost`() {
        val v = DeviceActions.clearData(serial, "com.example.driver").verdict
        assertTrue(v.summary.contains("com.example.driver"))
        assertTrue(v.loses!!.contains("databases"))
    }

    /**
     * A deep link's query string routinely contains `&`. Passing it as one argv element rather than
     * interpolating into a shell string is what stops it becoming a second command.
     */
    @Test
    fun `a deep link uri is a single argv element`() {
        val a = DeviceActions.deepLink(serial, "demo://delivery/ORD-12345?token=abc&next=home")
        assertTrue(a.args.contains("demo://delivery/ORD-12345?token=abc&next=home"))
        assertEquals(1, a.args.count { it.startsWith("demo://") })
    }

    @Test
    fun `a deep link can be scoped to one package`() {
        val a = DeviceActions.deepLink(serial, "demo://x", "com.example.driver")
        assertTrue(a.args.containsAll(listOf("-p", "com.example.driver")))
    }

    /** Force-stop clears saved state; `am kill` does not, which is what tests rememberSaveable. */
    @Test
    fun `process death is distinct from force-stop`() {
        assertTrue(DeviceActions.simulateProcessDeath(serial, "com.x").args.contains("kill"))
        assertTrue(DeviceActions.forceStop(serial, "com.x").args.contains("force-stop"))
    }

    @Test
    fun `screenshot streams binary rather than writing to the device`() {
        // exec-out avoids a temp file on the device — and avoids CRLF mangling of the PNG.
        assertTrue(DeviceActions.screenshot(serial).args.contains("exec-out"))
    }

    @Test
    fun `logcat capture is bounded and dumps`() {
        val args = DeviceActions.logcat(serial, pid = 4521, maxLines = 300).args
        assertTrue(args.contains("-d"))
        assertTrue(args.contains("--pid=4521"))
        assertTrue(args.contains("300"))
    }

    @Test
    fun `state probes cover every setting the recipes touch`() {
        val probed = DeviceActions.stateProbes(serial).map { it.first }.toSet()
        for (recipe in DeviceRecipe.entries) {
            for (setting in DeviceRecipes.touchedSettings(recipe)) {
                assertTrue("no probe for '$setting' (needed by $recipe)", setting in probed)
            }
        }
    }
}
