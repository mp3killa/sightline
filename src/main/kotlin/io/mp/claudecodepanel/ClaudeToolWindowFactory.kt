package io.mp.claudecodepanel

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.mp.claudecodepanel.ui.ClaudePanel

/**
 * Registers the right-dock "Sightline" tool window. Kept intentionally small: it builds [ClaudePanel],
 * wraps it in a single non-closeable content, and points keyboard focus at the composer. The tool
 * window already carries the "Sightline" display name, so the content title is left empty (no dupe tab).
 */
class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudePanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.preferredFocusableComponent = panel.preferredFocusComponent()
        toolWindow.contentManager.addContent(content)
    }
}
