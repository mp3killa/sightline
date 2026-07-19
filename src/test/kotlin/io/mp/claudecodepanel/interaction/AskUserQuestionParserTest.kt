package io.mp.claudecodepanel.interaction

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionParserTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun parsesSingleSelectQuestion() {
        val input = obj(
            """{"questions":[{"question":"How should I format the output?","header":"Format",
               "options":[{"label":"Summary","description":"Brief overview"},
                          {"label":"Detailed","description":"Full explanation"}],"multiSelect":false}]}""",
        )
        val r = AskUserQuestionParser.parse(input) as ParseResult.Ok
        assertEquals(1, r.value.questions.size)
        val q = r.value.questions[0]
        assertEquals("How should I format the output?", q.question)
        assertEquals("Format", q.header)
        assertFalse(q.multiSelect)
        assertEquals(2, q.options.size)
        assertEquals("Summary", q.options[0].label)
        assertEquals("Brief overview", q.options[0].description)
    }

    @Test fun parsesMultipleQuestionsAndDefaultsMultiSelectFalse() {
        val input = obj(
            """{"questions":[
                 {"question":"Q1","options":[{"label":"A"}]},
                 {"question":"Q2","options":[{"label":"B"},{"label":"C"}]}]}""",
        )
        val r = AskUserQuestionParser.parse(input) as ParseResult.Ok
        assertEquals(2, r.value.questions.size)
        assertFalse(r.value.questions[0].multiSelect)
    }

    @Test fun parsesMultiSelect() {
        val input = obj("""{"questions":[{"question":"Which checks?","options":[{"label":"Unit"},{"label":"Lint"}],"multiSelect":true}]}""")
        assertTrue((AskUserQuestionParser.parse(input) as ParseResult.Ok).value.questions[0].multiSelect)
    }

    @Test fun optionalDescriptionAndPreviewMayBeAbsent() {
        val input = obj("""{"questions":[{"question":"Q","options":[{"label":"A"}]}]}""")
        val opt = (AskUserQuestionParser.parse(input) as ParseResult.Ok).value.questions[0].options[0]
        assertEquals("A", opt.label)
        assertNull(opt.description)
        assertNull(opt.preview)
    }

    @Test fun preservesPreview() {
        val input = obj("""{"questions":[{"question":"Q","options":[{"label":"A","preview":"buildFeatures { compose = true }"}]}]}""")
        assertEquals("buildFeatures { compose = true }", (AskUserQuestionParser.parse(input) as ParseResult.Ok).value.questions[0].options[0].preview)
    }

    @Test fun missingQuestionsArrayIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"foo":1}""")) is ParseResult.Invalid)
    }

    @Test fun emptyQuestionsIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"questions":[]}""")) is ParseResult.Invalid)
    }

    @Test fun questionWithoutTextIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"questions":[{"options":[{"label":"A"}]}]}""")) is ParseResult.Invalid)
    }

    @Test fun questionWithoutOptionsIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"questions":[{"question":"Q","options":[]}]}""")) is ParseResult.Invalid)
    }

    @Test fun optionWithoutLabelIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"questions":[{"question":"Q","options":[{"description":"x"}]}]}""")) is ParseResult.Invalid)
    }

    @Test fun malformedQuestionElementIsInvalid() {
        assertTrue(AskUserQuestionParser.parse(obj("""{"questions":["not an object"]}""")) is ParseResult.Invalid)
    }

    @Test fun unknownFieldsAreTolerated() {
        val input = obj("""{"extra":true,"questions":[{"question":"Q","weird":1,"options":[{"label":"A","z":2}]}]}""")
        assertTrue(AskUserQuestionParser.parse(input) is ParseResult.Ok)
    }

    @Test fun doesNotMutateInput() {
        val input = obj("""{"questions":[{"question":"Q","options":[{"label":"A"}]}]}""")
        val before = input.toString()
        AskUserQuestionParser.parse(input)
        assertEquals(before, input.toString())
    }
}
