package io.mp.sightline.health

/**
 * The facts a preflight needs, gathered from the live IDE by `ui/HealthDialog` and turned into a
 * [HealthReport] by the pure [HealthChecker.evaluate]. Splitting the gathering (platform, side-effecting,
 * slow) from the judging (pure, fast, exhaustively testable) means the tricky part — deciding OK vs WARN
 * vs FAIL vs UNKNOWN, and writing an honest hint — is unit-tested without a running IDE.
 *
 * Every nullable field encodes "not determinable": null must map to [HealthStatus.UNKNOWN], never to a
 * cheerful default. See [HealthModel]'s guiding rule.
 */
data class HealthInputs(
    /** Resolved absolute path to the `claude` binary, or null if it could not be found. */
    val claudePath: String?,
    /** How the path was found, for the detail line (e.g. "on PATH", "common install dir", "configured"). */
    val claudePathSource: String? = null,
    /** `claude --version` stdout, trimmed; null if the probe didn't run or failed. */
    val claudeVersion: String? = null,
    /** True if a `claude` process is currently live in this project. */
    val sessionRunning: Boolean = false,
    /** True once we've seen a `session_id` — i.e. the CLI actually authenticated and responded. */
    val sawSession: Boolean = false,

    /** Whether the IDE-integration feature is switched on in settings. */
    val ideIntegrationEnabled: Boolean = true,
    /** The IDE MCP WebSocket server's bound port, or null/-1 if not listening. */
    val ideServerPort: Int? = null,

    /** Whether diagnostics are available right now (false during indexing), or null if unknown. */
    val diagnosticsAvailable: Boolean? = null,
    /** Human reason diagnostics are unavailable (e.g. "indexing"), if known. */
    val diagnosticsReason: String? = null,

    /** Effective permission mode, and whether it silently fell back (auto on a non-Sonnet/Opus model). */
    val permissionMode: String = "auto",
    val permissionModeFellBack: Boolean = false,

    /** The IDE product + build (e.g. "Android Studio 2026.1 / 261"), for the report's context. */
    val ideDescription: String? = null,
    /** Current model, blank = CLI default. */
    val model: String = "",

    /** Observed activity events this session — 0 is fine (nothing has run yet), just reported as context. */
    val activityEventCount: Int = 0,

    // ---- Android (docs/ANDROID.md). All skipped entirely unless [androidProject] — a Kotlin backend
    // project reporting "Android SDK: FAIL" would be noise dressed up as a problem.
    /** Whether this project looks like an Android project at all. */
    val androidProject: Boolean = false,
    /** Whether the Android features are switched on in settings. */
    val androidFeaturesEnabled: Boolean = true,
    /** Resolved SDK root, or null if none was found. */
    val androidSdkPath: String? = null,
    /** How the SDK was located ("ANDROID_HOME", "local.properties", "default location", …). */
    val androidSdkSource: String? = null,
    /** Resolved `adb`, or null when the SDK exists but platform-tools doesn't. */
    val adbPath: String? = null,
    /** `adb --version`, or null if the probe didn't run. */
    val adbVersion: String? = null,
    /** Devices adb reports, or null if the probe couldn't run — null is not "zero devices". */
    val androidDevices: List<AndroidDeviceSummary>? = null,
    /** Whether Android Studio's model layer (the optional dependency) is loaded. */
    val studioModelAvailable: Boolean = false,
)

/** Just enough of a device for the Health row; the full model lives in `android/AdbOutputParsers`. */
data class AndroidDeviceSummary(val name: String, val stateLabel: String, val usable: Boolean)

/**
 * Pure evaluator: [HealthInputs] → [HealthReport]. No IO, no platform. Each check states plainly what it
 * verified, and anything not OK carries a concrete next step.
 */
object HealthChecker {

    fun evaluate(i: HealthInputs): HealthReport = HealthReport(
        listOf(
            claudeBinary(i),
            claudeVersion(i),
            session(i),
            ideBridge(i),
            diagnostics(i),
            permission(i),
            activity(i),
        ) + androidChecks(i),
    )

    /**
     * The Android rows, or nothing at all. Omitting them outside an Android project is the point: a
     * report is only useful if every line on it could plausibly be the user's problem.
     */
    private fun androidChecks(i: HealthInputs): List<HealthCheck> {
        if (!i.androidProject) return emptyList()
        if (!i.androidFeaturesEnabled) return listOf(
            HealthCheck(
                "android", "Android features", HealthStatus.WARN,
                "Turned off in settings — no build variant, device or logcat context is being gathered.",
                "Enable Android features in Settings → Sightline.",
            ),
        )
        return listOf(androidSdk(i), androidDevices(i), androidStudioModel(i))
    }

    private fun androidSdk(i: HealthInputs): HealthCheck = when {
        i.androidSdkPath == null -> HealthCheck(
            "android.sdk", "Android SDK", HealthStatus.FAIL,
            "Not found. Looked at ANDROID_HOME, ANDROID_SDK_ROOT, local.properties and the default location.",
            "Set the SDK path in Settings → Sightline, or set ANDROID_HOME before launching the IDE.",
        )
        i.adbPath == null -> HealthCheck(
            "android.sdk", "Android SDK", HealthStatus.WARN,
            "SDK at ${i.androidSdkPath}" + (i.androidSdkSource?.let { " ($it)" } ?: "") +
                ", but `platform-tools/adb` is missing, so no device action can run.",
            "Install Platform-Tools from the SDK Manager.",
        )
        else -> HealthCheck(
            "android.sdk", "Android SDK", HealthStatus.OK,
            "SDK at ${i.androidSdkPath}" + (i.androidSdkSource?.let { " ($it)" } ?: "") +
                (i.adbVersion?.let { " · adb $it" } ?: "") + ".",
        )
    }

    /**
     * Null devices and empty devices are deliberately different outcomes. "No device connected" is a
     * normal state with an obvious fix; "we couldn't ask" means adb itself is broken, and telling the
     * user to plug in a phone would send them the wrong way entirely.
     */
    private fun androidDevices(i: HealthInputs): HealthCheck {
        val devices = i.androidDevices
        return when {
            i.adbPath == null -> HealthCheck(
                "android.devices", "Devices", HealthStatus.UNKNOWN,
                "Not checked — adb wasn't found.",
                "Resolve the Android SDK first.",
            )
            devices == null -> HealthCheck(
                "android.devices", "Devices", HealthStatus.UNKNOWN,
                "adb was found but didn't answer, so the device list is unknown.",
                "Run `adb devices` in a terminal — if it hangs, `adb kill-server` and try again.",
            )
            devices.isEmpty() -> HealthCheck(
                "android.devices", "Devices", HealthStatus.WARN,
                "No device or emulator connected.",
                "Start an emulator or connect a device — device actions and logcat need one.",
            )
            devices.none { it.usable } -> HealthCheck(
                "android.devices", "Devices", HealthStatus.WARN,
                devices.joinToString(", ") { "${it.name} (${it.stateLabel})" } + " — none ready.",
                if (devices.any { it.stateLabel.contains("authoris", true) })
                    "Accept the USB debugging prompt on the device."
                else "Reconnect the device, or `adb kill-server` and retry.",
            )
            else -> HealthCheck(
                "android.devices", "Devices", HealthStatus.OK,
                devices.joinToString(", ") { if (it.usable) it.name else "${it.name} (${it.stateLabel})" } + ".",
            )
        }
    }

    /**
     * Not having Android Studio's model is a normal, supported state, so this is never worse than a
     * WARN — it costs a qualifier on a chip, not a feature. See docs/ANDROID.md §1.1.
     */
    private fun androidStudioModel(i: HealthInputs): HealthCheck = when {
        i.studioModelAvailable -> HealthCheck(
            "android.model", "Build variant", HealthStatus.OK,
            "Android Studio's project model is available — the selected variant is read directly.",
        )
        else -> HealthCheck(
            "android.model", "Build variant", HealthStatus.WARN,
            "Android Studio's project model isn't available, so the variant is taken from the last " +
                "build output and may be stale.",
            "This is expected outside Android Studio. Inside it, a Gradle sync usually restores it.",
        )
    }

    private fun claudeBinary(i: HealthInputs): HealthCheck = when (val p = i.claudePath) {
        null -> HealthCheck(
            "cli", "Claude CLI", HealthStatus.FAIL,
            "Not found. GUI-launched IDEs often don't inherit your shell PATH.",
            "Install the Claude CLI, or set its full path in Settings → Sightline (the `claude` command field).",
        )
        else -> HealthCheck(
            "cli", "Claude CLI", HealthStatus.OK,
            "Found at $p" + (i.claudePathSource?.let { " ($it)" } ?: "") + ".",
        )
    }

    private fun claudeVersion(i: HealthInputs): HealthCheck = when {
        i.claudePath == null -> HealthCheck(
            "version", "CLI version", HealthStatus.UNKNOWN,
            "Not checked — the CLI wasn't found.",
            "Resolve the Claude CLI first.",
        )
        i.claudeVersion.isNullOrBlank() -> HealthCheck(
            "version", "CLI version", HealthStatus.UNKNOWN,
            "The binary was found but `claude --version` produced no output.",
            "Run `claude --version` in a terminal — if it hangs or errors, reinstall the CLI.",
        )
        else -> HealthCheck("version", "CLI version", HealthStatus.OK, i.claudeVersion)
    }

    private fun session(i: HealthInputs): HealthCheck = when {
        i.claudePath == null -> HealthCheck(
            "auth", "Authentication", HealthStatus.UNKNOWN,
            "Not checked — the CLI wasn't found.",
            "Resolve the Claude CLI first.",
        )
        i.sawSession -> HealthCheck(
            "auth", "Authentication", HealthStatus.OK,
            "The CLI authenticated and responded this session.",
        )
        i.sessionRunning -> HealthCheck(
            "auth", "Authentication", HealthStatus.UNKNOWN,
            "A CLI process is running but hasn't confirmed a session yet.",
            "Send a message; if it never responds, run `claude` once in a terminal to check you're logged in.",
        )
        else -> HealthCheck(
            "auth", "Authentication", HealthStatus.UNKNOWN,
            "No CLI session has run yet this launch, so login state is unverified.",
            "Send a message. If it fails, run `claude` in a terminal and complete login.",
        )
    }

    private fun ideBridge(i: HealthInputs): HealthCheck = when {
        !i.ideIntegrationEnabled -> HealthCheck(
            "bridge", "IDE integration", HealthStatus.WARN,
            "Turned off in settings — Claude can't read your selection, open files, or show diffs.",
            "Enable IDE integration in Settings → Sightline to get editor-aware tools.",
        )
        (i.ideServerPort ?: -1) <= 0 -> HealthCheck(
            "bridge", "IDE integration", HealthStatus.FAIL,
            "Enabled, but the IDE bridge server isn't listening.",
            "Restart the tool window; if it persists, check a firewall isn't blocking a loopback socket.",
        )
        else -> HealthCheck(
            "bridge", "IDE integration", HealthStatus.OK,
            "Bridge listening on a loopback port; editor-aware tools are available.",
        )
    }

    private fun diagnostics(i: HealthInputs): HealthCheck = when (i.diagnosticsAvailable) {
        true -> HealthCheck("diagnostics", "Code diagnostics", HealthStatus.OK, "Available.")
        false -> HealthCheck(
            "diagnostics", "Code diagnostics", HealthStatus.WARN,
            "Unavailable" + (i.diagnosticsReason?.let { " ($it)" } ?: "") + ".",
            if (i.diagnosticsReason?.contains("index", true) == true)
                "Wait for indexing to finish, then re-check." else "Re-check after the IDE finishes loading the project.",
        )
        null -> HealthCheck(
            "diagnostics", "Code diagnostics", HealthStatus.UNKNOWN,
            "Availability wasn't determined.",
        )
    }

    private fun permission(i: HealthInputs): HealthCheck = when {
        i.permissionModeFellBack -> HealthCheck(
            "permission", "Permission mode", HealthStatus.WARN,
            "Set to `auto`, but the current model doesn't support it, so it fell back to `default`.",
            "Switch to Sonnet or Opus for auto-approval, or pick a different mode in the composer.",
        )
        else -> HealthCheck(
            "permission", "Permission mode", HealthStatus.OK,
            "`${i.permissionMode}`" + (i.model.takeIf { it.isNotBlank() }?.let { " · model $it" } ?: " · default model") + ".",
        )
    }

    private fun activity(i: HealthInputs): HealthCheck = HealthCheck(
        "activity", "Activity map", HealthStatus.OK,
        if (i.activityEventCount == 0) "No activity yet this session."
        else "${i.activityEventCount} event${if (i.activityEventCount == 1) "" else "s"} observed this session.",
    )
}
