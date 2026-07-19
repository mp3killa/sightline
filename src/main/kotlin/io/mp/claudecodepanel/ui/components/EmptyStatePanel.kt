package io.mp.claudecodepanel.ui.components

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * A deliberate, centred empty state: optional icon, heading, supporting copy and a few optional
 * starter actions rendered as native [ActionLink]s. Reused by the transcript and the activity map.
 */
class EmptyStatePanel(
    icon: Icon?,
    heading: String,
    description: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) : JPanel(GridBagLayout()) {

    init {
        isOpaque = false
        val col = JPanel()
        col.isOpaque = false
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)

        if (icon != null) {
            val iconLabel = JBLabel(icon)
            iconLabel.alignmentX = Component.CENTER_ALIGNMENT
            col.add(iconLabel)
            col.add(strut(ClaudeUiTokens.md()))
        }

        val head = JBLabel(heading)
        head.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(15f).toFloat())
        head.foreground = ClaudeUiTokens.textPrimary()
        head.alignmentX = Component.CENTER_ALIGNMENT
        head.horizontalAlignment = JLabel.CENTER
        col.add(head)

        col.add(strut(ClaudeUiTokens.xs()))
        val desc = JBLabel("<html><div style='text-align:center;'>$description</div></html>")
        desc.foreground = ClaudeUiTokens.textSecondary()
        desc.alignmentX = Component.CENTER_ALIGNMENT
        desc.horizontalAlignment = JLabel.CENTER
        col.add(desc)

        if (actions.isNotEmpty()) {
            col.add(strut(ClaudeUiTokens.lg()))
            for ((label, run) in actions) {
                val link = ActionLink(label) { run() }
                link.alignmentX = Component.CENTER_ALIGNMENT
                col.add(link)
                col.add(strut(ClaudeUiTokens.sm()))
            }
        }

        add(col, GridBagConstraints())
    }

    private fun strut(h: Int): JComponent {
        val c = javax.swing.Box.createVerticalStrut(h) as JComponent
        c.alignmentX = Component.CENTER_ALIGNMENT
        return c
    }
}
