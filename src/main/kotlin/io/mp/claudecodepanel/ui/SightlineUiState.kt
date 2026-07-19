package io.mp.claudecodepanel.ui

import com.google.gson.JsonObject
import javax.swing.JComponent

/**
 * A small, thread-safe snapshot of the tool window's presentation state, published by [ClaudePanel] and
 * read by the sandbox test bridge (`sightline.test.get_ui_state` / `capture_tool_window`). Project
 * service (registered in plugin.xml). Holds no business logic — just what an automated check needs to
 * assert against, screenshot, or drive.
 */
class SightlineUiState {
    @Volatile var toolWindowVisible: Boolean = false
    @Volatile var workspace: String = "CHAT"      // CHAT | ACTIVITY | SPLIT
    @Volatile var sessionState: String = "READY"  // READY | WORKING | WAITING_FOR_APPROVAL | ...
    /** The tool window's root Swing component, for off-EDT-triggered on-EDT capture. */
    @Volatile var rootComponent: JComponent? = null
    /** TEST-ONLY seam: inject a synthetic AskUserQuestion (the gated bridge's `simulate_question`). */
    @Volatile var askQuestionSimulator: ((JsonObject) -> Unit)? = null
}
