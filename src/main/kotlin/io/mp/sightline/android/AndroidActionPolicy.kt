package io.mp.sightline.android

/**
 * How much a device action can cost you if it was the wrong one. Declaration order is severity order.
 */
enum class ActionRisk {
    /** Observes the device. Nothing on it changes. */
    READ_ONLY,

    /** Changes device or app state, but nothing the user can't get back by redoing their work. */
    MUTATING,

    /**
     * Irreversibly discards something: app data, an install, a granted permission, a running emulator,
     * or the device's security posture. Always confirmed, whatever the permission mode.
     */
    DESTRUCTIVE,
    ;

    fun worseOf(other: ActionRisk): ActionRisk = if (other.ordinal > ordinal) other else this
}

/** A risk verdict plus the sentence a confirmation card should show. */
data class ActionVerdict(
    val risk: ActionRisk,
    /** What the action does, in the user's terms — "Erases all app data for com.example". */
    val summary: String,
    /** What is unrecoverable, when that's the point. Null for anything below DESTRUCTIVE. */
    val loses: String? = null,
) {
    val needsConfirmation: Boolean get() = risk == ActionRisk.DESTRUCTIVE
}

/**
 * Platform-free gate on device actions — the adb sibling of [io.mp.sightline.ide.PathAccessPolicy],
 * and it inherits that class's core rule: **anything it cannot classify is not treated as safe.**
 *
 * The gate matters because the permission modes exist to let a user stop approving routine things. That
 * is right for `adb devices`; it is emphatically wrong for `pm clear`, which silently discards the
 * logged-in state, database and preferences of the app under test. So DESTRUCTIVE actions are confirmed
 * regardless of mode, through the same `ApprovalCoordinator` the tool-approval flow already uses.
 *
 * Chained commands are classified as the **worst** of their segments. Without that, `adb devices && adb
 * uninstall com.example` reads as a harmless device listing, which is exactly how a gate gets bypassed.
 *
 * This answers a different question from `activity/OutputParsers.adbAction`, which labels a command for
 * display ("Installing app"). This one decides whether to stop and ask.
 */
object AndroidActionPolicy {

    /** adb's own flags that consume a following value, so they must be skipped in pairs. */
    private val VALUE_FLAGS = setOf("-s", "-p", "-P", "-H", "-L", "--serial")

    fun classifyCommand(command: String): ActionVerdict =
        command.split("&&", "||", ";", "|", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { classifySegment(it) }
            .reduceOrNull { a, b -> if (b.risk.ordinal > a.risk.ordinal) b else a }
            ?: unknown("empty command")

    private fun classifySegment(segment: String): ActionVerdict {
        val tokens = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = tokens.firstOrNull() ?: return unknown("empty command")
        if (head != "adb" && head.substringAfterLast('/') != "adb") {
            // Not an adb call at all — not this policy's business; the normal tool-approval path applies.
            return ActionVerdict(ActionRisk.READ_ONLY, "Not a device command")
        }
        return classify(tokens.drop(1))
    }

    /** Classify the argv **after** the `adb` executable itself. */
    fun classify(rawArgs: List<String>): ActionVerdict {
        val args = stripDeviceFlags(rawArgs)
        val verb = args.firstOrNull()?.lowercase() ?: return unknown("`adb` with no sub-command")
        val rest = args.drop(1)

        return when (verb) {
            "devices", "get-state", "get-serialno", "version", "start-server", "help" ->
                ActionVerdict(ActionRisk.READ_ONLY, "Lists devices / queries adb")
            "logcat" -> ActionVerdict(ActionRisk.READ_ONLY, "Reads the device log")
            // Writes to the *host*, not the device. Host-path safety is PathAccessPolicy's job, not ours.
            "pull" -> ActionVerdict(ActionRisk.READ_ONLY, "Copies a file off the device")

            "install", "install-multiple", "install-multi-package" ->
                ActionVerdict(ActionRisk.MUTATING, "Installs an APK on the device")
            "push" -> ActionVerdict(ActionRisk.MUTATING, "Copies a file onto the device")
            "forward", "reverse", "connect", "disconnect" ->
                ActionVerdict(ActionRisk.MUTATING, "Changes adb's connection state")
            "wait-for-device" -> ActionVerdict(ActionRisk.READ_ONLY, "Waits for a device")

            "uninstall" -> destructive(
                "Uninstalls ${pkg(rest) ?: "an app"} from the device",
                "the app and all of its data — accounts, databases and preferences",
            )
            "emu" -> if (rest.firstOrNull()?.lowercase() == "kill")
                destructive("Shuts down the running emulator", "any unsaved emulator state")
            else ActionVerdict(ActionRisk.MUTATING, "Sends a command to the emulator console")
            "reboot" -> destructive(
                "Reboots the device" + (rest.firstOrNull()?.let { " into $it" } ?: ""),
                "everything currently running, including the debug session",
            )
            "root", "unroot", "remount", "disable-verity", "enable-verity" -> destructive(
                "Changes the device's security posture (`$verb`)",
                "the device's verified-boot state; on a physical device this can require a factory reset",
            )

            // `exec-out` runs a command exactly like `shell` but streams raw binary — it is how a
            // screenshot is captured without a pty mangling the PNG. Same command, so same classification.
            "shell", "exec-out" -> classifyShell(rest)

            else -> unknown("`adb $verb` isn't a command this gate recognises")
        }
    }

    private fun classifyShell(rest: List<String>): ActionVerdict {
        if (rest.isEmpty()) return unknown("an interactive `adb shell`")
        val joined = rest.joinToString(" ")
        val cmd = rest[0].lowercase()
        val sub = rest.getOrNull(1)?.lowercase()

        return when {
            cmd == "pm" && sub == "clear" -> destructive(
                "Erases all app data for ${pkg(rest.drop(2)) ?: "an app"}",
                "databases, preferences, cached files and any signed-in session",
            )
            cmd == "pm" && sub == "uninstall" -> destructive(
                "Uninstalls ${pkg(rest.drop(2)) ?: "an app"}",
                "the app and all of its data",
            )
            cmd == "pm" && sub == "revoke" -> destructive(
                "Revokes a runtime permission from ${pkg(rest.drop(2)) ?: "an app"}",
                "the granted permission; the app may reset related state when it next starts",
            )
            cmd == "pm" && sub == "grant" -> ActionVerdict(ActionRisk.MUTATING, "Grants a runtime permission")
            cmd == "pm" -> ActionVerdict(ActionRisk.READ_ONLY, "Queries the package manager")

            cmd == "rm" -> destructive(
                "Deletes files on the device (`rm`)",
                "the deleted files — there is no trash on a device",
            )
            cmd == "content" && sub == "delete" -> destructive(
                "Deletes rows through a content provider",
                "the deleted rows",
            )

            cmd == "am" && sub == "force-stop" ->
                ActionVerdict(ActionRisk.MUTATING, "Force-stops the app")
            cmd == "am" && (sub == "start" || sub == "start-activity") ->
                ActionVerdict(ActionRisk.MUTATING, "Launches an activity or deep link")
            cmd == "am" -> ActionVerdict(ActionRisk.MUTATING, "Sends an activity-manager command")

            cmd == "settings" && sub == "put" ->
                ActionVerdict(ActionRisk.MUTATING, "Changes a device setting")
            cmd == "settings" -> ActionVerdict(ActionRisk.READ_ONLY, "Reads a device setting")

            cmd == "input" -> ActionVerdict(ActionRisk.MUTATING, "Sends input events to the device")
            cmd == "wm" || cmd == "cmd" -> ActionVerdict(ActionRisk.MUTATING, "Changes device configuration")

            cmd == "getprop" || cmd == "dumpsys" || cmd == "screencap" || cmd == "uiautomator" ||
                cmd == "ps" || cmd == "cat" || cmd == "ls" || cmd == "df" || cmd == "date" ->
                ActionVerdict(ActionRisk.READ_ONLY, "Reads device state")

            // A shell one-liner we don't recognise. Deliberately not READ_ONLY — an unrecognised shell
            // command is the single easiest way to smuggle a wipe past this gate.
            else -> unknown("`adb shell ${joined.take(60)}` isn't a command this gate recognises")
        }
    }

    /**
     * Skip adb's own device-selection flags so `adb -s emulator-5554 shell pm clear x` classifies on
     * `shell`, not on `-s`. Everything after the first non-flag token is the sub-command's own argv and
     * is left untouched — its flags belong to it, not to adb.
     */
    private fun stripDeviceFlags(args: List<String>): List<String> {
        var i = 0
        while (i < args.size) {
            val t = args[i]
            when {
                t in VALUE_FLAGS -> i += 2
                t.startsWith("-") -> i++
                else -> return args.drop(i)
            }
        }
        return emptyList()
    }

    /** First non-flag token — the package name, for actions that name one. */
    private fun pkg(args: List<String>): String? = args.firstOrNull { !it.startsWith("-") }

    private fun destructive(summary: String, loses: String) =
        ActionVerdict(ActionRisk.DESTRUCTIVE, summary, loses)

    /**
     * An unclassifiable action. Treated as DESTRUCTIVE so it is confirmed, matching PathAccessPolicy's
     * "unresolvable → unsafe" rule: the cost of one extra prompt is a click, the cost of a missed wipe
     * is the user's afternoon.
     */
    private fun unknown(what: String) = ActionVerdict(
        ActionRisk.DESTRUCTIVE,
        "Unrecognised device command",
        "unknown — $what, so it is being confirmed rather than assumed safe",
    )
}
