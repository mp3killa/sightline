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

    // ---- pasted text ----

    private val textsList = mutableListOf<PendingText>()
    private var nextTextOrdinal = 1

    /** Large pastes held as chips rather than poured into the input, in paste order. */
    val texts: List<PendingText> get() = textsList.toList()
    val hasTexts: Boolean get() = textsList.isNotEmpty()

    /**
     * Accepts a large paste as an attachment, or refuses with a typed reason that
     * [TextAttachmentPolicy] words — a paste that appears to do nothing is the failure this must never
     * produce. Ordinals are monotonic, like the images': removing "Pasted text 1" never renames the
     * chip beside it.
     */
    fun addText(text: String): TextAttachmentPolicy.AddTextResult {
        if (textsList.size >= TextAttachmentPolicy.MAX_TEXTS) {
            return TextAttachmentPolicy.AddTextResult.REJECTED_LIMIT
        }
        if (text.length > TextAttachmentPolicy.HARD_MAX_CHARS) {
            return TextAttachmentPolicy.AddTextResult.REJECTED_TOO_LARGE
        }
        val ordinal = nextTextOrdinal++
        textsList += PendingText(id = "txt-$ordinal", ordinal = ordinal, text = text)
        return TextAttachmentPolicy.AddTextResult.ADDED
    }

    /** The chip just added, for the notice that says what became of the paste. */
    fun lastText(): PendingText? = textsList.lastOrNull()

    fun removeText(id: String): Boolean = textsList.removeAll { it.id == id }
    fun clearTexts() = textsList.clear()

    /** Reads then clears — a paste made after this instant belongs to the next message. */
    fun takeTexts(): List<PendingText> = textsList.toList().also { textsList.clear() }

    /**
     * Puts a queued message's captured blocks back as **chips**, not as characters in the input. A card's
     * Edit exists to revise the prose; re-inlining a 400-line paste into the textarea would undo the one
     * thing attaching it achieved.
     */
    fun restoreTexts(blocks: List<PendingText>) {
        for (t in blocks) {
            if (textsList.size >= TextAttachmentPolicy.MAX_TEXTS) break
            textsList.add(t)
        }
    }

    /** What submitting the composer did — the caller renders each outcome differently. */
    enum class Submit { SENT, INTERJECTED, QUEUED, IGNORED_BLANK }

    /**
     * A message parked behind a running turn. Its images were captured at Enter-time: a pasted
     * screenshot is *content*, frozen at the moment the user submitted — unlike the Android context,
     * which is framing and is deliberately re-gathered at send time (see [buildMessage]).
     */
    data class QueuedMessage(
        val text: String,
        val images: List<PendingImage> = emptyList(),
        val texts: List<PendingText> = emptyList(),
    )

    private val queue = ArrayDeque<QueuedMessage>()

    /** Messages waiting for the current turn to finish, oldest first. */
    val queued: List<QueuedMessage> get() = queue.toList()
    val hasQueued: Boolean get() = queue.isNotEmpty()

    /**
     * Whether a live session can take a message **right now** — the host wires this to "a CLI process is
     * alive and has not been asked to stop".
     *
     * When it can, a message submitted mid-turn is *interjected*: the CLI's streaming input folds it into
     * the work already in progress, so "also update the tests" reaches the agent while it is still on the
     * task instead of arriving as a fresh turn after it has moved on. When it can't — a Stop in flight, or
     * a process that has exited before the panel observed the exit — the message parks in [queued] and
     * goes out with the next turn, because writing to a stdin nobody is reading loses it silently.
     *
     * Defaults to the conservative answer: with no host wired, nothing is assumed to be listening.
     */
    var canInterject: () -> Boolean = { false }

    /**
     * Send is enabled whenever there is something to send — text, a pasted image, or an attached
     * file; "look at this" with no prose is a legitimate message. While a turn is running the
     * message is **interjected or queued** rather than rejected. Previously this was `!running && …`, and
     * because the input was never disabled a user could type a whole message, press Enter, and have
     * nothing happen with no feedback at all.
     */
    fun sendEnabled(text: String): Boolean = text.isNotBlank() || hasImages || hasAttachments || hasTexts

    /**
     * Submits [text]: sent now when idle, **interjected into the running turn** when one is in flight and
     * the session can take it ([canInterject]), and only parked in the queue when it can't. Truly empty
     * input — no text, no images, no attachments — is ignored in every case, so an accidental Enter
     * doesn't schedule an empty turn.
     *
     * Mid-turn used to mean *queued until the turn ended*, on the reasoning that two turns' output would
     * interleave unreadably. That reasoning doesn't apply to the delivery the CLI actually offers: a user
     * message written to its streaming stdin is folded into the work in progress at the agent's next step,
     * producing one continuous turn rather than two overlapping ones — and a follow-up's whole value is
     * usually that it lands *before* the agent finishes going the wrong way.
     *
     * Queuing captures the pending images into the entry (and clears them), so an image pasted while the
     * entry waits belongs to the *next* message.
     */
    fun submit(text: String): Submit = when {
        text.isBlank() && !hasImages && !hasAttachments && !hasTexts -> Submit.IGNORED_BLANK
        !running -> Submit.SENT
        canInterject() -> Submit.INTERJECTED
        else -> { queue.addLast(QueuedMessage(text, takeImages(), takeTexts())); Submit.QUEUED }
    }

    /** Pops the next queued message, or null when nothing is waiting. */
    fun takeQueued(): QueuedMessage? = queue.removeFirstOrNull()

    /** Removes and returns the queued message at [index] (a specific card's Cancel/Edit), or null. */
    fun removeQueuedAt(index: Int): QueuedMessage? = if (index in queue.indices) queue.removeAt(index) else null

    fun clearQueue() = queue.clear()

    /**
     * Puts a queued message's captured images back into the pending set — used by a card's **Edit**,
     * which pulls the message back into the composer for revision. Honours the per-message cap.
     */
    fun restoreImages(imgs: List<PendingImage>) {
        for (img in imgs) {
            if (imagesList.size >= ImageAttachmentPolicy.MAX_IMAGES) break
            imagesList.add(img)
        }
    }

    /** Placeholder text: says what Enter will actually do right now — send, fold in, or park. */
    fun placeholder(): String = when {
        !running -> "Ask Claude about this project…"
        canInterject() -> "Add to what Claude is doing…"
        else -> "Queue for the next turn…"
    }

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
    fun buildMessage(text: String, pasted: List<PendingText> = texts): String {
        val body = text.trim()
        // A slash command goes out **alone**. It only executes as a command when it is the first thing
        // in the message: prepending the Android context block turned `/context` into an ordinary
        // prompt that cost a turn and answered nothing, and an `@mention` before it cost three. The
        // framing that helps a prompt is the exact thing that stops a command being one.
        if (SlashCommands.isCommand(body)) return body
        val context = if (enabledChips.isEmpty()) "" else androidContextBlock(enabledContextChips)
        val mentions = attachmentsSet.joinToString(" ") { "@$it" }
        // Pasted blocks come **after** the prompt: they are the material the request is about, and a
        // request that starts with 400 lines of log buries itself. Each is fenced by
        // [TextAttachmentPolicy.promptBlock], titled with the same ordinal its chip showed.
        val blocks = pasted.joinToString("\n\n") { TextAttachmentPolicy.promptBlock(it) }
        return listOf(context, mentions, body, blocks).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * Whether sending [text] would leave pasted blocks behind, because it is a slash command and a
     * command must be the only thing in the message (the same rule that drops the context block and
     * `@mentions`). The caller says so and the blocks stay attached for the next message — the one
     * thing that must not happen is a chip quietly vanishing with content that never travelled.
     */
    fun commandWouldDropTexts(text: String, pasted: List<PendingText> = texts): Boolean =
        pasted.isNotEmpty() && SlashCommands.isCommand(text.trim())
}
