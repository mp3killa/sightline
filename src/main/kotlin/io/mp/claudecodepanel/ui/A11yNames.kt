package io.mp.claudecodepanel.ui

/**
 * Stable accessible-name constants for the controls a UI-automation driver (JetBrains Driver/Robot) or
 * an accessibility tool must find. Kept separate from visible button text, which changes during UX
 * work — a driver locating a control by wording would break on every copy tweak. Namespaced `sightline.*`.
 */
object A11yNames {
    const val APPROVAL_ALLOW = "sightline.approval.allow"
    const val APPROVAL_ALLOW_ALWAYS = "sightline.approval.allowAlways"
    const val APPROVAL_DENY = "sightline.approval.deny"
    const val DIFF_ACCEPT = "sightline.diff.accept"
    const val DIFF_REJECT = "sightline.diff.reject"
    const val COMPOSER_SEND = "sightline.composer.send"
    const val WORKSPACE_CHAT = "sightline.workspace.chat"
    const val WORKSPACE_ACTIVITY = "sightline.workspace.activity"
    const val TOOL_WINDOW_ROOT = "sightline.toolWindow.root"
}
