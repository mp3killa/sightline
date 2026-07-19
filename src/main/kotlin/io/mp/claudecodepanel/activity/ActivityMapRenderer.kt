package io.mp.claudecodepanel.activity

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * **Dev / verification tool** (not used by the live UI): renders an [ActivityGraph] to a static
 * PNG/JPG so the map can be eyeballed headlessly — e.g. from a test — without launching the IDE.
 *
 * It is platform-free (plain `java.awt`, no JBColor/UIUtil) and deterministic (seeded layout), so
 * it can run in a normal unit test. It reuses the shared [ActivityColorRoles] mapping, so colours
 * and structure faithfully represent what the Swing `ActivityMapPanel` draws, even though it is not
 * pixel-identical (the live panel is theme-aware and animated).
 */
object ActivityMapRenderer {

    private class P(var x: Double, var y: Double)

    fun renderToFile(
        graph: ActivityGraph,
        file: File,
        width: Int = 1400,
        height: Int = 900,
        dark: Boolean = true,
        title: String? = null,
    ) {
        val img = render(graph, width, height, dark, title)
        file.parentFile?.mkdirs()
        val fmt = if (file.extension.lowercase() in setOf("jpg", "jpeg")) "jpg" else "png"
        ImageIO.write(img, fmt, file)
    }

    fun render(graph: ActivityGraph, width: Int, height: Int, dark: Boolean, title: String?): BufferedImage {
        val ids = graph.nodes.map { it.id }
        val pos = layout(graph, ids)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = if (dark) Color(0x1E, 0x1F, 0x22) else Color(0xF7, 0xF8, 0xFA)
        g.fillRect(0, 0, width, height)

        if (pos.isEmpty()) { g.dispose(); return img }

        // Fit world positions into the image with padding (extra on the right for labels).
        val minX = pos.values.minOf { it.x }; val maxX = pos.values.maxOf { it.x }
        val minY = pos.values.minOf { it.y }; val maxY = pos.values.maxOf { it.y }
        val padL = 60.0; val padR = 220.0; val padT = 70.0; val padB = 60.0
        val scale = min(
            (width - padL - padR) / max(1.0, maxX - minX),
            (height - padT - padB) / max(1.0, maxY - minY),
        ).coerceIn(0.2, 3.0)
        val ox = (padL + width - padR) / 2 - (minX + maxX) / 2 * scale
        val oy = (padT + height - padB) / 2 - (minY + maxY) / 2 * scale
        fun sx(p: P) = (ox + p.x * scale).toInt()
        fun sy(p: P) = (oy + p.y * scale).toInt()

        val fg = if (dark) Color(0xE6, 0xE8, 0xEC) else Color(0x24, 0x26, 0x2A)
        val border = if (dark) Color(0x3A, 0x3D, 0x42) else Color(0xD0, 0xD3, 0xD8)
        val accent = if (dark) Color(0xE8, 0x8A, 0x6B) else Color(0xC2, 0x55, 0x2E)
        val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(5f, 5f), 0f)

        // Edges.
        for (e in graph.edges) {
            val a = pos[e.sourceNodeId] ?: continue
            val b = pos[e.targetNodeId] ?: continue
            val seq = e.type == ActivityEdgeType.SEQUENTIAL_ACTIVITY
            g.color = if (e.type == ActivityEdgeType.AFFECTED_BY) alpha(color(ActivityColorRole.ERROR, dark), 150) else alpha(border, 150)
            g.stroke = if (seq) dashed else BasicStroke((0.8f + min(3f, e.weight)).coerceAtMost(3f))
            g.drawLine(sx(a), sy(a), sx(b), sy(b))
        }
        g.stroke = BasicStroke(1f)

        // Nodes.
        for (n in graph.nodes.sortedBy { it.type == ActivityNodeType.CATEGORY }) {
            val p = pos[n.id] ?: continue
            val x = sx(p); val y = sy(p)
            val r = radius(n)
            val col = color(ActivityColorRoles.roleForNode(n), dark)
            if (n.type == ActivityNodeType.CATEGORY) {
                g.color = alpha(col, 60)
                g.fillOval(x - r, y - r, r * 2, r * 2)
                g.color = alpha(col, 160)
                g.drawOval(x - r, y - r, r * 2, r * 2)
            } else {
                g.color = col
                g.fillOval(x - r, y - r, r * 2, r * 2)
                g.color = alpha(if (dark) Color.BLACK else Color.WHITE, 120)
                g.drawOval(x - r, y - r, r * 2, r * 2)
            }
            if (n.type == ActivityNodeType.TASK) {
                g.color = accent; g.stroke = BasicStroke(2.5f)
                g.drawOval(x - r - 4, y - r - 4, (r + 4) * 2, (r + 4) * 2)
                g.stroke = BasicStroke(1f)
            }
            if (n.state == ActivityNodeState.FAILED) {
                g.color = color(ActivityColorRole.ERROR, dark); g.stroke = BasicStroke(2f)
                g.drawOval(x - r - 3, y - r - 3, (r + 3) * 2, (r + 3) * 2)
                g.stroke = BasicStroke(1f)
            }
            // Label with a semi-transparent pill so overlapping labels stay legible.
            g.font = Font(Font.SANS_SERIF, if (n.type == ActivityNodeType.CATEGORY) Font.BOLD else Font.PLAIN, if (n.type == ActivityNodeType.TASK) 15 else 12)
            val fm = g.fontMetrics
            val lx = x + r + 5; val ly = y + fm.ascent / 2 - 1
            val bg = if (dark) Color(0x1E, 0x1F, 0x22) else Color(0xF7, 0xF8, 0xFA)
            g.color = alpha(bg, 185)
            g.fillRoundRect(lx - 3, ly - fm.ascent, fm.stringWidth(n.label) + 6, fm.height, 7, 7)
            g.color = fg
            g.drawString(n.label, lx, ly)
        }

        // Title / focus banner.
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.color = accent
        g.drawString(title ?: "Agent Activity Map — ${graph.focus.verb} ${graph.focus.label}".trim(), 24, 34)
        g.font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
        g.color = if (dark) Color(0x9A, 0x9F, 0xA6) else Color(0x6A, 0x6E, 0x74)
        g.drawString("Observable activity only — not the model's private reasoning.", 24, 52)

        g.dispose()
        return img
    }

    private fun radius(n: ActivityNode): Int = when (n.type) {
        ActivityNodeType.TASK -> 17
        ActivityNodeType.CATEGORY -> 12
        else -> (8 + min(6.0, n.interactionCount * 0.7)).toInt()
    }

    private fun alpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    /** Fixed light/dark palette mirroring the Swing panel's JBColor roles. */
    private fun color(role: ActivityColorRole, dark: Boolean): Color = when (role) {
        ActivityColorRole.IDLE -> rgb(0x8A9099, 0x6A7075, dark)
        ActivityColorRole.DISCOVERED -> rgb(0x0A98AC, 0x33C6DA, dark)
        ActivityColorRole.READING -> rgb(0x2F6FEB, 0x5B9BFF, dark)
        ActivityColorRole.FOCUS -> rgb(0x8250DF, 0xB98BFF, dark)
        ActivityColorRole.EDITING -> rgb(0xC97A00, 0xF0B429, dark)
        ActivityColorRole.SUCCESS -> rgb(0x1F9D57, 0x54D98A, dark)
        ActivityColorRole.ERROR -> rgb(0xD1242F, 0xF66A63, dark)
        ActivityColorRole.WARNING -> rgb(0xB08500, 0xE6C34D, dark)
        ActivityColorRole.SUGGESTION -> rgb(0xC44FA0, 0xF07AC8, dark)
        ActivityColorRole.TASK -> rgb(0x3A3F45, 0xF2F4F8, dark)
    }

    private fun rgb(light: Int, dark: Int, isDark: Boolean) = Color(if (isDark) dark else light)

    /** Deterministic force-directed layout (same forces/constants family as the live panel). */
    private fun layout(graph: ActivityGraph, ids: List<String>, iterations: Int = 700, seed: Long = 42): Map<String, P> {
        val rnd = Random(seed)
        val pos = HashMap<String, P>()
        for (id in ids) {
            if (id == ActivityGraph.TASK_ID) pos[id] = P(0.0, 0.0)
            else pos[id] = P(rnd.nextDouble(-120.0, 120.0), rnd.nextDouble(-120.0, 120.0))
        }
        if (ids.size < 2) return pos
        val edges = graph.edges.filter { it.sourceNodeId in pos && it.targetNodeId in pos }
        val vel = HashMap<String, P>().apply { ids.forEach { this[it] = P(0.0, 0.0) } }
        repeat(iterations) {
            val force = HashMap<String, P>().apply { ids.forEach { this[it] = P(0.0, 0.0) } }
            for (i in ids.indices) {
                val a = pos[ids[i]]!!
                for (j in i + 1 until ids.size) {
                    val b = pos[ids[j]]!!
                    var dx = a.x - b.x; var dy = a.y - b.y
                    var d2 = dx * dx + dy * dy
                    if (d2 < 0.01) { dx = rnd.nextDouble(-1.0, 1.0); dy = rnd.nextDouble(-1.0, 1.0); d2 = 1.0 }
                    val d = Math.sqrt(d2); val rep = 6000.0 / d2
                    val fx = dx / d * rep; val fy = dy / d * rep
                    force[ids[i]]!!.x += fx; force[ids[i]]!!.y += fy
                    force[ids[j]]!!.x -= fx; force[ids[j]]!!.y -= fy
                }
            }
            for (e in edges) {
                val a = pos[e.sourceNodeId]!!; val b = pos[e.targetNodeId]!!
                val dx = b.x - a.x; val dy = b.y - a.y
                val d = max(0.01, hypot(dx, dy))
                val rest = if (e.type == ActivityEdgeType.CONTAINS) 130.0 else 95.0
                val f = 0.02 * (d - rest)
                val fx = dx / d * f; val fy = dy / d * f
                force[e.sourceNodeId]!!.x += fx; force[e.sourceNodeId]!!.y += fy
                force[e.targetNodeId]!!.x -= fx; force[e.targetNodeId]!!.y -= fy
            }
            for (id in ids) {
                if (id == ActivityGraph.TASK_ID) { pos[id]!!.x = 0.0; pos[id]!!.y = 0.0; continue }
                val p = pos[id]!!; val v = vel[id]!!; val fo = force[id]!!
                fo.x -= p.x * 0.012; fo.y -= p.y * 0.012
                v.x = (v.x + fo.x * 0.9) * 0.82; v.y = (v.y + fo.y * 0.9) * 0.82
                val sp = hypot(v.x, v.y); if (sp > 22) { v.x = v.x / sp * 22; v.y = v.y / sp * 22 }
                p.x += v.x * 0.9; p.y += v.y * 0.9
                val d = hypot(p.x, p.y)
                if (d in 0.001..52.0) { val s = 52.0 / d; p.x *= s; p.y *= s } else if (d <= 0.001) { p.x = 52.0 }
            }
        }
        return pos
    }
}
