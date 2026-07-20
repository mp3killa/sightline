package io.mp.sightline.interaction

/**
 * Platform-free selection state for a [UserQuestionRequest] — the single source of truth the Swing
 * question widget renders and mutates. Keeping it here (not in the widget) means validity and answer
 * resolution are unit-tested without a UI, mirroring the `ui/state` pattern.
 *
 * Single-select questions hold at most one option label (radio semantics); multi-select hold an
 * ordered set (checkbox semantics). Any question may instead carry a free-text **Other** answer — the
 * two are mutually exclusive for single-select and combinable for multi-select.
 */
class QuestionFormState(val request: UserQuestionRequest) {

    private class QState {
        val selected = LinkedHashSet<String>()
        var otherOn = false
        var otherText = ""
    }

    private val states = request.questions.map { QState() }

    fun isMultiSelect(qi: Int): Boolean = request.questions[qi].multiSelect

    /** Select [label]: replaces any prior choice for single-select, toggles it for multi-select. */
    fun select(qi: Int, label: String) {
        val s = states[qi]
        if (isMultiSelect(qi)) {
            if (!s.selected.remove(label)) s.selected.add(label)
        } else {
            s.selected.clear()
            s.selected.add(label)
            s.otherOn = false
        }
    }

    fun isSelected(qi: Int, label: String): Boolean = states[qi].selected.contains(label)

    /** Turn the "Other" free-text choice on/off. For single-select, enabling it clears option choices. */
    fun setOther(qi: Int, on: Boolean) {
        val s = states[qi]
        s.otherOn = on
        if (on && !isMultiSelect(qi)) s.selected.clear()
    }

    fun isOther(qi: Int): Boolean = states[qi].otherOn

    fun setOtherText(qi: Int, text: String) { states[qi].otherText = text }
    fun otherText(qi: Int): String = states[qi].otherText

    /** Answered when at least one option is selected, or "Other" is on with non-blank text. */
    fun isQuestionAnswered(qi: Int): Boolean {
        val s = states[qi]
        return s.selected.isNotEmpty() || (s.otherOn && s.otherText.isNotBlank())
    }

    fun isComplete(): Boolean = request.questions.indices.all { isQuestionAnswered(it) }

    /**
     * Resolved answers as question-text → ordered selected labels, with the "Other" choice replaced by
     * its trimmed custom text (never the literal word "Other"). Option labels keep their declared order;
     * unanswered questions are omitted. Feeds [AskUserQuestionResponseBuilder.build].
     */
    fun resolvedAnswers(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        request.questions.forEachIndexed { qi, q ->
            if (!isQuestionAnswered(qi)) return@forEachIndexed
            val s = states[qi]
            val labels = q.options.map { it.label }.filterTo(ArrayList()) { s.selected.contains(it) }
            if (s.otherOn && s.otherText.isNotBlank()) labels.add(s.otherText.trim())
            out[q.question] = labels
        }
        return out
    }
}
