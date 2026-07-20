package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOutputParsersTest {

    @Test
    fun `a real adb devices -l listing parses`() {
        val out = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R5CT10ABCDE            device product:a54xnaxx model:SM_A546B device:a54x transport_id:3
        """.trimIndent()

        val devices = AdbOutputParsers.parseDevices(out)
        assertEquals(2, devices.size)

        val emu = devices[0]
        assertEquals("emulator-5554", emu.serial)
        assertEquals(DeviceState.ONLINE, emu.state)
        assertEquals("sdk_gphone64_arm64", emu.model)
        assertEquals("emu64a", emu.device)
        assertEquals("1", emu.transportId)
        assertTrue(emu.isEmulator)
        assertEquals("sdk gphone64 arm64", emu.displayName)

        val phone = devices[1]
        assertFalse(phone.isEmulator)
        assertEquals("SM A546B", phone.displayName)
    }

    @Test
    fun `the short listing form parses without a tail`() {
        val devices = AdbOutputParsers.parseDevices("List of devices attached\nemulator-5554\tdevice\n")
        assertEquals(1, devices.size)
        assertEquals(DeviceState.ONLINE, devices[0].state)
        assertNull(devices[0].model)
        assertEquals("emulator-5554", devices[0].displayName) // falls back to the serial, never blank
    }

    @Test
    fun `an empty listing is empty, not an error`() {
        assertTrue(AdbOutputParsers.parseDevices("List of devices attached\n\n").isEmpty())
        assertTrue(AdbOutputParsers.parseDevices("").isEmpty())
    }

    @Test
    fun `daemon startup noise is skipped`() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device
        """.trimIndent()
        assertEquals(1, AdbOutputParsers.parseDevices(out).size)
    }

    /** Each non-ready state has a different fix, so they must not collapse into one another. */
    @Test
    fun `every device state is distinguished`() {
        val out = """
            List of devices attached
            A            device
            B            offline
            C            unauthorized
            D            bootloader
            E            recovery
            F            connecting
            G            no permissions; see [http://developer.android.com/tools/device.html]
        """.trimIndent()
        val byState = AdbOutputParsers.parseDevices(out).associate { it.serial to it.state }
        assertEquals(DeviceState.ONLINE, byState["A"])
        assertEquals(DeviceState.OFFLINE, byState["B"])
        assertEquals(DeviceState.UNAUTHORIZED, byState["C"])
        assertEquals(DeviceState.BOOTLOADER, byState["D"])
        assertEquals(DeviceState.RECOVERY, byState["E"])
        assertEquals(DeviceState.CONNECTING, byState["F"])
        assertEquals(DeviceState.NO_PERMISSIONS, byState["G"])
    }

    @Test
    fun `only a ready device is usable`() {
        assertTrue(DeviceState.ONLINE.isUsable)
        assertFalse(DeviceState.OFFLINE.isUsable)
        assertFalse(DeviceState.UNAUTHORIZED.isUsable)
        assertFalse(DeviceState.UNKNOWN.isUsable)
    }

    /** An unrecognised line must cost that line, not the listing. */
    @Test
    fun `unrecognised lines are skipped, not fatal`() {
        val out = """
            List of devices attached
            adb server version (41) doesn't match this client (39); killing...
            emulator-5554          device
            some totally unexpected future line
        """.trimIndent()
        val devices = AdbOutputParsers.parseDevices(out)
        assertEquals(1, devices.size)
        assertEquals("emulator-5554", devices[0].serial)
    }

    @Test
    fun `a network device serial parses`() {
        val devices = AdbOutputParsers.parseDevices("List of devices attached\n192.168.1.5:5555   device\n")
        assertEquals("192.168.1.5:5555", devices[0].serial)
        assertFalse(devices[0].isEmulator)
    }

    // ---- AVDs ----

    @Test
    fun `avd names parse`() {
        val out = """
            Medium_Phone
            Small_Phone_24
            Twin_Phone_2
            Wear_OS_Large_Round
        """.trimIndent()
        assertEquals(
            listOf("Medium_Phone", "Small_Phone_24", "Twin_Phone_2", "Wear_OS_Large_Round"),
            AdbOutputParsers.parseAvdNames(out),
        )
    }

    @Test
    fun `emulator diagnostics are not offered as bootable devices`() {
        val out = """
            INFO    | Storing crashdata in: /tmp/foo, detection is enabled for process: 1234
            Medium_Phone
            PANIC: Cannot find AVD system path.
        """.trimIndent()
        assertEquals(listOf("Medium_Phone"), AdbOutputParsers.parseAvdNames(out))
    }

    @Test
    fun `duplicate avd names collapse`() {
        assertEquals(listOf("Pixel_8"), AdbOutputParsers.parseAvdNames("Pixel_8\nPixel_8\n"))
    }

    // ---- version ----

    @Test
    fun `adb version parses`() {
        val out = """
            Android Debug Bridge version 1.0.41
            Version 35.0.2-12147458
        """.trimIndent()
        assertEquals("1.0.41", AdbOutputParsers.parseAdbVersion(out))
    }

    @Test
    fun `an unrecognised version banner falls back to the first line`() {
        assertEquals("something else entirely", AdbOutputParsers.parseAdbVersion("something else entirely"))
        assertNull(AdbOutputParsers.parseAdbVersion(""))
        assertNull(AdbOutputParsers.parseAdbVersion("   \n  "))
    }
}
