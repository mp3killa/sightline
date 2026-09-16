package io.mp.sightline.theme

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * A tiny theme-aware vector [Icon]. Icons are painted in code (not emoji/Unicode) so they render
 * crisply at any presentation scale, recolour with the IDE theme, and never depend on missing SVG
 * resources. Colour is resolved at paint time via [color], so a single instance follows theme
 * changes; [withColor]/[withSize] derive variants.
 */
class VectorIcon(
    private val baseSize: Int = 16,
    private val color: () -> Color = { ClaudeUiTokens.textSecondary() },
    private val painter: (g: Graphics2D, s: Int, c: Color) -> Unit,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(baseSize)
    override fun getIconHeight(): Int = JBUI.scale(baseSize)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.translate(x, y)
            painter(g2, JBUI.scale(baseSize), color())
        } finally {
            g2.dispose()
        }
    }

    fun withColor(c: () -> Color): VectorIcon = VectorIcon(baseSize, c, painter)
    fun withSize(size: Int): VectorIcon = VectorIcon(size, color, painter)
}

/** The panel's icon set. Prefer these over any glyph/emoji in interface chrome. */
object ClaudeIcons {

    /**
     * The brand mark, reusing the registered tool-window SVG so the stripe icon and the header
     * wordmark can never drift apart. `IconLoader` picks the `_dark` variant automatically.
     */
    val brand: Icon = IconLoader.getIcon("/icons/sightline.svg", ClaudeIcons::class.java)

    private fun stroke(s: Int, weight: Float = 0.095f): BasicStroke =
        BasicStroke((s * weight).coerceAtLeast(1.1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    private fun icon(painter: (Graphics2D, Int, Color) -> Unit) = VectorIcon(painter = painter)

    private fun icon(color: () -> Color, painter: (Graphics2D, Int, Color) -> Unit) =
        VectorIcon(color = color, painter = painter)

    // ---- header / actions ----

    val newChat = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s)
        val p = s * 0.16f
        // Document outline.
        g.draw(RoundRectangle2D.Float(p, p, s * 0.5f, s * 0.68f, s * 0.14f, s * 0.14f))
        // Pencil across the top-right corner.
        g.draw(line(s * 0.5f, s * 0.34f, s * 0.82f, s * 0.14f))
        g.draw(line(s * 0.66f, s * 0.5f, s * 0.86f, s * 0.3f))
        g.draw(line(s * 0.82f, s * 0.14f, s * 0.86f, s * 0.3f))
    }

    val more = icon { g, s, c ->
        g.color = c
        val r = s * 0.09f
        val cx = s * 0.5f
        for (cy in floatArrayOf(s * 0.25f, s * 0.5f, s * 0.75f)) {
            g.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
        }
    }

    val attach = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.088f)
        val old = g.transform
        g.rotate(Math.PI / 4, s * 0.5, s * 0.5)
        val w = s * 0.30f; val h = s * 0.60f
        val x = s * 0.5f - w / 2; val y = s * 0.5f - h / 2
        g.draw(RoundRectangle2D.Float(x, y, w, h, w, w))
        g.draw(line(s * 0.5f, y + w * 0.6f, s * 0.5f, y + h - w * 1.1f))
        g.transform = old
    }

    val slash = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.085f)
        val p = s * 0.16f
        g.draw(RoundRectangle2D.Float(p, p, s - 2 * p, s - 2 * p, s * 0.22f, s * 0.22f))
        g.draw(line(s * 0.6f, s * 0.3f, s * 0.4f, s * 0.7f))
    }

    val send = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.11f)
        g.draw(line(s * 0.5f, s * 0.8f, s * 0.5f, s * 0.22f))
        g.draw(line(s * 0.5f, s * 0.22f, s * 0.28f, s * 0.44f))
        g.draw(line(s * 0.5f, s * 0.22f, s * 0.72f, s * 0.44f))
    }

    val stop = icon { g, s, c ->
        g.color = c
        val side = s * 0.42f
        g.fill(RoundRectangle2D.Float(s * 0.5f - side / 2, s * 0.5f - side / 2, side, side, s * 0.08f, s * 0.08f))
    }

    // ---- chevrons / close ----

    val chevronRight = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.1f)
        g.draw(line(s * 0.4f, s * 0.28f, s * 0.64f, s * 0.5f))
        g.draw(line(s * 0.64f, s * 0.5f, s * 0.4f, s * 0.72f))
    }

    val chevronDown = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.1f)
        g.draw(line(s * 0.28f, s * 0.42f, s * 0.5f, s * 0.64f))
        g.draw(line(s * 0.5f, s * 0.64f, s * 0.72f, s * 0.42f))
    }

    val close = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.1f)
        g.draw(line(s * 0.3f, s * 0.3f, s * 0.7f, s * 0.7f))
        g.draw(line(s * 0.7f, s * 0.3f, s * 0.3f, s * 0.7f))
    }

    // ---- content actions ----

    val copy = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.085f)
        val r = s * 0.10f
        // Back sheet: top and left edges only, so the front sheet reads as sitting on top of it.
        val bp = GeneralPath()
        bp.moveTo((s * 0.28f).toDouble(), (s * 0.62f).toDouble())
        bp.lineTo((s * 0.28f).toDouble(), (s * 0.28f + r).toDouble())
        bp.quadTo((s * 0.28f).toDouble(), (s * 0.28f).toDouble(), (s * 0.28f + r).toDouble(), (s * 0.28f).toDouble())
        bp.lineTo((s * 0.62f).toDouble(), (s * 0.28f).toDouble())
        g.draw(bp)
        g.draw(RoundRectangle2D.Float(s * 0.40f, s * 0.40f, s * 0.42f, s * 0.42f, r, r))
    }

    // ---- tool icons ----

    val read = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.08f)
        val p = s * 0.2f
        g.draw(RoundRectangle2D.Float(p, s * 0.16f, s * 0.6f, s * 0.68f, s * 0.1f, s * 0.1f))
        g.draw(line(s * 0.32f, s * 0.36f, s * 0.68f, s * 0.36f))
        g.draw(line(s * 0.32f, s * 0.5f, s * 0.68f, s * 0.5f))
        g.draw(line(s * 0.32f, s * 0.64f, s * 0.56f, s * 0.64f))
    }

    val edit = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.09f)
        g.draw(line(s * 0.22f, s * 0.78f, s * 0.7f, s * 0.3f))
        g.draw(line(s * 0.7f, s * 0.3f, s * 0.82f, s * 0.42f))
        g.draw(line(s * 0.82f, s * 0.42f, s * 0.34f, s * 0.9f))
        g.draw(line(s * 0.22f, s * 0.78f, s * 0.2f, s * 0.9f))
        g.draw(line(s * 0.2f, s * 0.9f, s * 0.34f, s * 0.9f))
    }

    val search = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.095f)
        val d = s * 0.42f
        g.draw(Ellipse2D.Float(s * 0.22f, s * 0.22f, d, d))
        g.draw(line(s * 0.64f, s * 0.64f, s * 0.8f, s * 0.8f))
    }

    val web = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.078f)
        val p = s * 0.16f
        g.draw(Ellipse2D.Float(p, p, s - 2 * p, s - 2 * p))
        g.draw(line(p, s * 0.5f, s - p, s * 0.5f))
        g.draw(Ellipse2D.Float(s * 0.33f, p, s * 0.34f, s - 2 * p))
    }

    val command = icon { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.08f)
        val p = s * 0.16f
        g.draw(RoundRectangle2D.Float(p, p, s - 2 * p, s - 2 * p, s * 0.14f, s * 0.14f))
        g.draw(line(s * 0.3f, s * 0.4f, s * 0.42f, s * 0.5f))
        g.draw(line(s * 0.42f, s * 0.5f, s * 0.3f, s * 0.6f))
        g.draw(line(s * 0.5f, s * 0.62f, s * 0.68f, s * 0.62f))
    }

    val diamond = icon { g, s, c ->
        g.color = c
        val p = GeneralPath()
        p.moveTo(s * 0.5, s * 0.24); p.lineTo(s * 0.76, s * 0.5)
        p.lineTo(s * 0.5, s * 0.76); p.lineTo(s * 0.24, s * 0.5)
        p.closePath()
        g.fill(p)
    }

    // ---- state icons ----

    val check = icon({ ClaudeUiTokens.success() }) { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.12f)
        g.draw(line(s * 0.24f, s * 0.52f, s * 0.44f, s * 0.72f))
        g.draw(line(s * 0.44f, s * 0.72f, s * 0.78f, s * 0.28f))
    }

    val warningTriangle = icon({ ClaudeUiTokens.warning() }) { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.085f)
        val p = GeneralPath()
        p.moveTo(s * 0.5, s * 0.16); p.lineTo(s * 0.86, s * 0.8); p.lineTo(s * 0.14, s * 0.8)
        p.closePath()
        g.draw(p)
        g.draw(line(s * 0.5f, s * 0.4f, s * 0.5f, s * 0.6f))
        g.fill(Ellipse2D.Float(s * 0.5f - s * 0.045f, s * 0.66f, s * 0.09f, s * 0.09f))
    }

    val errorCircle = icon({ ClaudeUiTokens.error() }) { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.1f)
        val p = s * 0.16f
        g.draw(Ellipse2D.Float(p, p, s - 2 * p, s - 2 * p))
        g.draw(line(s * 0.36f, s * 0.36f, s * 0.64f, s * 0.64f))
        g.draw(line(s * 0.64f, s * 0.36f, s * 0.36f, s * 0.64f))
    }

    /** A prohibition sign (circle + diagonal bar): a blocked/denied action. */
    val blocked = icon({ ClaudeUiTokens.textSecondary() }) { g, s, c ->
        g.color = c; g.stroke = stroke(s, 0.1f)
        val p = s * 0.16f
        g.draw(Ellipse2D.Float(p, p, s - 2 * p, s - 2 * p))
        g.draw(line(s * 0.32f, s * 0.32f, s * 0.68f, s * 0.68f))
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        java.awt.geom.Line2D.Float(x1, y1, x2, y2)
}
