package io.mp.sightline.ui.components

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeUiTokens
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/**
 * A compact segmented control for the most common view switch (e.g. Chat / Activity). The selected
 * segment is distinguished by an elevated pill, bold weight **and** an accent underline, so it reads
 * as selected without relying on colour alone. Focusable and keyboard-navigable (Left/Right/Home/End),
 * with per-segment accessible descriptions.
 */
class SegmentedControl<T>(
    segments: List<Pair<T, String>>,
    initial: T,
    private val onSelect: (T) -> Unit,
) : JComponent() {

    private data class Seg<T>(val value: T, val label: String, var bounds: Rectangle = Rectangle())

    private val segs = segments.map { Seg(it.first, it.second) }
    var selected: T = initial
        private set

    init {
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Switch view"
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                segs.firstOrNull { it.bounds.contains(e.point) }?.let { select(it.value) }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val i = segs.indexOfFirst { it.value == selected }.coerceAtLeast(0)
                when (e.keyCode) {
                    KeyEvent.VK_LEFT, KeyEvent.VK_UP -> if (i > 0) select(segs[i - 1].value)
                    KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN -> if (i < segs.size - 1) select(segs[i + 1].value)
                    KeyEvent.VK_HOME -> select(segs.first().value)
                    KeyEvent.VK_END -> select(segs.last().value)
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    // JComponent doesn't create an AccessibleContext on its own, so provide one with a name/role.
    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PAGE_TAB_LIST
            }
            accessibleContext.accessibleName = "Workspace selector"
        }
        return accessibleContext
    }

    /** Set selection without firing the callback (for external/persisted sync). */
    fun setSelectedSilently(value: T) {
        if (segs.any { it.value == value }) { selected = value; repaint() }
    }

    private fun select(value: T) {
        if (value == selected) return
        selected = value
        repaint()
        onSelect(value)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font ?: UIUtil.getLabelFont())
        val perPad = JBUI.scale(14)
        val w = segs.sumOf { (fm.stringWidth(it.label) + 2 * perPad) } + JBUI.scale(4)
        return Dimension(w, JBUI.scale(28))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, JBUI.scale(30))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = ClaudeUiTokens.radiusMd()
        // Track.
        g2.color = ClaudeUiTokens.subtleSurface()
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = if (isFocusOwner) ClaudeUiTokens.accent() else ClaudeUiTokens.border()
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

        val baseFont = font ?: UIUtil.getLabelFont()
        val fm = g2.getFontMetrics(baseFont)
        val perPad = JBUI.scale(14)
        var x = JBUI.scale(2)
        val segH = height - JBUI.scale(4)
        val y = JBUI.scale(2)
        for (seg in segs) {
            val w = fm.stringWidth(seg.label) + 2 * perPad
            seg.bounds = Rectangle(x, 0, w, height)
            val isSel = seg.value == selected
            if (isSel) {
                g2.color = ClaudeUiTokens.elevatedSurface()
                g2.fillRoundRect(x, y, w, segH, ClaudeUiTokens.radiusSm(), ClaudeUiTokens.radiusSm())
                // Accent underline: a shape cue independent of colour perception.
                g2.color = ClaudeUiTokens.accent()
                val uw = w - 2 * perPad
                g2.fillRoundRect(x + perPad, y + segH - JBUI.scale(3), uw, JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
            }
            g2.font = if (isSel) baseFont.deriveFont(Font.BOLD) else baseFont
            g2.color = if (isSel) ClaudeUiTokens.textPrimary() else ClaudeUiTokens.textSecondary()
            val tx = x + (w - fm.stringWidth(seg.label)) / 2
            val ty = (height - fm.height) / 2 + fm.ascent
            g2.drawString(seg.label, tx, ty)
            x += w
        }
        g2.dispose()
    }
}
