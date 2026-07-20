package io.mp.sightline.ui.state

/** How much visual weight a tool event earns in the transcript. */
enum class ToolWeight {
    /** One quiet line: icon, verb, target, state. No border, no fill. */
    COMPACT,

    /** A bordered, filled card — reserved for things worth stopping on. */
    CARD,
}

/** What we know about a tool call's outcome so far. */
enum class ToolOutcome { RUNNING, OK, ERROR, BLOCKED }

/**
 * Decides whether a tool event renders as a compact row or a full card.
 *
 * The transcript used to be binary — `showDetails` off meant *no* tool cards, on meant *every* tool
 * call at full card weight, using the same fill+border recipe as the user's own message bubble. A
 * successful `Read` was as loud as the prompt that caused it. This adds the missing middle tier so
 * routine work recedes and the things worth reading stand out.
 *
 * The decision is made from **structured metadata** — the tool name and the outcome — never from a
 * rendered display string, so re-wording a summary can never change how an event is presented.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object ToolEventPresentation {

    /**
     * Tools whose successful use is *navigation*, not a change: reading, searching, listing. These are
     * how the agent orients itself, so they are numerous and individually uninteresting.
     */
    val READ_ONLY_TOOLS: Set<String> = setOf(
        "Read", "NotebookRead", "Grep", "Glob", "LS", "WebFetch", "WebSearch", "TodoWrite",
    )

    /**
     * Tools that change files. A successful edit is the *point* of a turn, so it keeps card weight
     * even when it worked — the opposite of a read.
     */
    val MUTATING_TOOLS: Set<String> = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

    fun weight(toolName: String?, outcome: ToolOutcome): ToolWeight = when {
        // A failure is the one thing a reader must not scroll past, whatever produced it.
        outcome == ToolOutcome.ERROR -> ToolWeight.CARD
        // Denied/cancelled is not an error, but it *is* a decision the user made and should be able
        // to see they made — it must never fade into the routine noise.
        outcome == ToolOutcome.BLOCKED -> ToolWeight.CARD
        toolName in MUTATING_TOOLS -> ToolWeight.CARD
        else -> ToolWeight.COMPACT
    }

    /** True when this event should stay quiet: the common case, so callers read the positive form. */
    fun isCompact(toolName: String?, outcome: ToolOutcome): Boolean =
        weight(toolName, outcome) == ToolWeight.COMPACT

    /** Whether a body exists worth offering a disclosure control for. */
    fun hasDisclosure(bodyLength: Int): Boolean = bodyLength > 0
}
