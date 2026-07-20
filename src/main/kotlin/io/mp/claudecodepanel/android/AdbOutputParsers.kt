package io.mp.claudecodepanel.android

/**
 * What adb says about a device's readiness. Only [ONLINE] can actually run a command, and the others are
 * kept distinct rather than collapsed to "not ready" because each has a different fix: authorise the
 * prompt on the handset, reconnect the cable, wait for boot.
 */
enum class DeviceState(val label: String) {
    ONLINE("ready"),
    OFFLINE("offline"),
    UNAUTHORIZED("unauthorised"),
    BOOTLOADER("in bootloader"),
    RECOVERY("in recovery"),
    CONNECTING("connecting"),
    NO_PERMISSIONS("no USB permission"),
    UNKNOWN("unknown state"),
    ;

    val isUsable: Boolean get() = this == ONLINE
}

data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
    val transportId: String? = null,
) {
    /** adb has no "is this an emulator" field; the serial prefix is the documented convention. */
    val isEmulator: Boolean get() = serial.startsWith("emulator-")

    /** `Pixel 8` where adb told us, else the serial — never a blank chip. */
    val displayName: String
        get() = model?.replace('_', ' ')?.takeIf { it.isNotBlank() } ?: serial
}

/**
 * Parsers for adb's text output. Pure, so they are unit-tested against captured real output rather than
 * against a live device.
 *
 * adb's stdout is a stable-in-practice but entirely undocumented contract, so every parser here is
 * written to **skip what it doesn't recognise** instead of failing or guessing. A daemon-startup banner,
 * a warning line, or a field added in a future platform-tools release must cost us that one line, not
 * the whole listing.
 */
object AdbOutputParsers {

    private val DAEMON_NOISE = listOf(
        "* daemon not running", "* daemon started successfully", "adb server version",
        "* failed to start daemon", "list of devices attached",
    )

    /**
     * Parse `adb devices -l`. Works equally on the short `adb devices` form, which simply has no
     * `key:value` tail.
     */
    fun parseDevices(output: String): List<AdbDevice> {
        val out = mutableListOf<AdbDevice>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (DAEMON_NOISE.any { line.startsWith(it, ignoreCase = true) }) continue

            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 2) continue
            val serial = tokens[0]
            // A serial can't contain a colon-delimited key/value; that shape means we're mid-tail and
            // this isn't a device row at all.
            if (serial.contains('=')) continue

            val state = parseState(tokens[1]) ?: continue
            val tail = tokens.drop(2).mapNotNull { t ->
                val i = t.indexOf(':')
                if (i <= 0) null else t.substring(0, i) to t.substring(i + 1)
            }.toMap()

            out += AdbDevice(
                serial = serial,
                state = state,
                model = tail["model"],
                product = tail["product"],
                device = tail["device"],
                transportId = tail["transport_id"],
            )
        }
        return out
    }

    /** Null for a token that isn't a state at all, which is how non-device lines get skipped. */
    private fun parseState(token: String): DeviceState? = when (token.lowercase()) {
        "device" -> DeviceState.ONLINE
        "offline" -> DeviceState.OFFLINE
        "unauthorized" -> DeviceState.UNAUTHORIZED
        "bootloader", "fastboot" -> DeviceState.BOOTLOADER
        "recovery", "sideload" -> DeviceState.RECOVERY
        "connecting", "authorizing" -> DeviceState.CONNECTING
        "no" -> DeviceState.NO_PERMISSIONS // "no permissions; see [http://...]"
        "host", "unknown" -> DeviceState.UNKNOWN
        else -> null
    }

    /**
     * AVD names from `emulator -list-avds`. The emulator prints diagnostics to stdout as well, so
     * anything that can't be an AVD name — AVD names are restricted to word characters, dot and dash —
     * is dropped rather than offered to the user as a bootable device.
     */
    fun parseAvdNames(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && AVD_NAME.matches(it) }
            .distinct()
            .toList()

    private val AVD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** `adb --version`'s first line, trimmed to the version itself: `35.0.2`. Null if unrecognised. */
    fun parseAdbVersion(output: String): String? {
        val first = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return Regex("""Android Debug Bridge version ([0-9][0-9.]*)""").find(first)?.groupValues?.get(1)
            ?: first.takeIf { it.isNotBlank() && it.length < 80 }
    }
}
