package io.mp.claudecodepanel.android

/** A device action, ready to run: what it is, what it costs, and the exact argv. */
data class DeviceAction(
    val id: String,
    val label: String,
    /** adb argv **after** the executable, with the serial already applied. */
    val args: List<String>,
    val verdict: ActionVerdict,
) {
    val needsConfirmation: Boolean get() = verdict.needsConfirmation
}

/**
 * Builds the adb argv for each device action, and classifies every one through [AndroidActionPolicy].
 *
 * Nothing here executes. The split matters: argv construction is pure and exhaustively testable, and it
 * means **every** action is risk-classified by construction rather than by a caller remembering to ask.
 * The gate cannot be bypassed by adding an action, because building one runs it through the policy.
 */
object DeviceActions {

    private fun serialArgs(serial: String?): List<String> =
        if (serial.isNullOrBlank()) emptyList() else listOf("-s", serial)

    private fun action(id: String, label: String, serial: String?, vararg rest: String): DeviceAction {
        val args = serialArgs(serial) + rest.toList()
        // Classify the argv we will actually run, so an action can't be built that skips the gate.
        return DeviceAction(id, label, args, AndroidActionPolicy.classify(args))
    }

    // ---- app lifecycle ----

    fun install(serial: String?, apkPath: String) =
        action("install", "Install APK", serial, "install", "-r", apkPath)

    fun uninstall(serial: String?, applicationId: String) =
        action("uninstall", "Uninstall app", serial, "uninstall", applicationId)

    fun launch(serial: String?, applicationId: String) =
        action("launch", "Launch app", serial, "shell", "monkey", "-p", applicationId, "-c", "android.intent.category.LAUNCHER", "1")

    fun launchActivity(serial: String?, applicationId: String, activity: String) =
        action("launchActivity", "Open activity", serial, "shell", "am", "start", "-n", "$applicationId/$activity")

    fun forceStop(serial: String?, applicationId: String) =
        action("forceStop", "Force-stop app", serial, "shell", "am", "force-stop", applicationId)

    fun clearData(serial: String?, applicationId: String) =
        action("clearData", "Clear app data", serial, "shell", "pm", "clear", applicationId)

    /**
     * Deep link. The URI is passed as one argv element rather than interpolated into a shell string, so
     * a query string with `&` cannot become a second command.
     */
    fun deepLink(serial: String?, uri: String, applicationId: String? = null) = action(
        "deepLink", "Open deep link", serial,
        *buildList {
            addAll(listOf("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", uri))
            applicationId?.let { addAll(listOf("-p", it)) }
        }.toTypedArray(),
    )

    fun grantPermission(serial: String?, applicationId: String, permission: String) =
        action("grant", "Grant permission", serial, "shell", "pm", "grant", applicationId, permission)

    fun revokePermission(serial: String?, applicationId: String, permission: String) =
        action("revoke", "Revoke permission", serial, "shell", "pm", "revoke", applicationId, permission)

    /**
     * Simulate process death: stop the app while keeping its saved state, which is what actually
     * exercises `onSaveInstanceState`/`rememberSaveable`. Distinct from force-stop, which does not.
     */
    fun simulateProcessDeath(serial: String?, applicationId: String) =
        action("processDeath", "Kill the app process (keeps saved state)", serial, "shell", "am", "kill", applicationId)

    // ---- observation ----

    fun listDevices() = action("devices", "List devices", null, "devices", "-l")

    fun screenshot(serial: String?) =
        action("screenshot", "Capture screenshot", serial, "exec-out", "screencap", "-p")

    fun currentActivity(serial: String?) =
        action("currentActivity", "Read current activity", serial, "shell", "dumpsys", "activity", "activities")

    fun isRunning(serial: String?, applicationId: String) =
        action("isRunning", "Check if the app is running", serial, "shell", "pidof", applicationId)

    fun logcat(serial: String?, pid: Int? = null, minLevel: LogLevel = LogLevel.VERBOSE, maxLines: Int = 2000) =
        action("logcat", "Capture logcat", serial, *LogcatParser.captureArgs(pid, minLevel, maxLines).toTypedArray())

    // ---- device configuration (all reversible; see DeviceRecipes) ----

    fun setFontScale(serial: String?, scale: Float) =
        action("fontScale", "Set font scale", serial, "shell", "settings", "put", "system", "font_scale", scale.toString())

    fun setDarkMode(serial: String?, on: Boolean) =
        action("darkMode", if (on) "Enable dark mode" else "Disable dark mode", serial,
            "shell", "cmd", "uimode", "night", if (on) "yes" else "no")

    fun setLocale(serial: String?, localeTag: String) =
        action("locale", "Set locale", serial, "shell", "am", "broadcast", "-a", "com.android.intent.action.SET_LOCALE",
            "--es", "com.android.intent.extra.LOCALE", localeTag)

    fun setRotation(serial: String?, landscape: Boolean) =
        action("rotate", if (landscape) "Rotate to landscape" else "Rotate to portrait", serial,
            "shell", "settings", "put", "system", "user_rotation", if (landscape) "1" else "0")

    fun setAutoRotate(serial: String?, on: Boolean) =
        action("autoRotate", "Set auto-rotate", serial,
            "shell", "settings", "put", "system", "accelerometer_rotation", if (on) "1" else "0")

    fun setDisplayDensity(serial: String?, dpi: Int?) =
        if (dpi == null) action("density", "Reset display density", serial, "shell", "wm", "density", "reset")
        else action("density", "Set display density", serial, "shell", "wm", "density", dpi.toString())

    fun setTalkBack(serial: String?, on: Boolean) = action(
        "talkback", if (on) "Enable TalkBack" else "Disable TalkBack", serial,
        "shell", "settings", "put", "secure", "enabled_accessibility_services",
        if (on) "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService" else "",
    )

    // ---- reading current values, so a change can be reverted ----

    fun readSetting(serial: String?, namespace: String, key: String) =
        action("readSetting", "Read $key", serial, "shell", "settings", "get", namespace, key)

    fun readNightMode(serial: String?) =
        action("readNightMode", "Read night mode", serial, "shell", "cmd", "uimode", "night")

    /** Every action whose effect [DeviceRecipes] needs to capture before changing it. */
    fun stateProbes(serial: String?): List<Pair<String, DeviceAction>> = listOf(
        "font_scale" to readSetting(serial, "system", "font_scale"),
        "user_rotation" to readSetting(serial, "system", "user_rotation"),
        "accelerometer_rotation" to readSetting(serial, "system", "accelerometer_rotation"),
        "night_mode" to readNightMode(serial),
        "enabled_accessibility_services" to readSetting(serial, "secure", "enabled_accessibility_services"),
    )
}
