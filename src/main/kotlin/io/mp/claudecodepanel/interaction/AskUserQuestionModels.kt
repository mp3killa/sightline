package io.mp.claudecodepanel.interaction

/**
 * Provider-neutral model of a Claude Code `AskUserQuestion` request. It travels the same `can_use_tool`
 * control channel as a permission prompt, but it is a request for **user input**, not permission to act,
 * so the plugin renders it as a structured question rather than an Allow/Deny approval.
 *
 * The protocol JSON is parsed off the Swing thread by [AskUserQuestionParser] (so the UI never inspects
 * raw fields), the user's choices are tracked by [QuestionFormState], and [AskUserQuestionResponseBuilder]
 * turns them back into the control response. All three are platform-free and unit-tested.
 */
data class UserQuestionRequest(val questions: List<UserQuestion>)

data class UserQuestion(
    /** The complete question text — also the key used for this question's entry in the response. */
    val question: String,
    /** Short category heading (e.g. "Instrumented tests"); may be absent. */
    val header: String?,
    val options: List<UserQuestionOption>,
    val multiSelect: Boolean,
)

data class UserQuestionOption(
    val label: String,
    val description: String?,
    /** Optional monospace preview blob supplied by some options; preserved but not required. */
    val preview: String?,
)

/** Result of parsing untrusted protocol input: either a usable request or a described, log-safe failure. */
sealed interface ParseResult<out T> {
    data class Ok<T>(val value: T) : ParseResult<T>

    /** [reason] and [fields] are safe to log (no prompt/source content) — they name what was wrong. */
    data class Invalid(val reason: String, val fields: List<String> = emptyList()) : ParseResult<Nothing>
}
