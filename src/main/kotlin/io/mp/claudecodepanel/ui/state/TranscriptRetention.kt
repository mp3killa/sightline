package io.mp.claudecodepanel.ui.state

/**
 * Bounds how many turns the transcript keeps as live Swing components.
 *
 * Every turn was retained forever: a long session accumulated an unbounded component tree, and each
 * layout pass walked all of it. (The activity graph has always had `activityMaxRetained`; the
 * transcript never did.) This is the equivalent cap.
 *
 * Eviction is **real** — the components are dropped, not merely hidden — so the memory is actually
 * released. That means evicted turns cannot be brought back, and the UI must not pretend otherwise:
 * there is no session persistence to reload them from, so the notice says they are gone rather than
 * offering a "load earlier" control that could not work.
 */
object TranscriptRetention {

    /**
     * Turns kept live. Generous enough that a normal working session never evicts, low enough that a
     * marathon session stays responsive.
     */
    const val MAX_TURNS = 150

    /** How many of the oldest turns to drop to get back under [cap]. */
    fun evictCount(turnCount: Int, cap: Int = MAX_TURNS): Int = (turnCount - cap).coerceAtLeast(0)

    fun shouldEvict(turnCount: Int, cap: Int = MAX_TURNS): Boolean = evictCount(turnCount, cap) > 0

    /**
     * The notice shown above the transcript once anything has been dropped. Empty when nothing has,
     * so the caller hides the row entirely.
     */
    fun noticeText(evictedTotal: Int): String = when {
        evictedTotal <= 0 -> ""
        evictedTotal == 1 -> "1 earlier turn was released to keep this session responsive"
        else -> "$evictedTotal earlier turns were released to keep this session responsive"
    }
}
