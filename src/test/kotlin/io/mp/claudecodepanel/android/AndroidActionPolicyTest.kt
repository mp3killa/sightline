package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidActionPolicyTest {

    private fun risk(cmd: String) = AndroidActionPolicy.classifyCommand(cmd).risk

    // ---- read-only ----

    @Test
    fun `observing the device is read-only`() {
        assertEquals(ActionRisk.READ_ONLY, risk("adb devices -l"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb logcat -d"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb shell getprop ro.build.version.sdk"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb shell dumpsys activity activities"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb shell screencap -p /sdcard/s.png"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb shell pm list packages"))
    }

    /** `pull` writes to the host, not the device — host-path safety is PathAccessPolicy's job. */
    @Test
    fun `pull is read-only from the device's point of view`() {
        assertEquals(ActionRisk.READ_ONLY, risk("adb pull /sdcard/log.txt ./log.txt"))
    }

    // ---- mutating ----

    @Test
    fun `changing state you can redo is merely mutating`() {
        assertEquals(ActionRisk.MUTATING, risk("adb install -r app-debug.apk"))
        assertEquals(ActionRisk.MUTATING, risk("adb push local.txt /sdcard/local.txt"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell am force-stop com.example"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell am start -n com.example/.MainActivity"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell settings put system font_scale 1.5"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell pm grant com.example android.permission.CAMERA"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell input tap 100 200"))
    }

    @Test
    fun `reading a setting is not the same as writing one`() {
        assertEquals(ActionRisk.READ_ONLY, risk("adb shell settings get system font_scale"))
        assertEquals(ActionRisk.MUTATING, risk("adb shell settings put system font_scale 1.5"))
    }

    // ---- destructive ----

    @Test
    fun `data loss is destructive`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb uninstall com.example"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell pm clear com.example"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell pm uninstall com.example"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell pm revoke com.example android.permission.CAMERA"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell rm -rf /sdcard/Download"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb emu kill"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb reboot bootloader"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb root"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb disable-verity"))
    }

    @Test
    fun `a destructive verdict names what is lost`() {
        val v = AndroidActionPolicy.classifyCommand("adb shell pm clear com.example.driver")
        assertTrue(v.needsConfirmation)
        assertTrue("summary should name the package: ${v.summary}", v.summary.contains("com.example.driver"))
        assertNotNull(v.loses)
        assertTrue(v.loses!!.contains("databases"))
    }

    @Test
    fun `a non-destructive verdict loses nothing`() {
        val v = AndroidActionPolicy.classifyCommand("adb devices")
        assertFalse(v.needsConfirmation)
        assertEquals(null, v.loses)
    }

    // ---- the bypass a naive gate would miss ----

    @Test
    fun `a chained command takes the worst risk of its segments`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb devices && adb uninstall com.example"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell pm clear com.example; adb devices"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb devices || adb shell rm -rf /sdcard/x"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb devices\nadb uninstall com.example"))
    }

    @Test
    fun `device-selection flags do not hide the sub-command`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb -s emulator-5554 shell pm clear com.example"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb -H 127.0.0.1 -P 5037 uninstall com.example"))
        assertEquals(ActionRisk.READ_ONLY, risk("adb -d devices"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("/usr/local/bin/adb -s R5CT10 shell pm clear com.example"))
    }

    @Test
    fun `an absolute path to adb is still adb`() {
        assertEquals(
            ActionRisk.DESTRUCTIVE,
            risk("/Users/me/Library/Android/sdk/platform-tools/adb uninstall com.example"),
        )
    }

    // ---- fail-safe: unknown is never assumed safe ----

    @Test
    fun `an unrecognised adb sub-command is confirmed, not assumed safe`() {
        val v = AndroidActionPolicy.classifyCommand("adb some-future-verb --wipe-everything")
        assertEquals(ActionRisk.DESTRUCTIVE, v.risk)
        assertTrue(v.loses!!.contains("unknown"))
    }

    @Test
    fun `an unrecognised shell one-liner is confirmed`() {
        // The easiest way to smuggle a wipe past a naive gate.
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell 'pm clear com.example'"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell sqlite3 /data/db 'DROP TABLE routes'"))
    }

    @Test
    fun `a bare adb with no sub-command is confirmed`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb"))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb -s emulator-5554"))
    }

    @Test
    fun `an interactive shell is confirmed`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk("adb shell"))
    }

    /** Non-adb commands go through the normal tool-approval path, not this gate. */
    @Test
    fun `a non-adb command is not this policy's business`() {
        assertEquals(ActionRisk.READ_ONLY, risk("./gradlew assembleDebug"))
        assertEquals(ActionRisk.READ_ONLY, risk("ls -la"))
    }

    /** A word starting with "adb" is not adb — the same anchoring OutputParsers.adbAction uses. */
    @Test
    fun `a lookalike executable is not treated as adb`() {
        assertEquals(ActionRisk.READ_ONLY, risk("adbfake uninstall com.example"))
    }

    @Test
    fun `an empty command is confirmed rather than crashing`() {
        assertEquals(ActionRisk.DESTRUCTIVE, risk(""))
        assertEquals(ActionRisk.DESTRUCTIVE, risk("   "))
    }

    // ---- the structured entry point M3's action cards will use ----

    @Test
    fun `classify works on argv directly`() {
        assertEquals(ActionRisk.DESTRUCTIVE, AndroidActionPolicy.classify(listOf("shell", "pm", "clear", "com.x")).risk)
        assertEquals(ActionRisk.READ_ONLY, AndroidActionPolicy.classify(listOf("devices", "-l")).risk)
        assertEquals(
            ActionRisk.DESTRUCTIVE,
            AndroidActionPolicy.classify(listOf("-s", "emulator-5554", "uninstall", "com.x")).risk,
        )
    }

    @Test
    fun `risk severity ordering is worst-wins`() {
        assertEquals(ActionRisk.DESTRUCTIVE, ActionRisk.READ_ONLY.worseOf(ActionRisk.DESTRUCTIVE))
        assertEquals(ActionRisk.DESTRUCTIVE, ActionRisk.DESTRUCTIVE.worseOf(ActionRisk.READ_ONLY))
        assertEquals(ActionRisk.MUTATING, ActionRisk.READ_ONLY.worseOf(ActionRisk.MUTATING))
    }
}
