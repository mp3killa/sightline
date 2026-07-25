package io.mp.sightline.vcs

/**
 * Platform-free, unit-tested assembly of the one-shot prompt that turns a unified diff into a commit
 * message, plus the cleanup of the model's reply. Kept separate from the CLI runner so the wording,
 * truncation and output-scrubbing are testable without a process.
 */
object CommitMessagePrompt {

    /**
     * The diff is truncated past this many characters: a giant changeset would otherwise blow the
     * process-argument limit and swamp a fast model's context for no gain. The model still sees a
     * representative head of the diff, and truncation is stated in the prompt so it knows.
     */
    const val MAX_DIFF_CHARS = 60_000

    private val BASE = """
        Write a git commit message for the changes shown in the unified diff below.

        Rules:
        - First line: a concise, imperative summary of at most 72 characters, with no trailing period.
        - Then a blank line, then a short body (wrapped near 72 columns) explaining what changed and why —
          but only if it adds information beyond the summary. Omit the body for a trivial change.
        - Describe only what the diff actually shows; do not invent motivations or features.
        - Output ONLY the commit message: no preamble, no explanation, no code fences, no surrounding quotes.
    """.trimIndent()

    fun build(diff: String, extraInstructions: String = ""): String {
        val (trimmed, truncated) = truncate(diff)
        val extra = extraInstructions.trim()
        val extraBlock = if (extra.isEmpty()) "" else "\n\nAdditional style guidance from the user:\n$extra"
        val note = if (truncated) "\n\n[diff truncated to the first $MAX_DIFF_CHARS characters]" else ""
        return "$BASE$extraBlock\n\nDiff:\n$trimmed$note"
    }

    fun truncate(diff: String): Pair<String, Boolean> =
        if (diff.length <= MAX_DIFF_CHARS) diff to false else diff.take(MAX_DIFF_CHARS) to true

    /**
     * Scrubs the model's reply into a bare commit message: strips a wrapping ``` code fence and
     * surrounding quotes, and trims blank edges. A fast model occasionally wraps its answer despite the
     * instruction, and a fenced/quoted message committed verbatim would be wrong.
     */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val afterOpen = s.removePrefix("```").substringAfter('\n', "")
            val close = afterOpen.lastIndexOf("```")
            s = (if (close >= 0) afterOpen.substring(0, close) else afterOpen).trim()
        }
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1).trim()
        return s
    }
}
