package io.mp.sightline.ui.markdown.mermaid

import java.awt.Shape
import java.awt.geom.Path2D
import kotlin.math.hypot

/**
 * The edge arrowhead a mermaid diagram draws.
 *
 * It lived in the activity map's `NodeGlyph` until the map was removed, borrowed from there by
 * [io.mp.sightline.ui.markdown.MermaidView]. Keeping a graph-drawing file alive for one function that
 * only the diagram renderer calls would have left an unowned utility behind, so it moved to the
 * package that uses it — platform-free (java.awt only) and unit-tested, like the rest of `mermaid/`.
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
