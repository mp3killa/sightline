package io.mp.sightline.ui.markdown.mermaid

/**
 * A layered ("Sugiyama-lite") layout for a [MermaidDiagram]: nodes are ranked by longest path from the
 * roots, laid out rank-by-rank along the diagram's direction, and packed/centred on the cross axis.
 *
 * Platform-free and deterministic. Node **sizes are injected** ([size]) — the Swing renderer measures
 * labels with real `FontMetrics`, while tests pass fixed sizes — so the geometry is unit-tested with no
 * toolkit. Edge geometry is *not* computed here: the renderer clips a centre-to-centre line to each
 * node's box, which keeps this a pure placement problem. Ranking is capped at the node count so a cyclic
 * diagram (state machines loop) terminates instead of diverging.
 */
object MermaidLayout {

    data class Size(val w: Double, val h: Double)

    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
        val cx get() = x + w / 2
        val cy get() = y + h / 2
    }

    data class Result(val nodes: Map<String, Rect>, val width: Double, val height: Double)

    /** Gap between ranks (main axis) is larger than within a rank, to leave room for the arrows. */
    const val MAIN_GAP = 46.0
    const val CROSS_GAP = 30.0

    fun layout(diagram: MermaidDiagram, size: (MermaidNode) -> Size): Result {
        if (diagram.nodes.isEmpty()) return Result(emptyMap(), 0.0, 0.0)
        val rank = ranks(diagram)
        val sizes = diagram.nodes.associate { it.id to size(it) }
        val vertical = diagram.direction == MermaidDirection.TB || diagram.direction == MermaidDirection.BT
        fun main(id: String) = if (vertical) sizes.getValue(id).h else sizes.getValue(id).w
        fun cross(id: String) = if (vertical) sizes.getValue(id).w else sizes.getValue(id).h

        // Group ids by rank, preserving first-seen order within each rank for a stable, deterministic layout.
        val byRank = LinkedHashMap<Int, MutableList<String>>()
        for (n in diagram.nodes) byRank.getOrPut(rank.getValue(n.id)) { ArrayList() }.add(n.id)
        val maxRank = byRank.keys.max()

        val rankMainExtent = (0..maxRank).map { r -> byRank[r]?.maxOfOrNull { main(it) } ?: 0.0 }
        val rankMainOffset = DoubleArray(maxRank + 1)
        var acc = 0.0
        for (r in 0..maxRank) { rankMainOffset[r] = acc; acc += rankMainExtent[r] + MAIN_GAP }
        val totalMain = (acc - MAIN_GAP).coerceAtLeast(0.0)

        val rankCrossWidth = (0..maxRank).map { r ->
            val ids = byRank[r].orEmpty()
            if (ids.isEmpty()) 0.0 else ids.sumOf { cross(it) } + CROSS_GAP * (ids.size - 1)
        }
        val totalCross = rankCrossWidth.maxOrNull() ?: 0.0

        val rects = HashMap<String, Rect>()
        for (r in 0..maxRank) {
            val ids = byRank[r] ?: continue
            var cursor = (totalCross - rankCrossWidth[r]) / 2.0 // centre this rank against the widest
            for (id in ids) {
                val crossCenter = cursor + cross(id) / 2.0
                val mainPos = rankMainOffset[r] + (rankMainExtent[r] - main(id)) / 2.0 // centre in the band
                rects[id] = toXY(diagram.direction, mainPos, crossCenter, sizes.getValue(id), totalMain)
                cursor += cross(id) + CROSS_GAP
            }
        }
        val width = if (vertical) totalCross else totalMain
        val height = if (vertical) totalMain else totalCross
        return Result(rects, width, height)
    }

    /** Maps a (main-axis leading edge, cross-axis centre) position to a top-left `x,y` rect. */
    private fun toXY(dir: MermaidDirection, mainPos: Double, crossCenter: Double, sz: Size, totalMain: Double): Rect =
        when (dir) {
            MermaidDirection.TB -> Rect(crossCenter - sz.w / 2, mainPos, sz.w, sz.h)
            MermaidDirection.BT -> Rect(crossCenter - sz.w / 2, totalMain - mainPos - sz.h, sz.w, sz.h)
            MermaidDirection.LR -> Rect(mainPos, crossCenter - sz.h / 2, sz.w, sz.h)
            MermaidDirection.RL -> Rect(totalMain - mainPos - sz.w, crossCenter - sz.h / 2, sz.w, sz.h)
        }

    /**
     * Longest-path ranks over the diagram with **cycles broken**: a state machine loops (`Error --> Idle`),
     * and ranking over those back edges would push looping nodes ever further down into one absurdly tall
     * chain. So a DFS first classifies edges — an edge to a node currently on the stack is a *back edge* —
     * and ranks are computed over the forward edges only (the back edge still *draws*, it just doesn't
     * stretch the layout). A root sits at rank 0; every forward edge pushes its target one rank past its
     * source.
     */
    private fun ranks(diagram: MermaidDiagram): Map<String, Int> {
        val ids = diagram.nodes.map { it.id }.toHashSet()
        val adj = LinkedHashMap<String, MutableList<String>>()
        for (e in diagram.edges) if (e.from in ids && e.to in ids) adj.getOrPut(e.from) { ArrayList() }.add(e.to)

        val state = HashMap<String, Int>() // 0 unvisited, 1 on-stack, 2 done
        val back = HashSet<Pair<String, String>>()
        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, Int>>() // (node, next child index)
            stack.addLast(start to 0); state[start] = 1
            while (stack.isNotEmpty()) {
                val (u, i) = stack.last()
                val children = adj[u].orEmpty()
                if (i < children.size) {
                    stack[stack.lastIndex] = u to i + 1
                    val v = children[i]
                    when (state[v] ?: 0) {
                        1 -> back.add(u to v) // edge into a node still on the stack → back edge
                        0 -> { state[v] = 1; stack.addLast(v to 0) }
                    }
                } else { state[u] = 2; stack.removeLast() }
            }
        }
        for (n in diagram.nodes) if ((state[n.id] ?: 0) == 0) dfs(n.id)

        val forward = diagram.edges.filter { it.from in ids && it.to in ids && (it.from to it.to) !in back }
        val rank = HashMap<String, Int>()
        diagram.nodes.forEach { rank[it.id] = 0 }
        repeat(diagram.nodes.size) {
            var changed = false
            for (e in forward) {
                val candidate = rank.getValue(e.from) + 1
                if (rank.getValue(e.to) < candidate) { rank[e.to] = candidate; changed = true }
            }
            if (!changed) return rank
        }
        return rank
    }
}
