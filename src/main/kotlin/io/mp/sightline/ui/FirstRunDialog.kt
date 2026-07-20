package io.mp.sightline.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.settings.ClaudeSettings
import io.mp.sightline.settings.ClaudeSettingsConfigurable
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.state.FirstRunDisclosure
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The one-time disclosure, shown before the first message is ever sent.
 *
 * Deliberately **modal and before the send**, not a dismissible banner beside it. A banner alongside a
 * running agent is read after the fact, which defeats the point: the only useful moment to tell someone
 * that a tool is about to read their files and run commands is before it does.
 *
 * Kept to three sentences and two buttons. A longer notice is not a better disclosure — it is one
 * people click past, and a disclosure nobody reads is worse than none because it manufactures consent.
 * The wording and the mode-specific sentence live in [FirstRunDisclosure], where they are unit-tested.
 */
class FirstRunDialog(private val project: Project) : DialogWrapper(project, false) {

    private val settings = ClaudeSettings.getInstance().state

    init {
        title = FirstRunDisclosure.TITLE
        setOKButtonText(FirstRunDisclosure.CONTINUE)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8, 12, 4, 12)

        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)

        val mode = settings.permissionMode ?: "auto"
        for (paragraph in FirstRunDisclosure.body(mode)) {
            column.add(paragraph(paragraph, emphasised = paragraph == FirstRunDisclosure.modeSentence(mode) &&
                FirstRunDisclosure.isDangerousMode(mode)))
            column.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
        }
        column.add(paragraph(FirstRunDisclosure.FOOTER, secondary = true))

        root.add(column, BorderLayout.CENTER)
        root.preferredSize = Dimension(JBUI.scale(520), root.preferredSize.height)
        return root
    }

    /** Wrapping text via HTML: a plain JLabel won't wrap, and this is prose, not a caption. */
    private fun paragraph(text: String, emphasised: Boolean = false, secondary: Boolean = false): JComponent {
        val label = JBLabel("<html><body style='width:${JBUI.scale(480)}px'>$text</body></html>")
        label.font = UIUtil.getLabelFont()
        label.foreground = when {
            emphasised -> ClaudeUiTokens.warning()
            secondary -> ClaudeUiTokens.textSecondary()
            else -> ClaudeUiTokens.textPrimary()
        }
        label.alignmentX = JComponent.LEFT_ALIGNMENT
        return label
    }

    /** A route to the settings, so "I'd rather change the mode first" doesn't mean cancelling. */
    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction(FirstRunDisclosure.SETTINGS) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
            }
        },
    )

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun doOKAction() {
        settings.firstRunAcknowledged = true
        super.doOKAction()
    }

    companion object {
        /**
         * Shows the notice if it hasn't been acknowledged. Returns true when the caller may proceed —
         * i.e. the user acknowledged, or had already.
         *
         * Closing the dialog without acknowledging returns false and **cancels the send**. That is the
         * point of a disclosure: dismissing it is not consent.
         */
        fun ensureAcknowledged(project: Project): Boolean {
            val settings = ClaudeSettings.getInstance().state
            if (!FirstRunDisclosure.shouldShow(settings.firstRunAcknowledged)) return true
            return FirstRunDialog(project).showAndGet()
        }
    }
}
