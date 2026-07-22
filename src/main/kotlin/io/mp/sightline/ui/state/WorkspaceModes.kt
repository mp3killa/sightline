package io.mp.sightline.ui.state

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
     * room there is. SPLIT is honoured wherever the split *button* is offered — see
     * [ResponsiveLayout.allowSplit] — because a visible control that silently does nothing reads as
     * a bug, and on a typical docked panel (MEDIUM) that is exactly what a stricter gate produced:
     * the toggle lit up and the layout never changed. Only NARROW demotes, where two panes
     * genuinely cannot coexist — and it falls back to **CHAT**, never to ACTIVITY: dropping the
     * user into a graph with no transcript is the opposite of what they asked for.
     * ([ResponsiveLayout.allowSplitDefault] still gates where SPLIT is *proposed* unasked, e.g.
     * reveal-in-map choosing between SPLIT and MAP.)
     *
     * This is deliberately a *pure view* of the preference, not a rewrite of it. The caller must
     * keep persisting [preferred] so that widening the panel restores SPLIT — a transient narrow
     * layout must never destroy the user's choice.
     */
    fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =
        if (preferred == WorkspaceMode.SPLIT && !ResponsiveLayout.allowSplit(profile)) {
            WorkspaceMode.CHAT
        } else {
            preferred
        }
}
