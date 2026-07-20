package io.mp.sightline.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Drives the realistic fixture from the brief through the real interpreter + graph:
 * search a ViewModel → read it → read its repository → edit the ViewModel → run tests →
 * a test fails → edit the test → run tests again (pass) → complete.
 * Asserts the resulting graph reflects the whole sequence.
 */
class ActivitySequenceIntegrationTest {

    private var t = Instant.parse("2026-07-19T10:00:00Z")
    private val interp = ActivityInterpreter { t = t.plusSeconds(1); t }
    private val graph = ActivityGraph { t }

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun feed(events: List<AgentActivityEvent>) { events.forEach { graph.apply(it) } }

    private val vm = "/app/src/main/java/com/x/driver/DriverLocationViewModel.kt"
    private val repo = "/app/src/main/java/com/x/data/DriverRepository.kt"
    private val vmTest = "/app/src/test/java/com/x/driver/DriverLocationViewModelTest.kt"

    @Test fun fullSequenceIsReflectedInGraph() {
        feed(interp.taskStarted("Fix the driver location updates"))
        // 1. Search for a ViewModel.
        feed(interp.toolUse("g1", "Grep", obj("""{"pattern":"ViewModel"}""")))
        // 2. Read the ViewModel.
        feed(interp.toolUse("r1", "Read", obj("""{"file_path":"$vm"}""")))
        feed(interp.toolResult("r1", "class DriverLocationViewModel { ... }", isError = false))
        // 3. Read its repository.
        feed(interp.toolUse("r2", "Read", obj("""{"file_path":"$repo"}""")))
        feed(interp.toolResult("r2", "class DriverRepository { ... }", isError = false))
        // 4. Edit the ViewModel.
        feed(interp.toolUse("e1", "Edit", obj("""{"file_path":"$vm"}""")))
        feed(interp.toolResult("e1", "ok", isError = false))
        // 5. Run unit tests.
        feed(interp.toolUse("b1", "Bash", obj("""{"command":"./gradlew :app:testDebugUnitTest"}""")))
        // 6. A test fails.
        feed(interp.toolResult("b1", "1 test completed, 1 failed\ncom.x.DriverLocationViewModelTest > loads FAILED\nBUILD FAILED", isError = true))
        // 7. Edit the test.
        feed(interp.toolUse("e2", "Edit", obj("""{"file_path":"$vmTest"}""")))
        feed(interp.toolResult("e2", "ok", isError = false))
        // 8. Run tests again — pass.
        feed(interp.toolUse("b2", "Bash", obj("""{"command":"./gradlew :app:testDebugUnitTest"}""")))
        feed(interp.toolResult("b2", "2 tests completed, 0 failed\nBUILD SUCCESSFUL", isError = false))
        // 9. Task complete.
        feed(interp.taskDone("Fixed the failing test", isError = false))

        // Central task node completed.
        assertEquals(ActivityNodeState.COMPLETED, graph.node(ActivityGraph.TASK_ID)!!.state)

        // The ViewModel was read then edited — final state EDITING, correct cluster.
        val vmNode = graph.node("file:${ActivityClassifier.normalizePath(vm)}")!!
        assertEquals(ActivityNodeState.EDITING, vmNode.state)
        assertEquals(ActivityCategory.VIEWMODELS_STATE, vmNode.category)

        // Repository landed in its own cluster.
        assertEquals(ActivityCategory.DATA_REPOSITORIES, graph.node("file:${ActivityClassifier.normalizePath(repo)}")!!.category)

        // The edited test file is in the Testing cluster and marked edited.
        val testFile = graph.node("file:${ActivityClassifier.normalizePath(vmTest)}")!!
        assertEquals(ActivityCategory.TESTING, testFile.category)
        assertEquals(ActivityNodeState.EDITING, testFile.state)

        // A failed test node from step 6 persists; a passed suite from step 8 exists.
        assertTrue(graph.nodes.any { it.type == ActivityNodeType.TEST && it.state == ActivityNodeState.FAILED })
        assertTrue(graph.nodes.any { it.type == ActivityNodeType.TEST && it.state == ActivityNodeState.PASSED })

        // The generated patch connects to both edited files.
        val patchTargets = graph.edges
            .filter { it.sourceNodeId == ActivityGraph.PATCH_ID && it.type == ActivityEdgeType.GENERATED_FROM }
            .map { it.targetNodeId }.toSet()
        assertTrue(patchTargets.contains("file:${ActivityClassifier.normalizePath(vm)}"))
        assertTrue(patchTargets.contains("file:${ActivityClassifier.normalizePath(vmTest)}"))

        // Expected clusters were all created.
        for (c in listOf(ActivityCategory.VIEWMODELS_STATE, ActivityCategory.DATA_REPOSITORIES,
                ActivityCategory.TESTING, ActivityCategory.GRADLE_BUILD)) {
            assertNotNull("missing cluster $c", graph.node("cat:${c.name}"))
        }

        // Timeline recorded the journey.
        assertTrue(graph.timeline.size >= 8)
    }
}
