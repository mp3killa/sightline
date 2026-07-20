package io.mp.claudecodepanel.ui.state

/** The primary workspace the tool window shows. SPLIT is a secondary (wide-panel) layout. */
enum class WorkspaceMode { CHAT, ACTIVITY, SPLIT }

/**
 * Pure mapping between [WorkspaceMode] and the persisted settings pair
 * (`showActivityMap` + `activityViewMode`), kept separate from the settings service so the
 * round-trip is unit-testable. `activityViewMode` retains the historical `chat|split|map` strings.
 */
object WorkspaceModes {

    fun fromSettings(showActivityMap: Boolean, activityViewMode: String?): WorkspaceMode {
        if (!showActivityMap) return WorkspaceMode.CHAT
        return when (activityViewMode) {
            "chat" -> WorkspaceMode.CHAT
            "split" -> WorkspaceMode.SPLIT
            else -> WorkspaceMode.ACTIVITY // "map" (and any legacy value)
        }
    }

    fun toShowActivityMap(mode: WorkspaceMode): Boolean = mode != WorkspaceMode.CHAT

    fun toViewMode(mode: WorkspaceMode): String = when (mode) {
        WorkspaceMode.CHAT -> "chat"
        WorkspaceMode.SPLIT -> "split"
        WorkspaceMode.ACTIVITY -> "map"
    }

    /**
     * The mode actually shown, given what the user *prefers* (the persisted choice) and how much
     * room there is. SPLIT needs a genuinely wide panel — see [ResponsiveLayout.allowSplitDefault];
     * below that the two panes fight for space and the conversation, which is the primary surface,
     * loses. So a too-narrow SPLIT falls back to **CHAT**, never to ACTIVITY: dropping the user into
     * a graph with no transcript is the opposite of what they asked for.
     *
     * This is deliberately a *pure view* of the preference, not a rewrite of it. The caller must
     * keep persisting [preferred] so that widening the panel restores SPLIT — a transient narrow
     * layout must never destroy the user's choice.
     */
    fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =
        if (preferred == WorkspaceMode.SPLIT && !ResponsiveLayout.allowSplitDefault(profile)) {
            WorkspaceMode.CHAT
        } else {
            preferred
        }
}
