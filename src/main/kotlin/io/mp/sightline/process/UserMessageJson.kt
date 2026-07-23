package io.mp.sightline.process

/**
 * Builds the `{"type":"user",...}` stream-json line a message travels to the CLI on. Extracted from
 * [ClaudeSession] so the wire format is unit-tested: the text-only shape must stay byte-identical to
 * what the CLI has always received, and the image shape must match the Messages API content-block
 * format exactly — a malformed line here fails silently inside another process.
 *
 * With images the `content` field becomes a block array: image blocks first, then the text block —
 * the ordering the API documents as best for vision. Blank text with images sends image blocks only;
 * a user message need not contain text at all.
 */
object UserMessageJson {

    /** One image ready for the wire. [base64Data] is standard base64, no line breaks. */
    class ImageBlock(val mediaType: String, val base64Data: String)

    fun userLine(text: String, images: List<ImageBlock> = emptyList()): String {
        if (images.isEmpty()) {
            return """{"type":"user","message":{"role":"user","content":"${escape(text)}"}}"""
        }
        val blocks = StringBuilder()
        for (img in images) {
            if (blocks.isNotEmpty()) blocks.append(',')
            blocks.append("""{"type":"image","source":{"type":"base64","media_type":"${escape(img.mediaType)}","data":"${img.base64Data}"}}""")
        }
        if (text.isNotBlank()) {
            blocks.append(""",{"type":"text","text":"${escape(text)}"}""")
        }
        return """{"type":"user","message":{"role":"user","content":[$blocks]}}"""
    }

    /** Minimal JSON string escape. [ClaudeSession] delegates here — one copy of this logic, not two. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // Control chars (backspace, form-feed, etc.) become valid \uXXXX escapes below.
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
