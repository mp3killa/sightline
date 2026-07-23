package io.mp.sightline.activity

import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.hypot

/**
 * The **shape** a node is drawn as, keyed to what *kind* of thing it represents.
 *
 * Shape encodes TYPE; colour and rings still encode STATE ([ActivityColorRoles] plus the panel's ring
 * passes). Distinguishing a file, a command, a test and an error by shape as well as colour is both a
 * legibility win on a dense graph — GPT's review called the map "a field of hundreds of similar
 * circles" — and an accessibility one: state is no longer communicated by colour alone.
 *
 * Platform-free. The [glyphFor] mapping is the testable part; [glyphShape] and [arrowhead] build plain
 * `java.awt` shapes (no IntelliJ types) so the live Swing panel and the headless PNG renderer share one
 * source of truth and cannot drift.
 */
enum class NodeGlyph { CIRCLE, DOCUMENT, TERMINAL, DIAMOND, TRIANGLE, HEXAGON }

/** The shape for a node type. TASK/CATEGORY intentionally stay [NodeGlyph.CIRCLE] — they are the
 *  scaffolding hubs and carry their own halo/ring treatment, not a type glyph. */
fun glyphFor(type: ActivityNodeType): NodeGlyph = when (type) {
    ActivityNodeType.FILE, ActivityNodeType.CLASS, ActivityNodeType.INTERFACE, ActivityNodeType.OBJECT,
    ActivityNodeType.COMPOSABLE, ActivityNodeType.VIEW_MODEL, ActivityNodeType.REPOSITORY,
    ActivityNodeType.USE_CASE, ActivityNodeType.API_ENDPOINT, ActivityNodeType.DOCUMENTATION -> NodeGlyph.DOCUMENT
    ActivityNodeType.COMMAND, ActivityNodeType.GRADLE_TASK -> NodeGlyph.TERMINAL
    ActivityNodeType.TEST -> NodeGlyph.HEXAGON
    ActivityNodeType.WARNING -> NodeGlyph.TRIANGLE
    ActivityNodeType.ERROR -> NodeGlyph.DIAMOND
    else -> NodeGlyph.CIRCLE
}

/** The compact type key shown as the map's persistent legend (shape → plain label). */
val TYPE_LEGEND: List<Pair<NodeGlyph, String>> = listOf(
    NodeGlyph.DOCUMENT to "File",
    NodeGlyph.TERMINAL to "Command",
    NodeGlyph.HEXAGON to "Test",
    NodeGlyph.TRIANGLE to "Warning",
    NodeGlyph.DIAMOND to "Error",
)

/**
 * An AWT [Shape] centred at ([cx], [cy]) whose footprint is about a diameter of `2*r`, so every glyph
 * reads at roughly the same size as the circle it replaces. Callers fill and stroke it as before.
 */
fun glyphShape(glyph: NodeGlyph, cx: Double, cy: Double, r: Double): Shape = when (glyph) {
    NodeGlyph.CIRCLE -> Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2)
    NodeGlyph.DOCUMENT -> {
        // A page: a little narrower than tall, gently rounded.
        val hw = r * 0.74; val hh = r * 0.94
        RoundRectangle2D.Double(cx - hw, cy - hh, hw * 2, hh * 2, r * 0.5, r * 0.5)
    }
    NodeGlyph.TERMINAL -> {
        // A console window: wide and short.
        val hw = r * 1.02; val hh = r * 0.72
        RoundRectangle2D.Double(cx - hw, cy - hh, hw * 2, hh * 2, r * 0.35, r * 0.35)
    }
    NodeGlyph.DIAMOND -> polygon(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy)
    NodeGlyph.TRIANGLE -> polygon(cx, cy - r, cx + r * 0.92, cy + r * 0.72, cx - r * 0.92, cy + r * 0.72)
    NodeGlyph.HEXAGON -> {
        // Pointy-top hexagon.
        val p = Path2D.Double()
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i - 90.0)
            val x = cx + r * Math.cos(a); val y = cy + r * Math.sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.closePath(); p
    }
}

private fun polygon(vararg xy: Double): Shape {
    val p = Path2D.Double()
    p.moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
    p.closePath()
    return p
}

/**
 * A small filled triangle at the `(x2,y2)` end of an edge, backed off by [targetRadius] so its tip
 * sits on the target node's rim rather than its centre. Returns `null` for a zero-length edge. This is
 * what turns the map's "faint undirected lines" into a readable execution direction.
 */
fun arrowhead(x1: Double, y1: Double, x2: Double, y2: Double, targetRadius: Double, size: Double): Shape? {
    val dx = x2 - x1; val dy = y2 - y1
    val len = hypot(dx, dy)
    if (len < 1e-3) return null
    val ux = dx / len; val uy = dy / len
    val tipX = x2 - ux * targetRadius; val tipY = y2 - uy * targetRadius
    val baseX = tipX - ux * size; val baseY = tipY - uy * size
    val px = -uy; val py = ux
    val half = size * 0.55
    val p = Path2D.Double()
    p.moveTo(tipX, tipY)
    p.lineTo(baseX + px * half, baseY + py * half)
    p.lineTo(baseX - px * half, baseY - py * half)
    p.closePath()
    return p
}
