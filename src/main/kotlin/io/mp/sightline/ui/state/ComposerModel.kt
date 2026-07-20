package io.mp.sightline.ui.state

import io.mp.sightline.android.ContextChipKind

/**
 * Presentation state for the composer: attached-context files, Android context chips, and send/stop
 * enablement. Structured attachments are kept as project-relative paths and only rendered into Claude's
 * `@mention` prompt format at submit time, so the input box never carries raw `@path` noise.
 * Platform-free/testable.
 */
class ComposerModel {

    private val attachmentsSet = LinkedHashSet<String>()
    var running: Boolean = false

    // ---- Android context (docs/ANDROID.md M1) ----

    /**
     * Which context chips contribute to the next message. The chips *are* the control: unchecking one
     * genuinely drops it from the prompt rather than just hiding a label describing it.
     */
    private val enabledChips = ContextChipKind.DEFAULT_ENABLED.toMutableSet()

    /**
     * Supplies the Android context block at send time. A supplier rather than a stored string because
     * facts go stale: a message typed before an emulator booted must not still claim there was no device
     * when it is finally sent. Defaults to contributing nothing, which is what a non-Android project and
     * every existing test see.
     */
    var androidContextBlock: (Set<ContextChipKind>) -> String = { "" }

    val enabledContextChips: Set<ContextChipKind> get() = enabledChips.toSet()

    fun isChipEnabled(kind: ContextChipKind): Boolean = kind in enabledChips

    fun setChipEnabled(kind: ContextChipKind, enabled: Boolean) {
        if (enabled) enabledChips += kind else enabledChips -= kind
    }

    fun removeContextChip(kind: ContextChipKind) = setChipEnabled(kind, false)

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

    /**
     * Builds the message Claude receives: the Android context block, then attachments as leading
     * `@mentions`, then the prompt.
     *
     * Context leads because it is framing rather than content — the model should know which variant and
     * device it is reasoning about before it reads the request. It is gathered **here, at send time**,
     * which matters for a queued message: one typed before an emulator booted is sent after it did, and
     * must describe the device that exists now, not the absence that existed while it waited.
     *
     * A blank body still sends: attaching a file and pressing Enter is a legitimate "look at this".
     */
    fun buildMessage(text: String): String {
        val body = text.trim()
        val context = if (enabledChips.isEmpty()) "" else androidContextBlock(enabledContextChips)
        val mentions = attachmentsSet.joinToString(" ") { "@$it" }
        return listOf(context, mentions, body).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
