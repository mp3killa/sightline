package io.mp.claudecodepanel.ui.state

/**
 * Presentation state for the composer: attached-context files and send/stop enablement. Structured
 * attachments are kept as project-relative paths and only rendered into Claude's `@mention` prompt
 * format at submit time, so the input box never carries raw `@path` noise. Platform-free/testable.
 */
class ComposerModel {

    private val attachmentsSet = LinkedHashSet<String>()
    var running: Boolean = false

    val attachments: List<String> get() = attachmentsSet.toList()
    val hasAttachments: Boolean get() = attachmentsSet.isNotEmpty()

    /** @return true if the attachment was newly added. */
    fun addAttachment(relativePath: String): Boolean {
        val p = relativePath.trim().removePrefix("@").trim()
        return if (p.isEmpty()) false else attachmentsSet.add(p)
    }

    fun removeAttachment(relativePath: String): Boolean = attachmentsSet.remove(relativePath)
    fun clearAttachments() = attachmentsSet.clear()

    /** What submitting the composer did — the caller renders each outcome differently. */
    enum class Submit { SENT, QUEUED, IGNORED_BLANK }

    private val queue = ArrayDeque<String>()

    /** Messages waiting for the current turn to finish, oldest first. */
    val queued: List<String> get() = queue.toList()
    val hasQueued: Boolean get() = queue.isNotEmpty()

    /**
     * Send is enabled whenever there is text: while a turn is running the message is **queued** rather
     * than rejected. Previously this was `!running && …`, and because the input was never disabled a
     * user could type a whole message, press Enter, and have nothing happen with no feedback at all.
     */
    fun sendEnabled(text: String): Boolean = text.isNotBlank()

    /**
     * Submits [text]: sent now when idle, queued when a turn is in flight. Blank input is ignored —
     * never queued — so an accidental Enter doesn't schedule an empty turn.
     */
    fun submit(text: String): Submit = when {
        text.isBlank() -> Submit.IGNORED_BLANK
        running -> { queue.addLast(text); Submit.QUEUED }
        else -> Submit.SENT
    }

    /** Pops the next queued message, or null when nothing is waiting. */
    fun takeQueued(): String? = queue.removeFirstOrNull()

    fun clearQueue() = queue.clear()

    /** Placeholder text: says what Enter will actually do right now. */
    fun placeholder(): String =
        if (running) "Queue another message…" else "Ask Claude about this project…"

    /** "1 message queued" / "3 messages queued"; empty when nothing is waiting. */
    fun queueLabel(): String = when (queue.size) {
        0 -> ""
        1 -> "1 message queued"
        else -> "${queue.size} messages queued"
    }

    /** Builds the message Claude receives: attachments as leading `@mentions`, then the prompt. */
    fun buildMessage(text: String): String {
        val body = text.trim()
        if (attachmentsSet.isEmpty()) return body
        val mentions = attachmentsSet.joinToString(" ") { "@$it" }
        return if (body.isEmpty()) mentions else "$mentions\n\n$body"
    }
}
