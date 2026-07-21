package io.mp.sightline.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeIcons
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.components.IconActionButton
import io.mp.sightline.ui.components.SegmentedControl
import io.mp.sightline.ui.state.LayoutProfile
import io.mp.sightline.ui.state.ResponsiveLayout
import io.mp.sightline.ui.state.StatusKind
import io.mp.sightline.ui.state.WorkspaceMode
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
 * Compact application header with three regions: brand + session state (left), a segmented
 * Chat/Activity workspace switch with a secondary Split toggle (centre), and icon actions
 * New + More (right). Common view switching is one click; everything secondary lives in More.
 */
class ClaudeToolHeader(
    initialMode: WorkspaceMode,
    private val onWorkspace: (WorkspaceMode) -> Unit,
    private val onToggleSplit: () -> Unit,
    private val onNew: () -> Unit,
    private val onMore: (Component) -> Unit,
) : JPanel(BorderLayout()) {

    private val brandLabel = JBLabel("Sightline")
    private val stateDot = StateDot()
    private val stateLabel = JBLabel("Ready")
    private val segmented = SegmentedControl(
        listOf(WorkspaceMode.CHAT to "Chat", WorkspaceMode.ACTIVITY to "Activity"),
        if (initialMode == WorkspaceMode.CHAT) WorkspaceMode.CHAT else WorkspaceMode.ACTIVITY,
    ) { onWorkspace(it) }
    private val splitButton = IconActionButton(ClaudeIcons.split, "Split view (chat + activity)") { onToggleSplit() }
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
        add(buildCenter(), BorderLayout.CENTER)
        add(buildRight(), BorderLayout.EAST)
        setWorkspace(initialMode)
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

    private fun buildCenter(): JPanel {
        val center = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), JBUI.scale(3)))
        center.isOpaque = false
        center.add(segmented)
        center.add(splitButton)
        return center
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

    /** Reflects the active workspace on the segmented control and the Split toggle. */
    fun setWorkspace(mode: WorkspaceMode) {
        segmented.setSelectedSilently(if (mode == WorkspaceMode.CHAT) WorkspaceMode.CHAT else WorkspaceMode.ACTIVITY)
        splitButton.toggledOn = mode == WorkspaceMode.SPLIT
    }

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
        splitButton.isVisible = ResponsiveLayout.allowSplit(profile)
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
