package io.mp.sightline.ui.state

/**
 * What a plan review can decide, and exactly what the CLI is told in each case.
 *
 * Plan mode ends with the CLI asking permission for **ExitPlanMode**, whose input carries the plan as
 * Markdown plus the file it was written to (`{plan, planFilePath}`, captured from 2.1.235). That makes
 * the approval prompt the natural place to *review* the plan rather than merely consent to it — the
 * thing developers single out as better about the equivalent VS Code panel is being able to edit a
 * plan before it runs, instead of arguing with it in chat afterwards.
 *
 * The wording of a rejection is not cosmetic. Denying `ExitPlanMode` with a bare "no" made the model
 * re-propose the same plan three times in a row (observed) — a denial with nothing actionable in it is
 * a loop. So every rejection here carries either the revised plan or a reason, and says plainly that
 * the plan was not approved.
 */
object PlanReview {

    /** The plan text and where the CLI saved it, if it said. */
    data class Plan(val markdown: String, val filePath: String?)

    /**
     * Reads the `ExitPlanMode` input. Returns null when there is no plan text — in which case the
     * caller must fall back to the ordinary approval card rather than render an empty plan, since a
     * plan review with no plan in it is worse than the generic prompt it replaced.
     */
    fun of(planText: String?, planFilePath: String?): Plan? {
        val markdown = planText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Plan(markdown, planFilePath?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The message sent when the user rewrites the plan. It states the outcome first (not approved),
     * then hands over the replacement, because the model reads this as the tool's result and needs to
     * know it is being redirected rather than answered.
     */
    fun revisedMessage(editedPlan: String): String =
        "The plan was not approved. Use this revised plan instead, and follow it as written:\n\n" +
            editedPlan.trim()

    /** The message sent when the user wants more planning rather than a rewrite. */
    fun keepPlanningMessage(reason: String?): String {
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }
        return if (why == null) {
            "The plan was not approved. Stay in plan mode and revise it before proposing again."
        } else {
            "The plan was not approved. Stay in plan mode and revise it before proposing again: $why"
        }
    }

    /** True when an edit actually changed something — an unmodified "edit" is an approval. */
    fun isChanged(original: String, edited: String): Boolean = original.trim() != edited.trim()
}
