package io.mp.sightline.ui.markdown.mermaid

/**
 * Platform-free model of the mermaid subset this plugin renders **natively** (no JCEF/browser exists in
 * this IDE's JBR, so mermaid.js cannot run — see CLAUDE.md "No JCEF"). We draw the flowchart family —
 * `graph`/`flowchart` and `stateDiagram` — which is exactly the "process flow / decision tree / state
 * machine" shape mermaid is asked for most; every other diagram type degrades to a labelled code block.
 *
 * A diagram is a plain node/edge graph. Parsing ([MermaidParser]) and layout ([MermaidLayout]) are
 * separate platform-free, unit-tested units; the Swing half is `BlockRenderer`'s mermaid component.
 */

/** `graph TD` == `TB`. Layout flows along this axis. */
enum class MermaidDirection { TB, BT, LR, RL }

/** Node outline. Encodes the author's intent (a rhombus is a decision); the renderer draws each. */
enum class MermaidShape { RECT, ROUNDED, STADIUM, SUBROUTINE, CIRCLE, DOUBLE_CIRCLE, RHOMBUS, HEXAGON }

data class MermaidNode(val id: String, val label: String, val shape: MermaidShape)

/** Line style of an edge; [arrow] is orthogonal (a dotted line may or may not carry an arrowhead). */
enum class MermaidEdgeStyle { SOLID, DOTTED, THICK }

data class MermaidEdge(
    val from: String,
    val to: String,
    val label: String?,
    val style: MermaidEdgeStyle = MermaidEdgeStyle.SOLID,
    val arrow: Boolean = true,
)

data class MermaidDiagram(
    val direction: MermaidDirection,
    /** In first-seen order — layout preserves it for stable, deterministic placement. */
    val nodes: List<MermaidNode>,
    val edges: List<MermaidEdge>,
) {
    fun node(id: String): MermaidNode? = nodes.firstOrNull { it.id == id }
}

/** The outcome of parsing a ```mermaid fence — the caller renders each case differently. */
sealed interface MermaidParse {
    /** A flowchart/state diagram we draw natively. */
    data class Rendered(val diagram: MermaidDiagram) : MermaidParse

    /** A real mermaid diagram type we don't draw yet (sequence, class, ER, gantt, pie, …) → show as code. */
    data class Unsupported(val type: String) : MermaidParse

    /** Looked like a supported type but yielded nothing drawable → show as code, honestly. */
    data class Failed(val reason: String) : MermaidParse
}
