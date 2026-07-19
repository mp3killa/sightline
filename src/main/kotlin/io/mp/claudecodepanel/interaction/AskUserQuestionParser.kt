package io.mp.claudecodepanel.interaction

import com.google.gson.JsonObject

/**
 * Parses an `AskUserQuestion` tool input into a [UserQuestionRequest]. Tolerant of missing **optional**
 * fields (`header`, `description`, `preview`, `multiSelect`) but never invents structure: a question
 * with no text, or with no options, is a parse failure — the UI must not present an empty or fabricated
 * choice. Pure (no platform imports) and does not mutate [input], so it is covered by plain unit tests.
 */
object AskUserQuestionParser {

    fun parse(input: JsonObject): ParseResult<UserQuestionRequest> {
        val arr = input.get("questions")
        if (arr == null || !arr.isJsonArray) {
            return ParseResult.Invalid("missing 'questions' array", listOf("questions"))
        }
        val questionsJson = arr.asJsonArray
        if (questionsJson.size() == 0) return ParseResult.Invalid("'questions' is empty", listOf("questions"))

        val questions = ArrayList<UserQuestion>(questionsJson.size())
        for ((qi, el) in questionsJson.withIndex()) {
            if (!el.isJsonObject) {
                return ParseResult.Invalid("question $qi is not an object", listOf("questions[$qi]"))
            }
            val qo = el.asJsonObject
            val text = qo.stringOrNull("question")
            if (text.isNullOrBlank()) {
                return ParseResult.Invalid("question $qi has no text", listOf("questions[$qi].question"))
            }
            val optsEl = qo.get("options")
            if (optsEl == null || !optsEl.isJsonArray || optsEl.asJsonArray.size() == 0) {
                return ParseResult.Invalid("question $qi has no options", listOf("questions[$qi].options"))
            }
            val options = ArrayList<UserQuestionOption>(optsEl.asJsonArray.size())
            for ((oi, oel) in optsEl.asJsonArray.withIndex()) {
                if (!oel.isJsonObject) {
                    return ParseResult.Invalid("option $oi of question $qi is not an object", listOf("questions[$qi].options[$oi]"))
                }
                val oo = oel.asJsonObject
                val label = oo.stringOrNull("label")
                if (label.isNullOrBlank()) {
                    return ParseResult.Invalid("option $oi of question $qi has no label", listOf("questions[$qi].options[$oi].label"))
                }
                options.add(UserQuestionOption(label, oo.stringOrNull("description"), oo.stringOrNull("preview")))
            }
            questions.add(UserQuestion(text, qo.stringOrNull("header"), options, qo.boolOrFalse("multiSelect")))
        }
        return ParseResult.Ok(UserQuestionRequest(questions))
    }

    private fun JsonObject.stringOrNull(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.boolOrFalse(k: String): Boolean =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}
