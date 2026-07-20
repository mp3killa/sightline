package io.mp.sightline.ui.components

import com.intellij.util.ui.JBUI
import io.mp.sightline.theme.ClaudeUiTokens
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton

/**
 * A compact, borderless icon-only button with a comfortable touch target, hover/focus affordance,
 * a tooltip and an accessible name (icon-only controls must always be labelled). Activates on
 * click and — because it is a real [JButton] — on Enter/Space when focused.
 */
class IconActionButton(
    icon: Icon,
    tooltip: String,
    private val onClick: () -> Unit,
) : JButton() {

    private var hovered = false

    /** Persistent "on" state for toggle-style actions (e.g. Split). Tints the button accent. */
    var toggledOn: Boolean = false
        set(value) { field = value; repaint() }

    init {
        setIcon(icon)
        setTooltip(tooltip)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        val d = Dimension(JBUI.scale(30), JBUI.scale(30))
        preferredSize = d
        minimumSize = d
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }

    fun setTooltip(tooltip: String) {
        toolTipText = tooltip
        // Call the method (not the `accessibleContext` field, which is null until lazily created).
        getAccessibleContext().accessibleName = tooltip
    }

    /** Swap the icon + tooltip together (used for toggles like pause/resume, send/stop). */
    fun update(icon: Icon, tooltip: String) {
        setIcon(icon)
        setTooltip(tooltip)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (toggledOn || hovered || isFocusOwner) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusSm()
            val inset = JBUI.scale(2)
            g2.color = when {
                toggledOn -> ClaudeUiTokens.withAlpha(ClaudeUiTokens.accent(), 0.16f)
                isFocusOwner -> ClaudeUiTokens.elevatedSurface()
                else -> ClaudeUiTokens.subtleSurface()
            }
            g2.fillRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, arc, arc)
            if (isFocusOwner || toggledOn) {
                g2.color = ClaudeUiTokens.accent()
                g2.drawRoundRect(inset, inset, width - 2 * inset - 1, height - 2 * inset - 1, arc, arc)
            }
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
