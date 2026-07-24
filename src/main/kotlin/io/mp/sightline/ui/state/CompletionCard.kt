package io.mp.sightline.ui.state

/**
 * The structured end-of-turn summary: a clear terminal **state**, the run metadata, and any warnings
 * the turn actually observed — so the outcome is a glanceable card, not a sentence a reader has to
 * find at the end of a wall of prose (the review's "completion should produce a dashboard, not another
 * message").
 *
 * It states only what was **observed**: files edited, checks that ran to a verdict, denials, recovered
 * command failures. It invents no "implemented X, Y, Z" narrative — that is the model's prose and stays
 * in the transcript. Platform-free and deterministic, so it is unit-tested; the Swing card in
 * `ClaudePanel.AssistantTurn` is a thin render of this [View].
 */
object CompletionCard {

    enum class State { COMPLETED, COMPLETED_WITH_WARNINGS, STOPPED }

    data class View(
        val state: State,
        val headline: String,
        /** `51.6s · 13 turns · $0.404`, or empty. */
        val meta: String,
        /** Observed issues worth surfacing at the end, e.g. "1 recovered command failure". */
        val warnings: List<String>,
    )

    fun of(
        summary: ProcessingSummary,
        costUsd: Double?,
        durationMs: Double?,
        numTurns: Int?,
        isError: Boolean,
        recoveredFailures: Int,
    ): View {
        val warnings = ArrayList<String>()
        if (recoveredFailures > 0) {
            warnings.add("$recoveredFailures recovered command failure${if (recoveredFailures == 1) "" else "s"}")
        }
        if (summary.checksFailed > 0) {
            warnings.add("${summary.checksFailed} ${if (summary.checksFailed == 1) "check" else "checks"} failed")
        }
        if (summary.denied > 0) warnings.add("${plural(summary.denied, "action")} denied")

        val state = when {
            isError -> State.STOPPED
            warnings.isNotEmpty() -> State.COMPLETED_WITH_WARNINGS
            else -> State.COMPLETED
        }
        val headline = when (state) {
            State.STOPPED -> "Stopped"
            State.COMPLETED_WITH_WARNINGS -> "Completed with warnings"
            State.COMPLETED -> "Completed"
        }
        return View(state, headline, CompletionSummary.meta(costUsd, durationMs, numTurns), warnings)
    }

    private fun plural(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
}
