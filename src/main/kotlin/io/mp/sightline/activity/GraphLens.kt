package io.mp.sightline.activity

/**
 * A way of looking at the activity graph: which nodes, and — the part that was missing — which **edges**.
 *
 * The previous filter lived as a private enum inside `ui/ActivityMapPanel` and filtered nodes only. That
 * is enough for "show me errors", but not for the question a graph mode actually answers: *"how does an
 * API response become UI state?"* is a question about `IMPORTS` and `PRODUCED` edges, and no amount of
 * node filtering expresses it. Lifting the concept here also makes it testable, and usable by
 * `ActivityMapRenderer` for the headless previews — which the private version was not.
 *
 * ### The boundary this must respect
 *
 * A lens **selects** from what the graph already established; it never adds. That matters because the
 * standing decision in CLAUDE.md — *naming may colour a node's cluster, but may never create an edge* —
 * is easy to breach here by accident: a "screen architecture" lens that drew ViewModel→Repository lines
 * because the names looked right would be exactly the unevidenced inference that was rejected. So a lens
 * is a pair of predicates over existing nodes and edges, and has no way to construct either.
 */
data class GraphLens(
    val id: String,
    val label: String,
    /** One line explaining what this view answers, for the mode picker. */
    val description: String,
    val nodePredicate: (ActivityNode) -> Boolean,
    val edgePredicate: (ActivityEdge) -> Boolean,
    /**
     * Whether the `task → category → node` spine stays visible. Off for a focused lens, where the
     * cluster hubs are scaffolding that crowds out the relationships being examined.
     */
    val keepCategorySpine: Boolean = true,
) {
    /**
     * Nodes this lens shows. Category and task nodes pass when [keepCategorySpine], so the graph keeps a
     * connected shape rather than scattering into islands.
     */
    fun visibleNodes(nodes: Collection<ActivityNode>): Set<String> = nodes
        .filter { n ->
            when (n.type) {
                ActivityNodeType.CATEGORY, ActivityNodeType.TASK -> keepCategorySpine
                else -> nodePredicate(n)
            }
        }
        .map { it.id }
        .toSet()

    /** Edges this lens shows — both ends must be visible, or the edge points at nothing. */
    fun visibleEdges(edges: Collection<ActivityEdge>, visibleNodeIds: Set<String>): List<ActivityEdge> =
        edges.filter { e -> edgePredicate(e) && e.sourceNodeId in visibleNodeIds && e.targetNodeId in visibleNodeIds }

    companion object {

        private val ALL_NODES: (ActivityNode) -> Boolean = { true }
        private val ALL_EDGES: (ActivityEdge) -> Boolean = { true }

        val EVERYTHING = GraphLens(
            "all", "Everything", "Every node and edge observed this session",
            ALL_NODES, ALL_EDGES,
        )

        /**
         * How data moves: what imports what, what a command produced, what a test covers. The edge
         * predicate is the whole lens — these node types appear in every other view too.
         */
        val DATA_FLOW = GraphLens(
            "dataflow", "Data flow", "Imports, structural links and what produced what",
            nodePredicate = { it.type != ActivityNodeType.SEARCH && it.type != ActivityNodeType.WEB },
            edgePredicate = {
                it.type in setOf(
                    ActivityEdgeType.IMPORTS, ActivityEdgeType.PRODUCED, ActivityEdgeType.EXTENDS,
                    ActivityEdgeType.IMPLEMENTS, ActivityEdgeType.REFERENCES, ActivityEdgeType.CALLS,
                )
            },
            keepCategorySpine = false,
        )

        /** Where navigation goes. Small by construction, which is what makes it readable. */
        val NAVIGATION = GraphLens(
            "navigation", "Navigation", "Screens and the destinations they lead to",
            nodePredicate = {
                it.type in setOf(
                    ActivityNodeType.COMPOSABLE, ActivityNodeType.CLASS, ActivityNodeType.FILE,
                )
            },
            edgePredicate = { it.type == ActivityEdgeType.NAVIGATES_TO },
            keepCategorySpine = false,
        )

        /** What is covered, and — by their absence — what is not. */
        val TEST_COVERAGE = GraphLens(
            "tests", "Test coverage", "Tests and the code they exercise",
            nodePredicate = {
                it.type == ActivityNodeType.TEST || it.type == ActivityNodeType.FILE ||
                    it.type == ActivityNodeType.CLASS
            },
            edgePredicate = { it.type == ActivityEdgeType.TESTS || it.type == ActivityEdgeType.TESTED_BY },
            keepCategorySpine = false,
        )

        /** Everything that went wrong, and the files it landed on. */
        val PROBLEMS = GraphLens(
            "problems", "Problems", "Errors and warnings, and the files they affect",
            nodePredicate = {
                it.type == ActivityNodeType.ERROR || it.type == ActivityNodeType.WARNING ||
                    it.state == ActivityNodeState.FAILED || it.type == ActivityNodeType.FILE
            },
            edgePredicate = { it.type == ActivityEdgeType.AFFECTED_BY || it.type == ActivityEdgeType.PRODUCED },
            keepCategorySpine = false,
        )

        /** What Claude actually changed — the review view. */
        val CHANGES = GraphLens(
            "changes", "Changes", "Files edited or created this session",
            nodePredicate = {
                it.state in setOf(
                    ActivityNodeState.EDITING, ActivityNodeState.CREATED, ActivityNodeState.DELETED,
                ) || it.type == ActivityNodeType.PATCH
            },
            edgePredicate = { it.type == ActivityEdgeType.GENERATED_FROM || it.type == ActivityEdgeType.EDITS },
        )

        /** In picker order. `EVERYTHING` leads because it is the default and the way back. */
        val ALL: List<GraphLens> = listOf(EVERYTHING, CHANGES, PROBLEMS, DATA_FLOW, NAVIGATION, TEST_COVERAGE)

        fun byId(id: String): GraphLens? = ALL.firstOrNull { it.id == id }
    }
}
