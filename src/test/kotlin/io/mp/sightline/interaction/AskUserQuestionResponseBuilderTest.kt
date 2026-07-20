package io.mp.sightline.interaction

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AskUserQuestionResponseBuilderTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val input = obj(
        """{"questions":[{"question":"No device is connected. Boot an emulator?","header":"Tests",
           "options":[{"label":"Boot Medium_Phone"},{"label":"Skip"}],"multiSelect":false}]}""",
    )
    private val request = (AskUserQuestionParser.parse(input) as ParseResult.Ok).value
    private val q = "No device is connected. Boot an emulator?"

    @Test fun buildsSingleAnswerKeyedByQuestionTextAndPreservesQuestions() {
        val out = AskUserQuestionResponseBuilder.build(input, request, mapOf(q to listOf("Boot Medium_Phone")))
        assertEquals("Boot Medium_Phone", out.getAsJsonObject("answers").get(q).asString)
        assertTrue(out.has("questions"))
        assertEquals(1, out.getAsJsonArray("questions").size())
    }

    @Test fun joinsMultipleLabelsWithComma() {
        val multiInput = obj("""{"questions":[{"question":"Which checks should I run?","options":[{"label":"Unit tests"},{"label":"Android lint"}],"multiSelect":true}]}""")
        val req = (AskUserQuestionParser.parse(multiInput) as ParseResult.Ok).value
        val out = AskUserQuestionResponseBuilder.build(multiInput, req, mapOf("Which checks should I run?" to listOf("Unit tests", "Android lint")))
        assertEquals("Unit tests, Android lint", out.getAsJsonObject("answers").get("Which checks should I run?").asString)
    }

    @Test fun customTextUsedInsteadOfOtherLiteral() {
        val out = AskUserQuestionResponseBuilder.build(input, request, mapOf(q to listOf("my custom answer")))
        val v = out.getAsJsonObject("answers").get(q).asString
        assertEquals("my custom answer", v)
        assertFalse(v.contains("Other"))
    }

    @Test fun skipOptionIsSubmittedAsAnAnswer() {
        // "Skip" is a valid option label, not a denial — it must be returned verbatim.
        val out = AskUserQuestionResponseBuilder.build(input, request, mapOf(q to listOf("Skip")))
        assertEquals("Skip", out.getAsJsonObject("answers").get(q).asString)
    }

    @Test fun doesNotMutateOriginalInput() {
        val before = input.toString()
        AskUserQuestionResponseBuilder.build(input, request, mapOf(q to listOf("Skip")))
        assertEquals(before, input.toString())
    }

    @Test fun rejectsUnansweredQuestion() {
        try { AskUserQuestionResponseBuilder.build(input, request, emptyMap()); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) {}
    }

    @Test fun rejectsBlankAnswer() {
        try { AskUserQuestionResponseBuilder.build(input, request, mapOf(q to listOf("  "))); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) {}
    }
}
