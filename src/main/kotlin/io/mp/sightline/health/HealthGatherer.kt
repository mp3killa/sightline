package io.mp.sightline.health

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.mp.sightline.android.AdbOutputParsers
import io.mp.sightline.ide.IdeServer
import io.mp.sightline.ide.android.AndroidEnvironment
import io.mp.sightline.ide.android.AndroidStudioFactProvider
import io.mp.sightline.process.ClaudePathResolver
import io.mp.sightline.settings.ClaudeSettings
import java.io.File

/**
 * Collects the live facts a preflight needs into a platform-free [HealthInputs], which [HealthChecker]
 * then judges. This is the only side-effecting, slow part — notably it shells out for `claude --version` —
 * so callers must run [gather] off the EDT. Everything it can't determine is left null, so the checker
 * reports it honestly as UNKNOWN rather than guessing.
 *
 * [diagnosticsAvailable]/[diagnosticsReason] and the session facts are supplied by the panel (which owns
 * the session and can query diagnostics), keeping this class free of those couplings.
 */
class HealthGatherer(private val project: Project) {

    data class SessionFacts(
        val running: Boolean,
        val sawSession: Boolean,
        val activityEventCount: Int,
        val diagnosticsAvailable: Boolean?,
        val diagnosticsReason: String?,
    )

    fun gather(session: SessionFacts): HealthInputs {
        val settings = ClaudeSettings.getInstance().state
        val configured = (settings.claudeCommand ?: "claude")
        // A health check is the moment a user runs *because* something is wrong — often right after
        // installing the CLI. Re-probe rather than reporting a cached miss they can no longer clear
        // without restarting the IDE.
        ClaudePathResolver.invalidate()
        val resolved = runCatching { ClaudePathResolver.resolve(configured) }.getOrNull()
        val (path, source) = classifyPath(configured, resolved)
        val version = path?.let { probeVersion(it) }

        val server = runCatching { project.getService(IdeServer::class.java) }.getOrNull()
        val model = (settings.model ?: "")
        val mode = (settings.permissionMode ?: "auto")

        return HealthInputs(
            claudePath = path,
            claudePathSource = source,
            claudeVersion = version,
            sessionRunning = session.running,
            sawSession = session.sawSession,
            ideIntegrationEnabled = settings.ideIntegration,
            ideServerPort = server?.port?.takeIf { it > 0 },
            diagnosticsAvailable = session.diagnosticsAvailable,
            diagnosticsReason = session.diagnosticsReason,
            permissionMode = mode,
            permissionModeFellBack = mode == "auto" && model.equals("haiku", ignoreCase = true),
            ideDescription = describeIde(),
            model = model,
            activityEventCount = session.activityEventCount,
        ).let { withAndroid(it, settings.androidFeatures) }
    }

    /**
     * Fold in the Android rows. Everything here shells out, so it inherits [gather]'s off-EDT contract;
     * it is skipped wholesale for a non-Android project, which is also the cheap path.
     */
    private fun withAndroid(base: HealthInputs, featuresEnabled: Boolean): HealthInputs {
        val env = runCatching { AndroidEnvironment.getInstance(project) }.getOrNull()
        val isAndroid = env?.let { runCatching { it.looksLikeAndroidProject() }.getOrDefault(false) } ?: false
        if (!isAndroid) return base.copy(androidProject = false)
        if (!featuresEnabled) return base.copy(androidProject = true, androidFeaturesEnabled = false)

        val sdk = runCatching { env?.sdk(refresh = true) }.getOrNull()
        val adbVersion = sdk?.adb?.let {
            runCatching { env?.adb("--version", timeoutMs = 4000) }.getOrNull()
                ?.takeIf { r -> r.ok }?.let { r -> AdbOutputParsers.parseAdbVersion(r.stdout) }
        }
        // Null (couldn't ask) and empty (nothing plugged in) mean different things to the checker, so
        // a failed or timed-out probe must stay null rather than collapse to an empty list.
        val devices = sdk?.adb?.let {
            runCatching { env?.adb("devices", "-l", timeoutMs = 6000) }.getOrNull()
                ?.takeIf { r -> r.ok }
                ?.let { r ->
                    AdbOutputParsers.parseDevices(r.stdout).map { d ->
                        AndroidDeviceSummary(d.displayName, d.state.label, d.state.isUsable)
                    }
                }
        }

        return base.copy(
            androidProject = true,
            androidFeaturesEnabled = true,
            androidSdkPath = sdk?.root,
            androidSdkSource = sdk?.source,
            adbPath = sdk?.adb,
            adbVersion = adbVersion,
            androidDevices = devices,
            studioModelAvailable = runCatching { AndroidStudioFactProvider.isAvailable() }.getOrDefault(false),
        )
    }

    /** Names how the binary was located, purely for the report's detail line. Returns null path if absent. */
    private fun classifyPath(configured: String, resolved: String?): Pair<String?, String?> {
        if (resolved.isNullOrBlank()) return null to null
        val exists = runCatching { File(resolved).canExecute() }.getOrDefault(false)
        if (!exists && resolved == "claude") return null to null // resolver's "give up" sentinel
        val source = when {
            configured.trim().isNotEmpty() && configured.trim() != "claude" -> "configured"
            resolved.contains("/homebrew/") || resolved.contains("/usr/local/") -> "common install dir"
            else -> "on PATH"
        }
        return resolved to source
    }

    /** `claude --version`, bounded so a hung CLI can't wedge the dialog. Off-EDT by contract. */
    private fun probeVersion(path: String): String? = try {
        val out = ExecUtil.execAndGetOutput(GeneralCommandLine(path, "--version"), 4000)
        out.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        thisLogger().info("health: claude --version failed", e)
        null
    }

    private fun describeIde(): String? = runCatching {
        val info = ApplicationInfo.getInstance()
        "${info.versionName} ${info.fullVersion} / ${info.build.baselineVersion}"
    }.getOrNull()
}
