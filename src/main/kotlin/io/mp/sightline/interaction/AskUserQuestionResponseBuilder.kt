package io.mp.sightline.interaction

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Builds the `AskUserQuestion` control response Claude expects: the original input (its `questions`
 * array preserved unchanged) plus an `answers` object keyed by **full question text**, whose values are
 * the selected option labels joined by ", " (a custom "Other" answer is passed through as its own text).
 *
 * Pure: it copies [originalInput] via re-parse rather than mutating it, and refuses to emit a response
 * that leaves any question unanswered or carries a blank label. Unit-tested.
 */
object AskUserQuestionResponseBuilder {

    /**
     * @param answers question text → ordered resolved labels (see [QuestionFormState.resolvedAnswers]).
     * @throws IllegalArgumentException if any question in [request] is unanswered or an answer is blank.
     */
    fun build(originalInput: JsonObject, request: UserQuestionRequest, answers: Map<String, List<String>>): JsonObject {
        val answersObj = JsonObject()
        for (q in request.questions) {
            val labels = answers[q.question]
            require(!labels.isNullOrEmpty()) { "no answer for question: ${q.question}" }
            require(labels.all { it.isNotBlank() }) { "blank answer for question: ${q.question}" }
            answersObj.addProperty(q.question, labels.joinToString(", "))
        }
        // Deep-copy the original input (keeping `questions` and any other fields) without mutating it.
        val out = JsonParser.parseString(originalInput.toString()).asJsonObject
        out.add("answers", answersObj)
        return out
    }
}
