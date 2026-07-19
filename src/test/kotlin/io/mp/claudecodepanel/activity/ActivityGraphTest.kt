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

    @Test fun deniedEditIsBlockedAndDropsPatchEdge() {
        val g = freshGraph()
        g.apply(FileEdited("/a/Foo.kt", created = false, at = tick()))
        // sanity: the optimistic patch edge exists before denial
        assertTrue(g.edges.any {
            it.targetNodeId == "file:/a/Foo.kt" && it.type == ActivityEdgeType.GENERATED_FROM
        })
        g.apply(ActivityDenied(toolUseId = "toolu_1", toolName = "Edit", path = "/a/Foo.kt", command = null, at = tick()))
        val file = g.node("file:/a/Foo.kt")!!
        assertEquals(ActivityNodeState.DENIED, file.state)
        // the file must no longer read as modified: no generated-patch edge survives
        assertFalse(g.edges.any {
            it.targetNodeId == "file:/a/Foo.kt" && it.type == ActivityEdgeType.GENERATED_FROM
        })
        assertEquals("Denied", g.focus.verb)
    }

    @Test fun deniedCommandIsBlocked() {
        val g = freshGraph()
        g.apply(CommandRun("rm -rf build", "clean", tick()))
        g.apply(ActivityDenied("toolu_2", "Bash", path = null, command = "rm -rf build", at = tick()))
        val cmd = g.nodes.firstOrNull { it.type == ActivityNodeType.COMMAND }
        assertNotNull(cmd)
        assertEquals(ActivityNodeState.DENIED, cmd!!.state)
    }

    @Test fun deniedUnknownToolIsFocusOnlyNotAnError() {
        val g = freshGraph()
        val primary = g.apply(ActivityDenied("toolu_3", "SomeTool", path = null, command = null, at = tick()))
        assertNull(primary)
        assertEquals("Denied", g.focus.verb)
    }

    @Test fun cancelledUsesCancelledState() {
        val g = freshGraph()
        g.apply(FileEdited("/a/Foo.kt", created = false, at = tick()))
        g.apply(ActivityDenied("toolu_4", "Edit", path = "/a/Foo.kt", command = null, cancelled = true, at = tick()))
        assertEquals(ActivityNodeState.CANCELLED, g.node("file:/a/Foo.kt")!!.state)
    }

    @Test fun commandLinksToBuildOutcomeItProduced() {
        val g = freshGraph()
        g.apply(GradleTaskRun("assembleDebug", tick()))
        g.apply(BuildReported(success = true, summary = "BUILD SUCCESSFUL", at = tick()))
        assertTrue(g.edges.any {
            it.sourceNodeId == "gradle:assembleDebug" && it.targetNodeId == "gradle:build" &&
                it.type == ActivityEdgeType.PRODUCED
        })
    }

    @Test fun commandLinksToDiagnosticItProduced() {
        val g = freshGraph()
        g.apply(CommandRun("detekt", "static analysis", tick()))
        g.apply(WarningObserved("/app/App.kt", "magic number", tick()))
        val cmdId = g.nodes.first { it.type == ActivityNodeType.COMMAND }.id
        val warnId = g.nodes.first { it.type == ActivityNodeType.WARNING }.id
        assertTrue(g.edges.any {
            it.sourceNodeId == cmdId && it.targetNodeId == warnId && it.type == ActivityEdgeType.PRODUCED
        })
    }

    @Test fun interleavedReadDoesNotBreakProducedLink() {
        // Only command-type events move the "active command"; an interleaved read must not steal it.
        val g = freshGraph()
        g.apply(CommandRun("./gradlew test", "run tests", tick()))
        g.apply(FileRead("/app/Foo.kt", tick()))
        g.apply(ErrorObserved("/app/Foo.kt", "boom", tick()))
        val cmdId = g.nodes.first { it.type == ActivityNodeType.COMMAND }.id
        val errId = g.nodes.first { it.type == ActivityNodeType.ERROR }.id
        assertTrue(g.edges.any {
            it.sourceNodeId == cmdId && it.targetNodeId == errId && it.type == ActivityEdgeType.PRODUCED
        })
    }

    @Test fun structuralImportLinksTouchedFileToResolvedTarget() {
        val g = freshGraph()
        g.apply(FileEdited("/app/ui/LoginViewModel.kt", created = false, at = tick()))
        g.apply(StructuralRelation("/app/ui/LoginViewModel.kt", "/app/data/UserRepository.kt", "UserRepository",
            StructuralRelationKind.IMPORTS, tick()))
        val edge = g.edges.firstOrNull {
            it.sourceNodeId == "file:/app/ui/LoginViewModel.kt" &&
                it.targetNodeId == "file:/app/data/UserRepository.kt" && it.type == ActivityEdgeType.IMPORTS
        }
        assertNotNull(edge)
        // the target became a light discovered node, not part of the active trail
        assertEquals(ActivityNodeState.DISCOVERED, g.node("file:/app/data/UserRepository.kt")!!.state)
    }

    @Test fun structuralEnrichmentDoesNotChangeFocusOrTrail() {
        val g = freshGraph()
        g.apply(FileEdited("/app/ui/Foo.kt", created = false, at = tick()))
        val focusBefore = g.focus
        val primary = g.apply(StructuralRelation("/app/ui/Foo.kt", "/app/Bar.kt", "Bar", StructuralRelationKind.IMPORTS, tick()))
        assertNull(primary)                 // focus-only events return null
        assertEquals(focusBefore.label, g.focus.label) // focus unchanged
    }

    @Test fun structuralRelationIgnoredIfSourceNotTouched() {
        val g = freshGraph()
        g.apply(StructuralRelation("/app/Untouched.kt", "/app/Bar.kt", "Bar", StructuralRelationKind.IMPORTS, tick()))
        assertNull(g.node("file:/app/Untouched.kt"))
        assertNull(g.node("file:/app/Bar.kt"))
    }

    @Test fun extendsAndImplementsRelationsBecomeHierarchyEdges() {
        val g = freshGraph()
        g.apply(FileEdited("/app/LoginViewModel.kt", created = false, at = tick()))
        g.apply(StructuralRelation("/app/LoginViewModel.kt", "/app/BaseViewModel.kt", "BaseViewModel",
            StructuralRelationKind.EXTENDS, tick()))
        g.apply(StructuralRelation("/app/LoginViewModel.kt", "/app/Trackable.kt", "Trackable",
            StructuralRelationKind.IMPLEMENTS, tick()))
        assertTrue(g.edges.any { it.type == ActivityEdgeType.EXTENDS && it.targetNodeId == "file:/app/BaseViewModel.kt" })
        assertTrue(g.edges.any { it.type == ActivityEdgeType.IMPLEMENTS && it.targetNodeId == "file:/app/Trackable.kt" })
    }

    @Test fun testRelationAndPackageMetadata() {
        val g = freshGraph()
        g.apply(FileRead("/app/FooTest.kt", tick()))
        g.apply(StructuralRelation("/app/FooTest.kt", "/app/Foo.kt", "Foo", StructuralRelationKind.TESTS, tick()))
        assertNotNull(g.edges.firstOrNull { it.type == ActivityEdgeType.TESTS && it.targetNodeId == "file:/app/Foo.kt" })
        g.apply(FilePackage("/app/FooTest.kt", "com.example", "app", tick()))
        assertEquals("com.example", g.node("file:/app/FooTest.kt")!!.metadata["package"])
        assertEquals("app", g.node("file:/app/FooTest.kt")!!.metadata["module"])
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
