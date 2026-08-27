package io.mp.sightline.ui.state

import com.google.gson.JsonObject

/**
 * "Revert Claude's file changes back to this message."
 *
 * The CLI keeps a backup of every file before it writes to it, keyed by the **user message** that
 * started the turn, and a `rewind_files` control request restores them. Verified end to end against
 * 2.1.235 (docs/PROTOCOL.md §6): a file was edited and then restored byte-for-byte, with the reply
 * `{"canRewind":true,"skippedLinks":0}`.
 *
 * ### Why this is off by default
 *
 * The mechanism is real but it is not a *stable* contract. It needs the environment variable
 * `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`, whose name says it belongs to the SDK's internals, and
 * the CLI's own docs note that its `--rewind-files` companion is deliberately absent from `--help`.
 * That is the `UNKNOWN` rung of this codebase's own fact ladder: good enough to offer, not good enough
 * to turn on for everyone and not good enough to promise. A CLI that drops it should cost a user a
 * greyed-out menu item, not a revert they believed happened.
 *
 * ### Why the limits must be on the screen, not in a doc
 *
 * A revert that silently covers *some* of what Claude did is more dangerous than no revert at all —
 * the user checks the file they were shown, sees it restored, and assumes the rest went with it. The
 * CLI tracks only `Write`, `Edit` and `NotebookEdit`. It does **not** track:
 *
 *  - anything a `Bash` command changed (`sed -i`, `npm install`, a build, a `git checkout`);
 *  - edits made by a **subagent**, which is most of a `Task`'s work;
 *  - created, moved or deleted **directories**.
 *
 * So [LIMITS] is shown wherever the action is, every time, and [confirmation] repeats it — this is the
 * one place in the panel where a user is about to throw work away on our say-so.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object CheckpointPolicy {

    /** Shown with the action, every time. Not a doc link — the caveat has to be where the click is. */
    const val LIMITS: String =
        "Only edits Claude made with Edit, Write or NotebookEdit are restored. Changes made by a " +
            "command it ran, or by a subagent inside a Task, are not — use git for those."

    /** Menu label for the revert action on a user message. */
    const val ACTION_LABEL: String = "Revert Claude's file changes to here…"

    /**
     * The text of a **replayed user message**, or null when the event is not one.
     *
     * `--replay-user-messages` echoes each message we sent back on stdout carrying the `uuid` that is
     * its checkpoint. Three other things look similar and must not be mistaken for it: a `user` event
     * carrying `tool_result` blocks (the normal case, many per turn), a forwarded **subagent** message
     * (which carries `parent_tool_use_id` and is routed away before this), and the CLI's synthetic
     * `[Request interrupted by user]` after a Stop. Requiring a text block and no tool_result excludes
     * the first two; matching the text against what we actually sent excludes the third.
     */
    fun replayedText(event: JsonObject): String? {
        if (event.string("type") != "user") return null
        if (event.string("uuid").isNullOrBlank()) return null
        val content = event.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val parts = ArrayList<String>(1)
        for (el in content) {
            if (!el.isJsonObject) continue
            val b = el.asJsonObject
            // A single tool_result anywhere means this is a turn's tool output, not a replay.
            if (b.string("type") == "tool_result") return null
            if (b.string("type") == "text") b.string("text")?.let { parts.add(it) }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * Whether a replayed message is the one we sent. Compared on trimmed text, because that is the only
     * thing common to both sides — the bubble has no id until this match assigns one.
     *
     * The match is **required**, never assumed from ordering. Assigning the wrong checkpoint to a bubble
     * would revert to the wrong point, and this is the one action in the panel that destroys work; no
     * revert offered at all is a far better failure than a revert to somewhere else.
     */
    fun isSameMessage(sent: String, replayed: String): Boolean = sent.trim() == replayed.trim()

    /**
     * Messages the **CLI** writes into the user stream itself, which are shaped exactly like a replay —
     * same type, same `uuid`, same text content — and must never be allowed near the queue.
     *
     * Currently one: the marker emitted after an `interrupt` (verified, 2.1.235). Hardcoding a literal
     * the CLI could reword is a small, bounded bet: if it changes, one queued checkpoint is discarded
     * after a Stop, and the next message gets one as normal.
     */
    fun isCliSynthetic(replayed: String): Boolean =
        replayed.trim() == "[Request interrupted by user]" 

    /** Whether the action can be offered at all for a given message. */
    fun offerable(enabled: Boolean, checkpointId: String?): Boolean = enabled && !checkpointId.isNullOrBlank()

    /** The confirmation the user has to accept. Names the message, and repeats the limits. */
    fun confirmation(messagePreview: String): String {
        val what = preview(messagePreview)
        val target = if (what.isBlank()) "this message" else "“$what”"
        return "Restore the files Claude edited since $target?\n\n$LIMITS\n\n" +
            "This cannot be undone from here."
    }

    /**
     * What to say once the CLI has answered.
     *
     * `skippedLinks` counts tracked paths the CLI refused to touch — a symlink, a hard link, a file
     * whose directory moved. It is reported because a partial restore that reads as a whole one is the
     * failure this whole class is written around.
     */
    fun outcome(canRewind: Boolean, skippedLinks: Int, error: String?): Notice = when {
        !canRewind -> Notice(
            "Nothing was restored" + (error?.let { " — $it" } ?: "") + ".",
            isError = true,
        )
        skippedLinks > 0 -> Notice(
            "Restored the files Claude edited, except $skippedLinks that could not be written safely " +
                "(a link, or a file whose folder has moved). $LIMITS",
            isError = true,
        )
        else -> Notice("Restored the files Claude edited since that message. $LIMITS", isError = false)
    }

    /** Said when the CLI turns out not to support rewinding at all. */
    const val UNSUPPORTED: String =
        "This Claude CLI cannot restore files. Nothing was changed. Turn the setting off, or update the CLI."

    data class Notice(val text: String, val isError: Boolean)

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** A short, single-line stand-in for the message being reverted to. */
    internal fun preview(text: String, max: Int = 48): String {
        val flat = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }
}
