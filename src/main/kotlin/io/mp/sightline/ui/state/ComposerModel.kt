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

    // ---- pasted images ----

    private val imagesList = mutableListOf<PendingImage>()
    private var nextImageOrdinal = 1

    /** Encoded clipboard images riding the next message, in paste order. */
    val images: List<PendingImage> get() = imagesList.toList()
    val hasImages: Boolean get() = imagesList.isNotEmpty()

    /**
     * Accepts a pasted image, or refuses with a typed reason — [ImageAttachmentPolicy] words the
     * refusal so the user is told *why* nothing appeared, never left with a silent no-op paste.
     * Ordinals are monotonic per conversation: removing "Image 1" never renames "Image 2".
     */
    fun addImage(encoded: EncodedImage): ImageAttachmentPolicy.AddImageResult {
        if (imagesList.size >= ImageAttachmentPolicy.MAX_IMAGES) {
            return ImageAttachmentPolicy.AddImageResult.REJECTED_LIMIT
        }
        if (encoded.bytes.size > ImageAttachmentPolicy.HARD_MAX_BYTES) {
            return ImageAttachmentPolicy.AddImageResult.REJECTED_TOO_LARGE
        }
        val ordinal = nextImageOrdinal++
        imagesList += PendingImage(id = "img-$ordinal", ordinal = ordinal, image = encoded)
        return ImageAttachmentPolicy.AddImageResult.ADDED
    }

    fun removeImage(id: String): Boolean = imagesList.removeAll { it.id == id }
    fun clearImages() = imagesList.clear()

    /**
     * Takes the pending images for a message leaving *now* — reads then clears, so an image pasted
     * after this instant belongs to the next message, never accidentally to this one.
     */
    fun takeImages(): List<PendingImage> = imagesList.toList().also { imagesList.clear() }

    /** What submitting the composer did — the caller renders each outcome differently. */
    enum class Submit { SENT, QUEUED, IGNORED_BLANK }

    /**
     * A message parked behind a running turn. Its images were captured at Enter-time: a pasted
     * screenshot is *content*, frozen at the moment the user submitted — unlike the Android context,
     * which is framing and is deliberately re-gathered at send time (see [buildMessage]).
     */
    data class QueuedMessage(val text: String, val images: List<PendingImage> = emptyList())

    private val queue = ArrayDeque<QueuedMessage>()

    /** Messages waiting for the current turn to finish, oldest first. */
    val queued: List<QueuedMessage> get() = queue.toList()
    val hasQueued: Boolean get() = queue.isNotEmpty()

    /**
     * Send is enabled whenever there is something to send — text, a pasted image, or an attached
     * file; "look at this" with no prose is a legitimate message. While a turn is running the
     * message is **queued** rather than rejected. Previously this was `!running && …`, and because
     * the input was never disabled a user could type a whole message, press Enter, and have nothing
     * happen with no feedback at all.
     */
    fun sendEnabled(text: String): Boolean = text.isNotBlank() || hasImages || hasAttachments

    /**
     * Submits [text]: sent now when idle, queued when a turn is in flight. Truly empty input — no
     * text, no images, no attachments — is ignored, never queued, so an accidental Enter doesn't
     * schedule an empty turn. Queuing captures the pending images into the entry (and clears them),
     * so an image pasted while the entry waits belongs to the *next* message.
     */
    fun submit(text: String): Submit = when {
        text.isBlank() && !hasImages && !hasAttachments -> Submit.IGNORED_BLANK
        running -> { queue.addLast(QueuedMessage(text, takeImages())); Submit.QUEUED }
        else -> Submit.SENT
    }

    /** Pops the next queued message, or null when nothing is waiting. */
    fun takeQueued(): QueuedMessage? = queue.removeFirstOrNull()

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
