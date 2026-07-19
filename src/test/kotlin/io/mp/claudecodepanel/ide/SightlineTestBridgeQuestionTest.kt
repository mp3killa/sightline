package io.mp.claudecodepanel.ide

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.claudecodepanel.interaction.AskUserQuestionParser
import io.mp.claudecodepanel.interaction.ParseResult

/**
 * Verifies the sandbox bridge's AskUserQuestion path drives the **same** production response builder +
 * `QuestionCoordinator` the real UI does — the autonomous half of live verification (the Swing rendering
 * still needs a runIde/driver, but the answer-construction + resolve-once logic is checked here).
 */
class SightlineTestBridgeQuestionTest : BasePlatformTestCase() {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun register(coord: QuestionCoordinator, id: String, inputJson: String, sink: (QuestionResolution) -> Unit) {
        val input = obj(inputJson)
        val request = (AskUserQuestionParser.parse(input) as ParseResult.Ok).value
        coord.register(PendingQuestion(id, null, request, input.toString(), sink))
    }

    fun testRespondQuestionBuildsProductionResponseAndResolvesOnce() {
        val bridge = SightlineTestBridge(project)
        val coord = project.getService(QuestionCoordinator::class.java)
        var resolved: QuestionResolution? = null
        register(
            coord, "req1",
            """{"questions":[{"question":"Boot an emulator?","header":"Tests","options":[{"label":"Boot Medium_Phone"},{"label":"Skip"}],"multiSelect":false}]}""",
        ) { resolved = it }

        // list_pending_interactions surfaces the question with its options
        val listed = obj(bridge.call("sightline.test.list_pending_interactions", JsonObject()).text)
        val q = listed.getAsJsonArray("interactions").map { it.asJsonObject }.single { it.get("type").asString == "QUESTION" }
        assertEquals("req1", q.get("id").asString)

        // answer it → production builder response
        val res = obj(bridge.call("sightline.test.respond_question", obj("""{"interactionId":"req1","answers":{"Boot an emulator?":["Boot Medium_Phone"]}}""")).text)
        assertTrue(res.get("ok").asBoolean)
        val answered = resolved as QuestionResolution.Answered
        val updated = obj(answered.updatedInputJson)
        assertEquals("Boot Medium_Phone", updated.getAsJsonObject("answers").get("Boot an emulator?").asString)
        assertTrue("original questions preserved", updated.has("questions"))

        // resolve-once: a second answer is a no-op
        val res2 = obj(bridge.call("sightline.test.respond_question", obj("""{"interactionId":"req1","answers":{"Boot an emulator?":["Skip"]}}""")).text)
        assertFalse(res2.get("ok").asBoolean)
    }

    fun testCancelQuestionResolvesAsCancelled() {
        val bridge = SightlineTestBridge(project)
        val coord = project.getService(QuestionCoordinator::class.java)
        var resolved: QuestionResolution? = null
        register(coord, "req2", """{"questions":[{"question":"Q?","options":[{"label":"A"}]}]}""") { resolved = it }
        val res = obj(bridge.call("sightline.test.respond_question", obj("""{"interactionId":"req2","cancel":true}""")).text)
        assertTrue(res.get("ok").asBoolean)
        assertEquals(QuestionResolution.Cancelled, resolved)
    }

    fun testRespondUnknownQuestionIsAnError() {
        val bridge = SightlineTestBridge(project)
        val res = obj(bridge.call("sightline.test.respond_question", obj("""{"interactionId":"nope","answers":{"x":["y"]}}""")).text)
        assertFalse(res.get("ok").asBoolean)
    }

    fun testSimulateQuestionWithoutWiredPanelIsAnError() {
        // No tool window open in this fixture, so the injector isn't wired — must fail cleanly, not throw.
        val bridge = SightlineTestBridge(project)
        val res = obj(bridge.call("sightline.test.simulate_question", obj("""{"input":{"questions":[{"question":"Q?","options":[{"label":"A"}]}]}}""")).text)
        assertFalse(res.get("ok").asBoolean)
    }
}
