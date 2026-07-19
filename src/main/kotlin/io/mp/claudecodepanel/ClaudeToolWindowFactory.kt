package io.mp.claudecodepanel

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.mp.claudecodepanel.ui.ClaudePanel

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudePanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
