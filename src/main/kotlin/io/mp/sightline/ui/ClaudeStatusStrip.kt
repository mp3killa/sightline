package io.mp.sightline.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.state.StatusKind
import io.mp.sightline.ui.state.StatusView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The single coordinated status line, immediately above the composer. Shows one primary status
 * (driven by [StatusModel] — the same event stream as the graph focus) with an animated semantic
 * dot, plus optional right-aligned metadata (elapsed / model / cost). It never disappears abruptly:
 * on completion it settles into a muted summary. Animation respects reduce-motion.
 */
class ClaudeStatusStrip(parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val dot = Dot()
    private val primary = JBLabel(" ")
    private val secondary = JBLabel(" ")
    private var view: StatusView? = null
    private var reduceMotion = false
    private val pulse = Timer(60) { dot.phase += 0.14; if (dot.animated) dot.repaint() }

    init {
        com.intellij.openapi.util.Disposer.register(parent, this)
        isOpaque = false
        border = JBUI.Borders.empty(3, 12, 3, 12)
        val left = JPanel(BorderLayout(JBUI.scale(8), 0))
        left.isOpaque = false
        left.add(dot, BorderLayout.WEST)
        primary.font = UIUtil.getLabelFont()
        primary.foreground = ClaudeUiTokens.textPrimary()
        left.add(primary, BorderLayout.CENTER)
        add(left, BorderLayout.CENTER)
        secondary.foreground = ClaudeUiTokens.textSecondary()
        secondary.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        add(secondary, BorderLayout.EAST)

        addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                if (isShowing && dot.animated && !reduceMotion) pulse.start() else pulse.stop()
            }
        }
    }

    fun setReduceMotion(value: Boolean) {
        reduceMotion = value
        if (reduceMotion) pulse.stop() else if (isShowing && dot.animated) pulse.start()
        dot.repaint()
    }

    fun update(v: StatusView, meta: String?) {
        view = v
        dot.color = ClaudeUiTokens.statusColor(v.kind)
        dot.animated = v.animated
        primary.text = v.primary.ifBlank { " " }
        primary.foreground = if (v.kind == StatusKind.ERROR) ClaudeUiTokens.error() else ClaudeUiTokens.textPrimary()
        val sec = listOfNotNull(v.secondary?.takeIf { it.isNotBlank() }, meta?.takeIf { it.isNotBlank() })
            .joinToString("   ·   ")
        secondary.text = sec.ifBlank { " " }
        if (isShowing && v.animated && !reduceMotion) pulse.start() else pulse.stop()
        dot.repaint()
    }


    override fun dispose() { pulse.stop() }

    /** A small semantic dot with a gentle recency pulse (never a big spinner). */
    private inner class Dot : JPanel() {
        var color: Color = ClaudeUiTokens.textSecondary()
        var animated: Boolean = false
        var phase: Double = 0.0
        init { isOpaque = false; preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14)) }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val cx = width / 2f; val cy = height / 2f
            val r = JBUI.scale(4).toFloat()
            if (animated && !reduceMotion) {
                val pr = r + JBUI.scale(3) + (Math.sin(phase) * JBUI.scale(2)).toFloat()
                g2.color = ClaudeUiTokens.withAlpha(color, 0.30f)
                g2.fillOval((cx - pr).toInt(), (cy - pr).toInt(), (pr * 2).toInt(), (pr * 2).toInt())
            }
            g2.color = color
            g2.fillOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
            g2.dispose()
        }
    }
}
