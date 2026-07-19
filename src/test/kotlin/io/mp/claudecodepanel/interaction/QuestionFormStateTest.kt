package io.mp.claudecodepanel.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionFormStateTest {

    private fun opt(label: String) = UserQuestionOption(label, null, null)
    private fun single(question: String, vararg labels: String) = UserQuestion(question, null, labels.map { opt(it) }, false)
    private fun multi(question: String, vararg labels: String) = UserQuestion(question, null, labels.map { opt(it) }, true)
    private fun state(vararg qs: UserQuestion) = QuestionFormState(UserQuestionRequest(qs.toList()))

    @Test fun incompleteInitially() {
        assertFalse(state(single("Q?", "A", "B")).isComplete())
    }

    @Test fun singleSelectionCompletesQuestion() {
        val s = state(single("Q?", "A", "B"))
        s.select(0, "A")
        assertTrue(s.isComplete())
        assertEquals(mapOf("Q?" to listOf("A")), s.resolvedAnswers())
    }

    @Test fun switchingRadioReplacesSelection() {
        val s = state(single("Q?", "A", "B"))
        s.select(0, "A"); s.select(0, "B")
        assertEquals(mapOf("Q?" to listOf("B")), s.resolvedAnswers())
    }

    @Test fun multiSelectPreservesAllChoicesInOptionOrder() {
        val s = state(multi("Which?", "Unit", "Lint", "Detekt"))
        s.select(0, "Detekt"); s.select(0, "Unit") // selected out of order
        assertEquals(mapOf("Which?" to listOf("Unit", "Detekt")), s.resolvedAnswers())
    }

    @Test fun multiSelectToggleRemoves() {
        val s = state(multi("Which?", "A", "B"))
        s.select(0, "A"); s.select(0, "A")
        assertFalse(s.isComplete())
    }

    @Test fun otherRequiresNonBlankText() {
        val s = state(single("Q?", "A"))
        s.setOther(0, true)
        assertFalse(s.isComplete()) // blank custom text is not an answer
        s.setOtherText(0, "custom")
        assertTrue(s.isComplete())
        assertEquals(mapOf("Q?" to listOf("custom")), s.resolvedAnswers())
    }

    @Test fun singleSelectOtherIsExclusiveWithOptions() {
        val s = state(single("Q?", "A"))
        s.select(0, "A")
        s.setOther(0, true); s.setOtherText(0, "x")
        assertEquals(mapOf("Q?" to listOf("x")), s.resolvedAnswers()) // option cleared by Other
        s.select(0, "A")
        assertEquals(mapOf("Q?" to listOf("A")), s.resolvedAnswers()) // Other cleared by option
    }

    @Test fun multiSelectCombinesOptionAndOther() {
        val s = state(multi("Which?", "A", "B"))
        s.select(0, "A"); s.setOther(0, true); s.setOtherText(0, "custom")
        assertEquals(mapOf("Which?" to listOf("A", "custom")), s.resolvedAnswers())
    }

    @Test fun allQuestionsMustBeAnswered() {
        val s = state(single("Q1?", "A"), single("Q2?", "B"))
        s.select(0, "A")
        assertFalse(s.isComplete())
        s.select(1, "B")
        assertTrue(s.isComplete())
        assertEquals(mapOf("Q1?" to listOf("A"), "Q2?" to listOf("B")), s.resolvedAnswers())
    }
}
