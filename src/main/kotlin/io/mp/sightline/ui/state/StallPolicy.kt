package io.mp.sightline.ui.state

/**
 * When to say that a running turn has gone quiet — and, much more importantly, when **not** to.
 *
 * The complaint this answers is real and common: the panel sits there "working" and nothing happens,
 * with no timeout and no error, so the only way to find out whether anything is still alive is to give
 * up and press Stop. A turn that is genuinely wedged should say so.
 *
 * The trap is the opposite failure, and in an Android IDE it is the likelier one: **a long Gradle
 * build produces no stream events for minutes and is perfectly healthy.** A naive "no output for N
 * seconds" notice would cry wolf on exactly the workflow this plugin exists for. So silence is only
 * reported when nothing is known to be running — [quiet] takes `toolsInFlight`, and a tool in flight
 * means the quiet is explained and nothing is said at any duration.
 *
 * The wording is held to the same standard as the rest of the panel: it states **what is observed**
 * ("no response for 2m") and never diagnoses ("stuck", "crashed", "hung"). Nothing here can tell the
 * difference between a slow model, a network stall and a dead process — and [SessionFailure] is what
 * speaks when the process really does die.
 */
object StallPolicy {

    /**
     * How long silence must last before it is worth mentioning. Long enough that an ordinary slow
     * reply — extended thinking, a large prompt being processed — never trips it.
     */
    const val QUIET_AFTER_MS = 90_000L

    /** How long between repeats, so a genuinely wedged turn keeps saying so without flooding. */
    const val REPEAT_EVERY_MS = 120_000L

    /**
     * Whether to report silence now.
     *
     * [sinceLastEventMs] is the time since the last event of any kind on the stream. [toolsInFlight]
     * is the number of tools the panel has seen start and not finish — a build, a test run, a
     * subagent. [sinceLastNoticeMs] is null when nothing has been said yet this turn.
     */
    fun quiet(sinceLastEventMs: Long, toolsInFlight: Int, sinceLastNoticeMs: Long?): Boolean {
        if (toolsInFlight > 0) return false
        if (sinceLastEventMs < QUIET_AFTER_MS) return false
        return sinceLastNoticeMs == null || sinceLastNoticeMs >= REPEAT_EVERY_MS
    }

    /**
     * What to say. Reports the observed silence and what the user can do, without claiming to know
     * why — and without promising that Stop kills a command, which [StopPolicy] is the authority on.
     */
    fun notice(sinceLastEventMs: Long): String =
        "No response from Claude for ${duration(sinceLastEventMs)}. The turn is still open — it may " +
            "still be working, or the CLI may have stopped responding. Stop ends the turn; the health " +
            "check under More can tell you whether the CLI is reachable."

    /** Whole minutes once past one, since a second-accurate figure implies a precision we don't have. */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes >= 2 -> "$minutes minutes"
            minutes == 1L -> "a minute"
            else -> "${ms / 1000} seconds"
        }
    }
}
