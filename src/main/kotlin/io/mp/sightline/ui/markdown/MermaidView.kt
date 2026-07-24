package io.mp.sightline.ui.markdown

import io.mp.sightline.activity.arrowhead
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.markdown.mermaid.MermaidDiagram
import io.mp.sightline.ui.markdown.mermaid.MermaidEdgeStyle
import io.mp.sightline.ui.markdown.mermaid.MermaidLayout
import io.mp.sightline.ui.markdown.mermaid.MermaidNode
import io.mp.sightline.ui.markdown.mermaid.MermaidShape
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * Draws a parsed [MermaidDiagram] natively in Swing — the plugin cannot run mermaid.js (this JBR has no
 * JCEF/Chromium; see CLAUDE.md), so a flowchart/state diagram is laid out by [MermaidLayout] and painted
 * here: node shapes carry the author's intent (a rhombus is a decision), edges are clipped box-to-box
 * with arrowheads, dotted/thick styles honoured, and labels drawn on a chip at the segment midpoint.
 * Theme-aware; measurement uses the component's own `FontMetrics` so text fits its box.
 */
object MermaidView {

    private const val PSEUDO_DIAMETER = 16.0 // start/end state dots (empty label)

    fun component(diagram: MermaidDiagram): JComponent = DiagramComponent(diagram)

    private class DiagramComponent(private val diagram: MermaidDiagram) : JComponent() {
        private val font: Font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
        private val pad = JBUI.scale(10)
        private val layout: MermaidLayout.Result

        init {
            isOpaque = false
            val fm = getFontMetrics(font)
            val lineH = fm.height.toDouble()
            layout = MermaidLayout.layout(diagram) { node -> sizeOf(node, fm, lineH) }
        }

        private fun sizeOf(node: MermaidNode, fm: java.awt.FontMetrics, lineH: Double): MermaidLayout.Size {
            if (node.label.isEmpty()) return MermaidLayout.Size(JBUI.scale(PSEUDO_DIAMETER.toInt()).toDouble(), JBUI.scale(PSEUDO_DIAMETER.toInt()).toDouble())
            val lines = node.label.split('\n')
            val textW = lines.maxOf { fm.stringWidth(it) }.toDouble()
            val textH = lineH * lines.size
            val padX = JBUI.scale(16).toDouble()
            val padY = JBUI.scale(10).toDouble()
            var w = textW + padX * 2
            var h = textH + padY * 2
            when (node.shape) {
                // Diamonds/hexagons waste corner space, so the box must be roomier to keep text inside.
                MermaidShape.RHOMBUS -> { w *= 1.5; h *= 1.5 }
                MermaidShape.HEXAGON -> { w += JBUI.scale(20); }
                MermaidShape.STADIUM -> { w += JBUI.scale(12) }
                MermaidShape.CIRCLE, MermaidShape.DOUBLE_CIRCLE -> {
                    val d = maxOf(w, h); w = d; h = d
                }
                else -> {}
            }
            return MermaidLayout.Size(maxOf(w, JBUI.scale(40).toDouble()), maxOf(h, JBUI.scale(30).toDouble()))
        }

        override fun getPreferredSize(): Dimension =
            Dimension((layout.width + pad * 2).toInt(), (layout.height + pad * 2).toInt())

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.translate(pad, pad)
            g2.font = font
            drawEdges(g2)
            drawNodes(g2)
            g2.dispose()
        }

        private fun drawEdges(g2: Graphics2D) {
            val fm = g2.fontMetrics
            for (e in diagram.edges) {
                val s = layout.nodes[e.from] ?: continue
                val t = layout.nodes[e.to] ?: continue
                val p1 = borderPoint(s, t.cx, t.cy)
                val p2 = borderPoint(t, s.cx, s.cy)
                g2.color = ClaudeUiTokens.withAlpha(ClaudeUiTokens.textSecondary(), 0.7f)
                g2.stroke = when (e.style) {
                    MermaidEdgeStyle.DOTTED -> BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(3f, 4f), 0f)
                    MermaidEdgeStyle.THICK -> BasicStroke(2.6f)
                    MermaidEdgeStyle.SOLID -> BasicStroke(1.4f)
                }
                g2.drawLine(p1[0].toInt(), p1[1].toInt(), p2[0].toInt(), p2[1].toInt())
                if (e.arrow) {
                    arrowhead(p1[0], p1[1], p2[0], p2[1], targetRadius = 0.0, size = JBUI.scale(9).toDouble())?.let {
                        g2.color = ClaudeUiTokens.withAlpha(ClaudeUiTokens.textSecondary(), 0.85f)
                        g2.fill(it)
                    }
                }
                e.label?.takeIf { it.isNotBlank() }?.let { drawEdgeLabel(g2, fm, it, (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2) }
            }
            g2.stroke = BasicStroke(1f)
        }

        private fun drawEdgeLabel(g2: Graphics2D, fm: java.awt.FontMetrics, text: String, cx: Double, cy: Double) {
            val w = fm.stringWidth(text) + JBUI.scale(6)
            val h = fm.height
            val x = (cx - w / 2).toInt(); val y = (cy - h / 2).toInt()
            g2.color = ClaudeUiTokens.subtleSurface()
            g2.fillRoundRect(x, y, w, h, JBUI.scale(5), JBUI.scale(5))
            g2.color = ClaudeUiTokens.textSecondary()
            g2.drawString(text, x + JBUI.scale(3), y + fm.ascent)
        }

        private fun drawNodes(g2: Graphics2D) {
            val fm = g2.fontMetrics
            for (node in diagram.nodes) {
                val r = layout.nodes[node.id] ?: continue
                if (node.label.isEmpty()) { drawPseudostate(g2, node, r); continue }
                val shape = shapeFor(node.shape, r)
                g2.color = ClaudeUiTokens.subtleSurface()
                g2.fill(shape)
                // A decision (rhombus) gets the accent border so a branch point reads at a glance.
                g2.color = if (node.shape == MermaidShape.RHOMBUS) ClaudeUiTokens.accent() else ClaudeUiTokens.border()
                g2.stroke = BasicStroke(1.4f)
                g2.draw(shape)
                if (node.shape == MermaidShape.DOUBLE_CIRCLE) {
                    val inset = JBUI.scale(4)
                    g2.draw(Ellipse2D.Double(r.x + inset, r.y + inset, r.w - inset * 2, r.h - inset * 2))
                }
                if (node.shape == MermaidShape.SUBROUTINE) {
                    val bar = JBUI.scale(6)
                    g2.drawLine((r.x + bar).toInt(), r.y.toInt(), (r.x + bar).toInt(), (r.y + r.h).toInt())
                    g2.drawLine((r.x + r.w - bar).toInt(), r.y.toInt(), (r.x + r.w - bar).toInt(), (r.y + r.h).toInt())
                }
                g2.stroke = BasicStroke(1f)
                drawLabel(g2, fm, node.label, r)
            }
        }

        private fun drawPseudostate(g2: Graphics2D, node: MermaidNode, r: MermaidLayout.Rect) {
            g2.color = ClaudeUiTokens.textPrimary()
            if (node.shape == MermaidShape.DOUBLE_CIRCLE) { // final: ring + inner dot
                g2.stroke = BasicStroke(1.4f)
                g2.draw(Ellipse2D.Double(r.x, r.y, r.w, r.h))
                val inset = JBUI.scale(4)
                g2.fill(Ellipse2D.Double(r.x + inset, r.y + inset, r.w - inset * 2, r.h - inset * 2))
                g2.stroke = BasicStroke(1f)
            } else { // initial: solid dot
                g2.fill(Ellipse2D.Double(r.x, r.y, r.w, r.h))
            }
        }

        private fun drawLabel(g2: Graphics2D, fm: java.awt.FontMetrics, label: String, r: MermaidLayout.Rect) {
            g2.color = ClaudeUiTokens.textPrimary()
            val lines = label.split('\n')
            val totalH = fm.height * lines.size
            var y = (r.cy - totalH / 2 + fm.ascent).toInt()
            for (line in lines) {
                val x = (r.cx - fm.stringWidth(line) / 2.0).toInt()
                g2.drawString(line, x, y)
                y += fm.height
            }
        }

        private fun shapeFor(shape: MermaidShape, r: MermaidLayout.Rect): java.awt.Shape = when (shape) {
            MermaidShape.RECT -> Rectangle2D.Double(r.x, r.y, r.w, r.h)
            MermaidShape.ROUNDED, MermaidShape.SUBROUTINE -> RoundRectangle2D.Double(r.x, r.y, r.w, r.h, JBUI.scale(10).toDouble(), JBUI.scale(10).toDouble())
            MermaidShape.STADIUM -> RoundRectangle2D.Double(r.x, r.y, r.w, r.h, r.h, r.h)
            MermaidShape.CIRCLE, MermaidShape.DOUBLE_CIRCLE -> Ellipse2D.Double(r.x, r.y, r.w, r.h)
            MermaidShape.RHOMBUS -> Path2D.Double().apply {
                moveTo(r.cx, r.y); lineTo(r.x + r.w, r.cy); lineTo(r.cx, r.y + r.h); lineTo(r.x, r.cy); closePath()
            }
            MermaidShape.HEXAGON -> {
                val inset = r.w * 0.18
                Path2D.Double().apply {
                    moveTo(r.x + inset, r.y); lineTo(r.x + r.w - inset, r.y); lineTo(r.x + r.w, r.cy)
                    lineTo(r.x + r.w - inset, r.y + r.h); lineTo(r.x + inset, r.y + r.h); lineTo(r.x, r.cy); closePath()
                }
            }
        }

        /** The point on [rect]'s border along the line from its centre toward (toX,toY). */
        private fun borderPoint(rect: MermaidLayout.Rect, toX: Double, toY: Double): DoubleArray {
            val dx = toX - rect.cx; val dy = toY - rect.cy
            if (dx == 0.0 && dy == 0.0) return doubleArrayOf(rect.cx, rect.cy)
            val scaleX = if (dx != 0.0) (rect.w / 2) / Math.abs(dx) else Double.MAX_VALUE
            val scaleY = if (dy != 0.0) (rect.h / 2) / Math.abs(dy) else Double.MAX_VALUE
            val t = Math.min(scaleX, scaleY)
            return doubleArrayOf(rect.cx + dx * t, rect.cy + dy * t)
        }
    }
}
