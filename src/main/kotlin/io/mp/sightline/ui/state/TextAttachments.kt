package io.mp.sightline.ui.state

/**
 * A block of pasted text parked in the composer as a chip rather than poured into the input box.
 *
 * The problem this solves is the one every chat composer has: a 400-line stack trace pasted into a
 * few-row textarea buries the sentence the user was writing, and there is no way back to it except
 * scrolling. Holding it as an attachment keeps the *prompt* readable while the content still travels
 * with the message — the chip is a handle on something the user can see the size of, remove, and know
 * will be sent.
 *
 * [ordinal] is monotonic per conversation for the same reason [PendingImage]'s is: removing
 * "Pasted text 1" must never renumber "Pasted text 2" under the user's eyes.
 */
class PendingText(
    val id: String,
    val ordinal: Int,
    val text: String,
) {
    val lineCount: Int = TextAttachmentPolicy.lineCount(text)
    val charCount: Int = text.length
}

/**
 * When a paste becomes an attachment instead of characters in the input, how much of it is allowed,
 * and the exact wording of every label and refusal. Platform-free and unit-tested; the composer reads
 * its numbers from here rather than embedding copies.
 */
object TextAttachmentPolicy {

    /**
     * Above either of these, a paste becomes a chip. Two triggers rather than one because the failure
     * is *unreadability*, and text reaches it two different ways: 30 lines is about where a paste stops
     * fitting the composer and starts scrolling the prompt out of view, and 2000 characters is where a
     * few very long lines (minified JSON, a single-line log) do the same damage with no line count to
     * show for it. Either alone is enough.
     */
    const val MAX_INLINE_LINES = 30
    const val MAX_INLINE_CHARS = 2000

    /** Per-message cap on pasted-text chips, matching the images' — past a handful, send and continue. */
    const val MAX_TEXTS = 8

    /**
     * Absolute per-attachment cap. The CLI takes the message as one stdin line, and a multi-megabyte
     * paste is a file, not a message — refused with a stated reason and a stated alternative (attach
     * the file), never truncated, because silently sending part of what someone pasted is worse than
     * sending none of it.
     */
    const val HARD_MAX_CHARS = 400_000

    /** What became of an attempted paste. The composer renders each outcome differently. */
    enum class AddTextResult { ADDED, REJECTED_LIMIT, REJECTED_TOO_LARGE }

    /** Lines as a human counts them: a trailing newline doesn't add an empty last line. */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        val body = text.removeSuffix("\n").removeSuffix("\r")
        return body.count { it == '\n' } + 1
    }

    /**
     * Whether this paste is large enough to become a chip. Blank text never is — there is nothing to
     * hold — and neither is anything the composer can simply show.
     */
    fun shouldAttach(text: String): Boolean =
        text.isNotBlank() && (text.length > MAX_INLINE_CHARS || lineCount(text) > MAX_INLINE_LINES)

    /** Chip label: the name alone. Size lives in [chipDetail], drawn muted beside it. */
    fun chipLabel(t: PendingText): String = "Pasted text ${t.ordinal}"

    /** The muted half of the chip: `412 lines`, or a character count when it is all one line. */
    fun chipDetail(t: PendingText): String = when {
        t.lineCount <= 1 -> "${formatCount(t.charCount)} chars"
        else -> "${formatCount(t.lineCount)} lines"
    }

    /** Tooltip: the size, then the opening of the text, so a chip can be told from its neighbour. */
    fun tooltip(t: PendingText): String {
        val size = "${formatCount(t.lineCount)} lines · ${formatCount(t.charCount)} characters"
        return "Pasted text ${t.ordinal} · $size\n\n" + preview(t.text)
    }

    /** First few lines, each clipped, for the tooltip. Never the whole thing — that is the point. */
    fun preview(text: String, maxLines: Int = 6, maxLineChars: Int = 100): String {
        val lines = text.lineSequence().take(maxLines)
            .map { if (it.length > maxLineChars) it.take(maxLineChars) + "…" else it }
            .toList()
        val more = lineCount(text) - lines.size
        return lines.joinToString("\n") + if (more > 0) "\n… ${formatCount(more)} more lines" else ""
    }

    /**
     * Said in the transcript when a paste is turned into a chip. A paste that appears to do nothing to
     * the input box is indistinguishable from one that failed, so the first time it happens the user is
     * told what became of their text and that it is still going to be sent.
     */
    fun attachedNotice(t: PendingText): String =
        "That paste was attached as “Pasted text ${t.ordinal}” (${chipDetail(t)}) instead of filling " +
            "the input box. It is sent with your next message; remove the chip to drop it."

    fun limitMessage(): String =
        "Text not attached: a message can carry at most $MAX_TEXTS pasted blocks. Send these, then paste the next one."

    fun tooLargeMessage(chars: Int): String =
        "Text not attached: ${formatCount(chars)} characters, above the ${formatCount(HARD_MAX_CHARS)} limit for a " +
            "pasted block. Save it to a file and attach the file instead."

    /** Said when a slash command leaves pasted text behind — see [ComposerModel.buildMessage]. */
    fun droppedByCommandMessage(count: Int): String {
        val what = if (count == 1) "The pasted text block was" else "All $count pasted text blocks were"
        return "$what left out: a slash command has to be the only thing in the message, or the CLI " +
            "treats it as an ordinary prompt. They are still attached for your next message."
    }

    /**
     * How a pasted block is written into the message: a titled fence.
     *
     * The fence is made **longer than the longest backtick run inside the text**, which is what
     * CommonMark requires and what keeps a pasted Markdown document — the obvious thing to paste —
     * from closing its own fence a third of the way through and leaving the rest as prose.
     */
    fun promptBlock(t: PendingText): String {
        val fence = fenceFor(t.text)
        return "Pasted text ${t.ordinal}:\n$fence\n${t.text.removeSuffix("\n")}\n$fence"
    }

    /** At least three backticks, and always more than any run occurring in [text]. */
    fun fenceFor(text: String): String {
        var longest = 0
        var run = 0
        for (c in text) {
            if (c == '`') { run++; if (run > longest) longest = run } else run = 0
        }
        return "`".repeat(maxOf(3, longest + 1))
    }

    /** `412`, `4,120` — grouped, because a bare `412000` is read wrong at a glance. */
    fun formatCount(n: Int): String {
        val s = n.toString()
        if (s.length <= 3) return s
        return s.reversed().chunked(3).joinToString(",").reversed()
    }
}
