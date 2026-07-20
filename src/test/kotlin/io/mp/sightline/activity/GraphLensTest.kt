package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GraphLensTest {

    private val now: Instant = Instant.parse("2026-07-20T10:00:00Z")

    private fun node(
        id: String,
        type: ActivityNodeType,
        state: ActivityNodeState = ActivityNodeState.IDLE,
    ) = ActivityNode(
        id = id,
        label = id,
        type = type,
        category = ActivityCategory.UNKNOWN,
        state = state,
        firstSeenAt = now,
        lastSeenAt = now,
    )

    private fun edge(id: String, from: String, to: String, type: ActivityEdgeType) =
        ActivityEdge(id = id, sourceNodeId = from, targetNodeId = to, type = type, lastActivatedAt = now)

    private val nodes = listOf(
        node("task:root", ActivityNodeType.TASK),
        node("cat:UI", ActivityNodeType.CATEGORY),
        node("file:Screen.kt", ActivityNodeType.FILE, ActivityNodeState.EDITING),
        node("file:Repo.kt", ActivityNodeType.FILE),
        node("test:RepoTest", ActivityNodeType.TEST),
        node("err:1", ActivityNodeType.ERROR),
        node("search:foo", ActivityNodeType.SEARCH),
    )

    private val edges = listOf(
        edge("e1", "cat:UI", "file:Screen.kt", ActivityEdgeType.CONTAINS),
        edge("e2", "file:Screen.kt", "file:Repo.kt", ActivityEdgeType.IMPORTS),
        edge("e3", "test:RepoTest", "file:Repo.kt", ActivityEdgeType.TESTS),
        edge("e4", "err:1", "file:Repo.kt", ActivityEdgeType.AFFECTED_BY),
        edge("e5", "file:Screen.kt", "file:Repo.kt", ActivityEdgeType.NAVIGATES_TO),
    )

    // ---- the gap this closes ----

    /**
     * The old filter was node-only, which cannot express "show me imports". A data-flow view is a
     * question about edges, and this is the assertion that proves the lens answers it.
     */
    @Test
    fun `a lens filters edges, not just nodes`() {
        val lens = GraphLens.DATA_FLOW
        val visible = lens.visibleNodes(nodes)
        val shown = lens.visibleEdges(edges, visible).map { it.type }

        assertTrue(ActivityEdgeType.IMPORTS in shown)
        assertFalse("a containment edge is not data flow", ActivityEdgeType.CONTAINS in shown)
        assertFalse(ActivityEdgeType.NAVIGATES_TO in shown)
    }

    @Test
    fun `everything shows everything`() {
        val visible = GraphLens.EVERYTHING.visibleNodes(nodes)
        assertEquals(nodes.size, visible.size)
        assertEquals(edges.size, GraphLens.EVERYTHING.visibleEdges(edges, visible).size)
    }

    /** An edge to a hidden node points at nothing and must not be drawn. */
    @Test
    fun `an edge whose endpoint is hidden is dropped`() {
        val lens = GraphLens.NAVIGATION
        val visible = lens.visibleNodes(nodes)
        val orphaned = edges + edge("e6", "file:Screen.kt", "search:foo", ActivityEdgeType.NAVIGATES_TO)
        assertTrue(lens.visibleEdges(orphaned, visible).none { it.id == "e6" })
    }

    // ---- the spine ----

    @Test
    fun `the category spine is kept or dropped per lens`() {
        assertTrue(GraphLens.EVERYTHING.visibleNodes(nodes).contains("cat:UI"))
        assertFalse(GraphLens.DATA_FLOW.visibleNodes(nodes).contains("cat:UI"))
        assertFalse(GraphLens.DATA_FLOW.visibleNodes(nodes).contains("task:root"))
    }

    // ---- the individual lenses ----

    @Test
    fun `navigation shows only navigation edges`() {
        val lens = GraphLens.NAVIGATION
        val shown = lens.visibleEdges(edges, lens.visibleNodes(nodes))
        assertEquals(1, shown.size)
        assertEquals(ActivityEdgeType.NAVIGATES_TO, shown.single().type)
    }

    @Test
    fun `test coverage shows tests and what they exercise`() {
        val lens = GraphLens.TEST_COVERAGE
        val visible = lens.visibleNodes(nodes)
        assertTrue(visible.contains("test:RepoTest"))
        assertFalse(visible.contains("err:1"))
        assertEquals(1, lens.visibleEdges(edges, visible).size)
    }

    @Test
    fun `problems shows errors and the files they affect`() {
        val lens = GraphLens.PROBLEMS
        val visible = lens.visibleNodes(nodes)
        assertTrue(visible.contains("err:1"))
        assertTrue(visible.contains("file:Repo.kt"))
        assertTrue(lens.visibleEdges(edges, visible).any { it.type == ActivityEdgeType.AFFECTED_BY })
    }

    @Test
    fun `changes shows only what was edited`() {
        val visible = GraphLens.CHANGES.visibleNodes(nodes)
        assertTrue(visible.contains("file:Screen.kt"))
        assertFalse("an untouched file is not a change", visible.contains("file:Repo.kt"))
    }

    // ---- the boundary ----

    /**
     * The standing decision in CLAUDE.md: naming may colour a node's cluster, but may never create an
     * edge. A lens selects from what the graph established — it has no way to construct a node or edge,
     * so it cannot breach that by accident.
     */
    @Test
    fun `a lens can only select, never invent`() {
        for (lens in GraphLens.ALL) {
            val visible = lens.visibleNodes(nodes)
            assertTrue("$lens invented a node", nodes.map { it.id }.containsAll(visible))

            val shown = lens.visibleEdges(edges, visible)
            assertTrue("$lens invented an edge", edges.containsAll(shown))
        }
    }

    @Test
    fun `every lens has a distinct id and a description`() {
        assertEquals(GraphLens.ALL.size, GraphLens.ALL.map { it.id }.distinct().size)
        assertTrue(GraphLens.ALL.all { it.description.isNotBlank() && it.label.isNotBlank() })
    }

    @Test
    fun `lenses resolve by id, and everything leads the picker`() {
        assertEquals(GraphLens.DATA_FLOW, GraphLens.byId("dataflow"))
        assertEquals(null, GraphLens.byId("nope"))
        assertEquals(GraphLens.EVERYTHING, GraphLens.ALL.first())
    }

    @Test
    fun `an empty graph yields empty results rather than throwing`() {
        for (lens in GraphLens.ALL) {
            assertTrue(lens.visibleNodes(emptyList()).isEmpty())
            assertTrue(lens.visibleEdges(emptyList(), emptySet()).isEmpty())
        }
    }
}
