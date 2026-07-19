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
}
