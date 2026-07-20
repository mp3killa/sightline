package io.mp.sightline.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.health.HealthCheck
import io.mp.sightline.health.HealthGatherer
import io.mp.sightline.health.HealthReport
import io.mp.sightline.health.HealthSanitizer
import io.mp.sightline.health.HealthStatus
import io.mp.sightline.settings.ClaudeSettingsConfigurable
import io.mp.sightline.theme.ClaudeUiTokens
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The Health / preflight panel: a modal read-out of whether the CLI, auth, IDE bridge, diagnostics and
 * permission mode are actually working, with a concrete next step for anything that isn't. It exists to
 * turn "it's not doing anything" into a self-serve answer instead of a support thread.
 *
 * Gathering (which shells out for `claude --version`) runs off the EDT; the dialog shows a "Checking…"
 * state until the report lands. "Copy report" copies the **sanitised** text ([HealthSanitizer]), safe to
 * paste into a public tracker.
 */
class HealthDialog(
    private val project: Project,
    private val facts: () -> HealthGatherer.SessionFacts,
) : DialogWrapper(project) {

    private val headline = JBLabel(" ")
    private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private var report: HealthReport? = null

    init {
        title = "Sightline Health"
        setModal(false)
        init()
        recheck()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(440))

        headline.font = headline.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(14f).toFloat())
        headline.border = JBUI.Borders.empty(4, 4, 10, 4)
        root.add(headline, BorderLayout.NORTH)

        val scroll = JBScrollPane(list)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.isOpaque = false
        scroll.isOpaque = false
        root.add(scroll, BorderLayout.CENTER)
        return root
    }

    // --- actions row ---

    override fun createActions(): Array<Action> = arrayOf(RecheckAction(), CopyReportAction(), OpenSettingsAction(), okAction())
    private fun okAction(): Action = okAction.apply { putValue(Action.NAME, "Close") }

    private inner class RecheckAction : DialogWrapperAction("Recheck") {
        override fun doAction(e: java.awt.event.ActionEvent?) = recheck()
    }
    private inner class OpenSettingsAction : DialogWrapperAction("Open settings") {
        override fun doAction(e: java.awt.event.ActionEvent?) =
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
    }
    private inner class CopyReportAction : DialogWrapperAction("Copy report") {
        override fun doAction(e: java.awt.event.ActionEvent?) {
            val r = report ?: return
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(HealthSanitizer.toReportText(r)), null)
            Messages.showInfoMessage(project, "A sanitised report was copied to the clipboard.", "Health Report Copied")
        }
    }

    // --- gather + render ---

    private fun recheck() {
        renderChecking()
        val snapshot = facts()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { HealthGatherer(project).gather(snapshot) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (isShowing) result?.let { render(io.mp.sightline.health.HealthChecker.evaluate(it)) } ?: renderError()
            }, ModalityState.any())
        }
    }

    private fun renderChecking() {
        headline.text = "Checking…"
        headline.foreground = ClaudeUiTokens.textSecondary()
        list.removeAll(); list.revalidate(); list.repaint()
    }

    private fun renderError() {
        headline.text = "Couldn't complete the health check"
        headline.foreground = ClaudeUiTokens.error()
    }

    private fun render(r: HealthReport) {
        report = r
        headline.text = r.headline
        headline.foreground = color(r.overall)
        list.removeAll()
        for (c in r.checks) {
            list.add(row(c))
            list.add(Box.createVerticalStrut(JBUI.scale(6)))
        }
        list.revalidate(); list.repaint()
    }

    private fun row(c: HealthCheck): JComponent {
        val card = JPanel(BorderLayout())
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.isOpaque = false
        card.border = JBUI.Borders.empty(2, 4)

        val dot = JBLabel(glyph(c.status))
        dot.foreground = color(c.status)
        dot.font = dot.font.deriveFont(Font.BOLD)
        dot.verticalAlignment = SwingConstants.TOP
        dot.border = JBUI.Borders.empty(0, 0, 0, 8)
        card.add(dot, BorderLayout.WEST)

        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        val name = JBLabel(c.name)
        name.font = name.font.deriveFont(Font.BOLD)
        name.alignmentX = Component.LEFT_ALIGNMENT
        body.add(name)

        val detail = wrappingLabel(c.detail, ClaudeUiTokens.textPrimary())
        body.add(detail)
        c.hint?.takeIf { c.status != HealthStatus.OK }?.let {
            val hint = wrappingLabel("→ $it", ClaudeUiTokens.textSecondary())
            hint.border = JBUI.Borders.emptyTop(2)
            body.add(hint)
        }
        card.add(body, BorderLayout.CENTER)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    /** A left-aligned, wrapping text line. The fixed-width HTML wrapper makes the label wrap, not sprawl. */
    private fun wrappingLabel(text: String, fg: java.awt.Color): JComponent {
        val label = JBLabel("<html><body style='width:${JBUI.scale(440)}px'>${escape(text)}</body></html>")
        label.foreground = fg
        label.alignmentX = Component.LEFT_ALIGNMENT
        label.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(12f).toFloat())
        return label
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun color(s: HealthStatus): java.awt.Color = when (s) {
        HealthStatus.OK -> ClaudeUiTokens.success()
        HealthStatus.WARN -> ClaudeUiTokens.warning()
        HealthStatus.UNKNOWN -> ClaudeUiTokens.textSecondary()
        HealthStatus.FAIL -> ClaudeUiTokens.error()
    }

    private fun glyph(s: HealthStatus): String = when (s) {
        HealthStatus.OK -> "●"
        HealthStatus.WARN -> "▲"
        HealthStatus.UNKNOWN -> "?"
        HealthStatus.FAIL -> "✕"
    }
}
