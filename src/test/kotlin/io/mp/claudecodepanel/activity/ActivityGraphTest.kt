package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ActivityGraphTest {

    private var t = Instant.parse("2026-07-19T10:00:00Z")
    private fun tick(): Instant { t = t.plusSeconds(1); return t }

    private fun freshGraph(max: Int = 500): ActivityGraph {
        val g = ActivityGraph(maxRetained = max) { t }
        g.apply(TaskStarted("Improve the login flow", tick()))
        return g
    }

    @Test fun taskStartCreatesCentralNode() {
        val g = freshGraph()
        val task = g.node(ActivityGraph.TASK_ID)
        assertNotNull(task)
        assertEquals(ActivityNodeType.TASK, task!!.type)
        assertEquals("Starting", g.focus.verb)
    }

    @Test fun fileReadLightsUpNodeInCluster() {
        val g = freshGraph()
        g.apply(FileRead("/app/ui/LoginScreen.kt", tick()))
        val file = g.node("file:/app/ui/LoginScreen.kt")
        assertNotNull(file)
        assertEquals(ActivityNodeState.READING, file!!.state)
        assertEquals(ActivityCategory.UI_COMPOSE, file.category)
        assertNotNull(g.node("cat:UI_COMPOSE"))
        assertEquals("Reading", g.focus.verb)
    }

    @Test fun repeatedReadDeduplicatesAndCountsInteractions() {
        val g = freshGraph()
        g.apply(FileRead("/a/Foo.kt", tick()))
        g.apply(FileRead("/a/Foo.kt", tick()))
        val nodes = g.nodes.filter { it.id == "file:/a/Foo.kt" }
        assertEquals(1, nodes.size)
        assertEquals(2, nodes[0].interactionCount)
    }

    @Test fun readThenEditTransitionsState() {
        val g = freshGraph()
        g.apply(FileRead("/a/Foo.kt", tick()))
        g.apply(FileEdited("/a/Foo.kt", created = false, at = tick()))
        assertEquals(ActivityNodeState.EDITING, g.node("file:/a/Foo.kt")!!.state)
    }

    @Test fun editCreatesPatchNodeAndEdge() {
        val g = freshGraph()
        g.apply(FileEdited("/a/Foo.kt", created = false, at = tick()))
        assertNotNull(g.node(ActivityGraph.PATCH_ID))
        val edge = g.edges.firstOrNull {
            it.sourceNodeId == ActivityGraph.PATCH_ID && it.targetNodeId == "file:/a/Foo.kt" &&
                it.type == ActivityEdgeType.GENERATED_FROM
        }
        assertNotNull(edge)
    }

    @Test fun confidenceTakesTheMaximum() {
        val g = freshGraph()
        g.apply(FileRead("/a/Foo.kt", tick(), confidence = 0.3f))
        val low = g.node("file:/a/Foo.kt")!!.confidence
        g.apply(FileRead("/a/Foo.kt", tick(), confidence = 1f))
        val high = g.node("file:/a/Foo.kt")!!.confidence
        assertTrue(high >= low)
        assertEquals(1f, high, 0.0001f)
    }

    @Test fun errorConnectsToTouchedFile() {
        val g = freshGraph()
        g.apply(FileEdited("/a/Foo.kt", created = false, at = tick()))
        g.apply(ErrorObserved("/a/Foo.kt", "unresolved reference", tick()))
        assertTrue(g.hasActiveError("file:/a/Foo.kt"))
        val edge = g.edges.firstOrNull { it.type == ActivityEdgeType.AFFECTED_BY && it.targetNodeId == "file:/a/Foo.kt" }
        assertNotNull(edge)
    }

    @Test fun testReportMarksSuiteAndFailedNodes() {
        val g = freshGraph()
        g.apply(TestReported(passed = 0, failed = 1, failedNames = listOf("FooTest > loads"), at = tick()))
        assertTrue(g.nodes.any { it.type == ActivityNodeType.TEST && it.state == ActivityNodeState.FAILED })
    }

    @Test fun sequentialTrailEdgeCreated() {
        val g = freshGraph()
        g.apply(FileRead("/a/A.kt", tick()))
        g.apply(FileRead("/a/B.kt", tick()))
        val edge = g.edges.firstOrNull { it.type == ActivityEdgeType.SEQUENTIAL_ACTIVITY }
        assertNotNull(edge)
    }

    @Test fun statusVerbDoesNotCarryLastFileLabel() {
        val g = freshGraph()
        g.apply(FileRead("/a/Foo.kt", tick()))
        assertEquals("Foo.kt", g.focus.label)
        g.apply(StatusUpdated("Thinking", null, tick()))
        assertEquals("Thinking", g.focus.verb)
        assertTrue(g.focus.label.isBlank())
    }

    @Test fun clearResetsEverything() {
        val g = freshGraph()
        g.apply(FileRead("/a/Foo.kt", tick()))
        g.clear()
        assertTrue(g.nodes.isEmpty())
        assertTrue(g.timeline.isEmpty())
        assertNull(g.focus.nodeId)
    }

    @Test fun retentionCapEvictsOldestNonStructuralNodes() {
        val g = freshGraph(max = 8)
        for (i in 0 until 20) g.apply(FileRead("/p/n$i.data", tick()))
        assertEquals(8, g.nodes.size)
        assertNotNull(g.node(ActivityGraph.TASK_ID))          // task kept
        assertNotNull(g.node("cat:UNKNOWN"))                  // structural cluster kept
        assertNotNull(g.node("file:/p/n19.data"))             // newest kept
        assertNull(g.node("file:/p/n0.data"))                 // oldest evicted
    }

    @Test fun pinnedNodesSurviveEviction() {
        val g = freshGraph(max = 6)
        g.apply(FileRead("/p/keep.data", tick()))
        g.setPinned("file:/p/keep.data", true)
        for (i in 0 until 20) g.apply(FileRead("/p/x$i.data", tick()))
        assertNotNull(g.node("file:/p/keep.data"))
    }

    @Test fun taskCompletePreservesEditsButCompletesTransient() {
        val g = freshGraph()
        g.apply(FileEdited("/a/Edited.kt", created = false, at = tick()))
        g.apply(FileRead("/a/Read.kt", tick()))
        g.apply(TaskCompleted("done", isError = false, at = tick()))
        assertEquals(ActivityNodeState.EDITING, g.node("file:/a/Edited.kt")!!.state)
        assertEquals(ActivityNodeState.COMPLETED, g.node("file:/a/Read.kt")!!.state)
        assertFalse(g.timeline.isEmpty())
    }
}
