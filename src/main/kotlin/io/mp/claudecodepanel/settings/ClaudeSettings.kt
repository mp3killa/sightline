package io.mp.claudecodepanel.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ClaudeCodePanelSettings", storages = [Storage("claudeCodePanel.xml")])
class ClaudeSettings : SimplePersistentStateComponent<ClaudeSettings.State>(State()) {

    class State : BaseState() {
        /** The Claude Code CLI command. "claude" triggers auto-detection of the binary. */
        var claudeCommand by string("claude")

        /** Model for new sessions: "" = CLI default, otherwise an alias ("opus"/"sonnet"/"haiku") or a full id. */
        var model by string("")

        /** Permission mode: default | acceptEdits | plan | auto | bypassPermissions. */
        var permissionMode by string("auto")

        /** Stream token-level deltas for a live "typing" effect. */
        var includePartialMessages by property(true)

        /** Show thinking blocks and tool-call cards in the transcript (off = compact/summary view). */
        var showDetails by property(false)

        /** Prompt for approval before each tool that needs it (uses the CLI control protocol). */
        var interactiveApproval by property(true)

        /** Run the `ide` MCP server so Claude sees the editor selection and opens diffs natively. */
        var ideIntegration by property(true)

        /** Show the live Agent Activity Map (graph of observable tool activity) while Claude works. */
        var showActivityMap by property(true)

        /** Layout of the transcript vs. the activity map: "chat" | "split" | "map". */
        var activityViewMode by string("split")

        /** Static activity-map layout with no pulsing/animation (lower CPU). */
        var activityReduceMotion by property(false)

        /** Max nodes rendered in the activity map at once. */
        var activityMaxNodes by property(200)

        /** Max nodes retained in a session before the oldest non-pinned ones are evicted. */
        var activityMaxRetained by property(500)

        /** Whether the activity-log dock is expanded (vs. the compact collapsed summary). */
        var activityTimelineExpanded by property(false)

        /** Whether the one-time "observable activity only" activity-map disclaimer was dismissed. */
        var activityAboutDismissed by property(false)

        /**
         * Android SDK location. Blank = auto-detect (ANDROID_HOME, ANDROID_SDK_ROOT, `local.properties`,
         * then the per-OS default). Set this only when auto-detection picks the wrong one of several SDKs.
         */
        var androidSdkPath by string("")

        /**
         * Master switch for the Android control-centre features (context strip, device actions, logcat).
         * On by default, but every one of them is additionally gated on the project actually looking like
         * an Android project, so a non-Android project sees nothing either way.
         */
        var androidFeatures by property(true)

        /**
         * Persist a small Android cache under `.sightline/` in the project — flaky-test history,
         * screenshot baselines, artifact sizes, saved workflows.
         *
         * **Off by default, deliberately.** This is the one carve-out from the standing decision that
         * nothing but settings is written to disk (docs/ANDROID.md §1.3), and it stays opt-in: only
         * workspace-relative paths are ever stored, never source contents, prompts or reasoning.
         */
        var androidPersistCache by property(false)

        /** Retention cap for each `.sightline/` store; oldest entries are dropped first. */
        var androidCacheMaxEntries by property(200)

        /** Advanced: extra CLI args appended to every invocation (space-separated). */
        var extraArgs by string("")
    }

    companion object {
        fun getInstance(): ClaudeSettings = service()
    }
}
