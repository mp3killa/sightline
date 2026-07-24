package io.mp.sightline.ui.markdown.mermaid

/**
 * Parses the mermaid subset we render — `graph`/`flowchart` and `stateDiagram(-v2)` — into a
 * [MermaidDiagram]. Everything else returns [MermaidParse.Unsupported] so the caller shows the source as
 * a code block rather than a wrong picture; a supported header that yields nothing drawable returns
 * [MermaidParse.Failed]. Unrecognised *lines* inside a supported diagram are skipped, not fatal, so a
 * mostly-valid diagram still renders (an honest partial beats an all-or-nothing failure).
 *
 * Deliberately not a full mermaid grammar: it covers the node shapes and edge forms Claude actually
 * emits for process flows. Platform-free and unit-tested.
 */
object MermaidParser {

    fun parse(source: String): MermaidParse {
        val lines = source.lineSequence()
            .map { stripComment(it).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("%%") }
            .toList()
        val header = lines.firstOrNull() ?: return MermaidParse.Failed("empty diagram")
        val firstWord = header.substringBefore(' ').substringBefore('\t').trim().lowercase()
        return when {
            firstWord == "graph" || firstWord == "flowchart" -> parseFlowchart(header, lines.drop(1))
            firstWord.startsWith("statediagram") -> parseState(lines.drop(1))
            else -> MermaidParse.Unsupported(firstWord)
        }
    }

    // ---- flowchart ----

    private fun parseFlowchart(header: String, body: List<String>): MermaidParse {
        val dir = directionFrom(header.substringAfter(' ', "").trim()) ?: MermaidDirection.TB
        val nodes = LinkedHashMap<String, MermaidNode>()
        val edges = ArrayList<MermaidEdge>()
        for (raw in body) {
            for (stmt in raw.split(';')) {
                val s = stmt.trim()
                if (s.isEmpty()) continue
                if (s.startsWith("subgraph") || s == "end" || s.startsWith("direction") ||
                    s.startsWith("classDef") || s.startsWith("class ") || s.startsWith("style ") ||
                    s.startsWith("linkStyle") || s.startsWith("click ")
                ) continue // grouping/styling we don't draw — skip, keep the nodes flat
                parseFlowStatement(s, nodes, edges)
            }
        }
        if (nodes.isEmpty()) return MermaidParse.Failed("no nodes recognised")
        return MermaidParse.Rendered(MermaidDiagram(dir, nodes.values.toList(), edges))
    }

    /** A chain of `node (connector node)*`, e.g. `A[Start] -->|yes| B{OK?} --> C`. */
    private fun parseFlowStatement(stmt: String, nodes: LinkedHashMap<String, MermaidNode>, edges: MutableList<MermaidEdge>) {
        var pos = 0
        var prev: String? = null
        var pendingConnector: Connector? = null
        while (pos < stmt.length) {
            val ws = WS.matchAt(stmt, pos); if (ws != null) { pos = ws.range.last + 1 }
            if (pos >= stmt.length) break

            val conn = readConnector(stmt, pos)
            if (conn != null) { pendingConnector = conn.first; pos = conn.second; continue }

            val nm = NODE.matchAt(stmt, pos)
            if (nm != null && nm.groupValues[1].isNotEmpty()) {
                val id = nm.groupValues[1]
                val bracket = nm.groups[2]?.value
                val node = nodeFrom(id, bracket)
                nodes.putIfAbsent(id, node)
                // Re-labelling: `A[Full]` after a bare `A` upgrades the stored label.
                if (bracket != null) nodes[id] = node
                val c = pendingConnector
                if (prev != null && c != null) {
                    edges.add(MermaidEdge(prev, id, c.label, c.style, c.arrow))
                }
                prev = id; pendingConnector = null
                pos = nm.range.last + 1
                continue
            }
            pos++ // unrecognised char — advance so we never spin
        }
    }

    private fun nodeFrom(id: String, bracket: String?): MermaidNode {
        if (bracket == null) return MermaidNode(id, id, MermaidShape.RECT)
        val (shape, inner) = when {
            bracket.startsWith("[[") -> MermaidShape.SUBROUTINE to bracket.removeSurrounding("[[", "]]")
            bracket.startsWith("((") -> MermaidShape.DOUBLE_CIRCLE to bracket.removeSurrounding("((", "))")
            bracket.startsWith("([") -> MermaidShape.STADIUM to bracket.removeSurrounding("([", "])")
            bracket.startsWith("{{") -> MermaidShape.HEXAGON to bracket.removeSurrounding("{{", "}}")
            bracket.startsWith("[") -> MermaidShape.RECT to bracket.removeSurrounding("[", "]")
            bracket.startsWith("(") -> MermaidShape.ROUNDED to bracket.removeSurrounding("(", ")")
            bracket.startsWith("{") -> MermaidShape.RHOMBUS to bracket.removeSurrounding("{", "}")
            bracket.startsWith(">") -> MermaidShape.RECT to bracket.removePrefix(">").removeSuffix("]")
            else -> MermaidShape.RECT to bracket
        }
        return MermaidNode(id, cleanLabel(inner), shape)
    }

    // ---- state diagram ----

    private const val START = "__start__"
    private const val END = "__end__"

    private fun parseState(body: List<String>): MermaidParse {
        val nodes = LinkedHashMap<String, MermaidNode>()
        val edges = ArrayList<MermaidEdge>()
        var dir = MermaidDirection.TB
        for (raw in body) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("direction")) { directionFrom(s.substringAfter(' ', "").trim())?.let { dir = it }; continue }
            val m = STATE_EDGE.matchEntire(s)
            if (m != null) {
                val fromId = stateNode(m.groupValues[1], nodes, source = true)
                val toId = stateNode(m.groupValues[2], nodes, source = false)
                val label = m.groups[3]?.value?.let { cleanLabel(it) }?.takeIf { it.isNotBlank() }
                edges.add(MermaidEdge(fromId, toId, label))
                continue
            }
            // `state "Human label" as id` or a bare `id` declaration.
            val alias = STATE_ALIAS.matchEntire(s)
            if (alias != null) {
                val id = alias.groupValues[2]
                nodes[id] = MermaidNode(id, cleanLabel(alias.groupValues[1]), MermaidShape.ROUNDED)
                continue
            }
            if (BARE_ID.matches(s)) nodes.putIfAbsent(s, MermaidNode(s, s, MermaidShape.ROUNDED))
        }
        if (nodes.isEmpty()) return MermaidParse.Failed("no states recognised")
        return MermaidParse.Rendered(MermaidDiagram(dir, nodes.values.toList(), edges))
    }

    /** Registers a state node, mapping the `[*]` pseudostate to a start (as source) or end (as target). */
    private fun stateNode(token: String, nodes: LinkedHashMap<String, MermaidNode>, source: Boolean): String {
        if (token == "[*]") {
            val id = if (source) START else END
            nodes.putIfAbsent(id, MermaidNode(id, "", if (source) MermaidShape.CIRCLE else MermaidShape.DOUBLE_CIRCLE))
            return id
        }
        nodes.putIfAbsent(token, MermaidNode(token, token, MermaidShape.ROUNDED))
        return token
    }

    // ---- connectors ----

    private data class Connector(val style: MermaidEdgeStyle, val arrow: Boolean, val label: String?)

    /** Reads an edge connector at [pos]; returns (connector, newPos) or null. Tries the inline-label
     *  form (`-- yes -->`) before the plain/piped forms (`-->`, `-->|yes|`). */
    private fun readConnector(s: String, pos: Int): Pair<Connector, Int>? {
        INLINE_EDGE.matchAt(s, pos)?.let { m ->
            val end = m.groupValues[3]
            return Connector(styleOf(m.groupValues[1] + end), end.endsWith(">"), cleanLabel(m.groupValues[2])) to (m.range.last + 1)
        }
        PLAIN_EDGE.matchAt(s, pos)?.let { m ->
            val core = m.groupValues[1]
            val label = m.groups[2]?.value?.let { cleanLabel(it) }?.takeIf { it.isNotBlank() }
            return Connector(styleOf(core), core.endsWith(">"), label) to (m.range.last + 1)
        }
        return null
    }

    private fun styleOf(connector: String): MermaidEdgeStyle = when {
        connector.contains('.') -> MermaidEdgeStyle.DOTTED
        connector.contains('=') -> MermaidEdgeStyle.THICK
        else -> MermaidEdgeStyle.SOLID
    }

    // ---- helpers ----

    private fun directionFrom(s: String): MermaidDirection? = when (s.trim().uppercase()) {
        "TD", "TB" -> MermaidDirection.TB
        "BT" -> MermaidDirection.BT
        "LR" -> MermaidDirection.LR
        "RL" -> MermaidDirection.RL
        else -> null
    }

    private fun stripComment(line: String): String {
        val i = line.indexOf("%%")
        return if (i >= 0) line.substring(0, i) else line
    }

    private fun cleanLabel(raw: String): String {
        var t = raw.trim()
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length >= 2) t = t.substring(1, t.length - 1)
        }
        // Mermaid allows <br> as a hard line break inside a label.
        return t.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").trim()
    }

    private val WS = Regex("""\s+""")
    private val NODE = Regex("""([A-Za-z0-9_]+)\s*(\[\[[^\]]*]]|\(\([^)]*\)\)|\(\[[^\]]*]\)|\{\{[^}]*}}|\[[^\]]*]|\([^)]*\)|\{[^}]*}|>[^\]]*])?""")
    private val BARE_ID = Regex("""[A-Za-z0-9_]+""")
    // Plain / piped: -->  ---  -.->  -.-  ==>  ===  ----> etc., optional trailing |label|.
    private val PLAIN_EDGE = Regex("""<?(-\.-+>?|-{2,}>?|={2,}>?|-\.-+|~{3,})(?:\s*\|([^|]*)\|)?""")
    // Inline label: -- text -->   == text ==>   -. text .->  (the opening run must NOT already be an
    // arrow, or `A --> B --> C` would read "B" as a label).
    private val INLINE_EDGE = Regex("""(-\.|-{2,}|={2,})\s+([^|>\n][^\n]*?)\s+(-\.->|-{2,}>|={2,}>|-{2,}|={2,})""")
    private val STATE_EDGE = Regex("""(\[\*]|[A-Za-z0-9_]+)\s*-{2,}>\s*(\[\*]|[A-Za-z0-9_]+)(?:\s*:\s*(.*))?""")
    private val STATE_ALIAS = Regex("""state\s+"([^"]*)"\s+as\s+([A-Za-z0-9_]+)""")
}
