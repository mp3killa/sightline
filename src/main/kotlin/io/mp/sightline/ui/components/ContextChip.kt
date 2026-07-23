package io.mp.sightline.ui.components

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeIcons
import io.mp.sightline.theme.ClaudeUiTokens
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * A small removable "attached context" pill (e.g. `ClaudePanel.kt ×`) shown above the composer.
 * Backed by a structured value (the project-relative path); removing it invokes [onRemove].
 */
class ContextChip(
    val value: String,
    label: String,
    /** Optional leading icon — e.g. a thumbnail for a pasted-image chip. */
    icon: javax.swing.Icon? = null,
    onRemove: (String) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(1))) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 8, 1, 3)
        val text = JBLabel(label)
        text.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, JBUI.scaleFontSize(11f).toFloat())
        text.foreground = ClaudeUiTokens.textPrimary()
        text.toolTipText = value
        if (icon != null) {
            text.icon = icon
            text.iconTextGap = JBUI.scale(5)
        }
        add(text, BorderLayout.CENTER)
        val remove = IconActionButton(
            ClaudeIcons.close.withSize(12).withColor { ClaudeUiTokens.textSecondary() },
            "Remove $label",
        ) { onRemove(value) }
        remove.preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18))
        remove.minimumSize = remove.preferredSize
        add(remove)
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = ClaudeUiTokens.radiusMd()
        g2.color = ClaudeUiTokens.subtleSurface()
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = ClaudeUiTokens.border()
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }
}
