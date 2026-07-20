package io.mp.claudecodepanel.health

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
)

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
        ),
    )

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
