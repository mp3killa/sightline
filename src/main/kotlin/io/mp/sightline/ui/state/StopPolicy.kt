package io.mp.sightline.ui.state

/**
 * What pressing **Stop** should actually do, and what the panel is allowed to claim it did.
 *
 * There are two ways to stop a turn and they are not interchangeable:
 *
 * - **`interrupt`** (control protocol) ends the turn and leaves everything else standing — the process,
 *   the session id, the MCP connections, the CLI's view of the conversation. The next message continues
 *   in the same process with no `--resume` and no reconnect.
 * - **Killing the process** ends the turn by ending the CLI. Recovery is a relaunch plus `--resume`,
 *   which re-reads every config file and re-establishes every MCP server, and leaves a window in which
 *   nothing is reading stdin (which is the whole reason
 *   [ComposerModel]'s message queue exists as a fallback).
 *
 * So interrupt is the primary path and the kill is the fallback — the same shape as interject-then-queue.
 * The fallback is reached in exactly three ways: the CLI is too old to know the subtype, there is no
 * live process to write to, or **the user pressed Stop again**, which is the only signal available that
 * the polite stop was not enough.
 *
 * ### What Stop does not do
 *
 * Neither path kills a command that is already running. Verified against CLI 2.1.235: after an
 * acknowledged `interrupt`, a `sleep 40 && touch SENTINEL` still created its sentinel forty seconds
 * later. The agent takes no further step, but the shell it already started runs to completion. A panel
 * that says "Stopped" full stop is therefore telling a developer their Gradle build has stopped when it
 * has not — so the notice names what did stop and what did not, and says nothing about the child
 * process when there wasn't one.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object StopPolicy {

    /**
     * Tools whose work can outlive the stop, so the notice has to mention them.
     *
     * `Bash` is the obvious one: it leaves a child process running, verified to survive both paths.
     * `Task` is here because a subagent's own `Bash` calls are **not individually trackable** — the
     * forwarded events carry the subagent's tool ids, which we deliberately do not correlate across the
     * boundary — so a Task in flight may well have a shell running that we cannot see the end of.
     * Treating a live Task as "something may still be running" is the honest reading; the alternative
     * was a notice that confidently said nothing was running while a subagent's Gradle build carried on.
     */
    val LINGERING_TOOLS: Set<String> = setOf("Bash", "Task")

    enum class StopAction {
        /** Nothing is running, or a force-stop is already in flight — the press is a no-op. */
        NONE,

        /** Send `interrupt`: end the turn, keep the process and the conversation. */
        INTERRUPT,

        /** Kill the process. Recovery is a relaunch with `--resume`. */
        FORCE,
    }

    /**
     * @param running whether a turn is in flight at all.
     * @param interruptPending true once an [INTERRUPT] has been sent for this turn and neither the
     *   turn's `result` nor a refusal has arrived — i.e. a second press is an escalation.
     * @param interruptSupported false once this CLI has answered an `interrupt` with
     *   "Unsupported control request subtype", so later presses skip straight to the kill.
     */
    fun decide(running: Boolean, interruptPending: Boolean, interruptSupported: Boolean): StopAction = when {
        !running -> StopAction.NONE
        // The user pressed Stop again while the polite stop was outstanding. That is the escalation
        // signal, and the only one there is — the CLI does not report "still working on stopping".
        interruptPending -> StopAction.FORCE
        !interruptSupported -> StopAction.FORCE
        else -> StopAction.INTERRUPT
    }

    /** Status-strip label while a stop is in flight. */
    fun statusLabel(action: StopAction): String = when (action) {
        StopAction.FORCE -> "Stopping"
        else -> "Interrupting"
    }

    /**
     * What to tell the user in the transcript, or null when there is nothing worth saying.
     *
     * @param commandInFlight whether a command tool was running when Stop was pressed. Only then is the
     *   "it will finish on its own" clause true, and stating it unconditionally would be its own kind of
     *   wrong — a reader would start looking for a process that was never started.
     */
    fun notice(action: StopAction, commandInFlight: Boolean): String? = when (action) {
        StopAction.NONE -> null
        StopAction.INTERRUPT ->
            if (commandInFlight) {
                "Stopping this turn. Claude will not take another step — but a command it already " +
                    "started keeps running to completion; stopping that is up to you."
            } else {
                "Stopping this turn. The conversation is kept, so your next message continues it."
            }
        StopAction.FORCE ->
            if (commandInFlight) {
                "Ending the Claude process. A command it already started may keep running; stopping " +
                    "that is up to you. Your next message resumes the conversation."
            } else {
                "Ending the Claude process. Your next message resumes the conversation."
            }
    }

    /** Said once per session, when a CLI turns out not to know `interrupt` at all. */
    const val UNSUPPORTED_NOTICE: String =
        "This Claude CLI cannot interrupt a turn without ending the process, so Stop ended it. " +
            "Updating the CLI makes Stop keep the session."
}
