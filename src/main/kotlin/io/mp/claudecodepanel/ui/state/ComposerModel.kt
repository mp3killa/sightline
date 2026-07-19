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

    /** Send is enabled only when idle and there is prompt text. */
    fun sendEnabled(text: String): Boolean = !running && text.isNotBlank()

    /** Builds the message Claude receives: attachments as leading `@mentions`, then the prompt. */
    fun buildMessage(text: String): String {
        val body = text.trim()
        if (attachmentsSet.isEmpty()) return body
        val mentions = attachmentsSet.joinToString(" ") { "@$it" }
        return if (body.isEmpty()) mentions else "$mentions\n\n$body"
    }
}
