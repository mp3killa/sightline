package io.mp.sightline.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeIcons
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.components.IconActionButton
import io.mp.sightline.ui.state.LayoutProfile
import io.mp.sightline.ui.state.ResponsiveLayout
import io.mp.sightline.ui.state.StatusKind
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JPanel

/**
 * Compact application header: brand + session state on the left, icon actions New + More on the
 * right. The centre held a Chat/Activity switch and a Split toggle until the activity map was
 * removed; with one view left there is nothing to switch between, and a control that only ever
 * selects the view you are already in is worse than no control.
 */
class ClaudeToolHeader(
    private val onNew: () -> Unit,
    private val onMore: (Component) -> Unit,
) : JPanel(BorderLayout()) {

    private val brandLabel = JBLabel("Sightline")
    private val stateDot = StateDot()
    private val stateLabel = JBLabel("Ready")
    private val newButton = IconActionButton(ClaudeIcons.newChat, "New conversation") { onNew() }
    private val moreButton = IconActionButton(ClaudeIcons.more, "More actions") { onMore(moreButtonAnchor()) }

    init {
        isOpaque = true
        background = ClaudeUiTokens.panel()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ClaudeUiTokens.border()),
            JBUI.Borders.empty(3, 8),
        )
        preferredSize = Dimension(JBUI.scale(320), JBUI.scale(40))

        add(buildLeft(), BorderLayout.WEST)
        add(buildRight(), BorderLayout.EAST)
    }

    private fun buildLeft(): JPanel {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        left.isOpaque = false
        left.add(JBLabel(ClaudeIcons.brand))
        brandLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        brandLabel.foreground = ClaudeUiTokens.textPrimary()
        left.add(brandLabel)
        left.add(sep())
        left.add(stateDot)
        stateLabel.foreground = ClaudeUiTokens.textSecondary()
        stateLabel.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        left.add(stateLabel)
        return left
    }

    private fun buildRight(): JPanel {
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0))
        right.isOpaque = false
        right.add(newButton)
        right.add(moreButton)
        return right
    }

    private fun sep(): JPanel {
        val p = JPanel()
        p.isOpaque = false
        p.preferredSize = Dimension(JBUI.scale(1), JBUI.scale(1))
        return p
    }

    private fun moreButtonAnchor(): Component = moreButton

    /** Coarse session state shown as a semantic dot + short label. */
    fun setSessionState(kind: StatusKind, label: String) {
        stateDot.color = ClaudeUiTokens.statusColor(kind)
        stateDot.repaint()
        stateLabel.text = label
    }

    /** Hide non-essential chrome on narrow panels. */
    fun applyProfile(profile: LayoutProfile) {
        val labels = ResponsiveLayout.showHeaderLabels(profile)
        brandLabel.isVisible = labels
        stateLabel.isVisible = labels
        revalidate(); repaint()
    }


    private class StateDot : JPanel() {
        var color: Color = ClaudeUiTokens.textSecondary()
        init { isOpaque = false; preferredSize = Dimension(JBUI.scale(10), JBUI.scale(14)) }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(4)
            g2.color = color
            g2.fillOval((width - 2 * r) / 2, (height - 2 * r) / 2, 2 * r, 2 * r)
            g2.dispose()
        }
    }
}
