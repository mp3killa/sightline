package io.mp.sightline.ui.state

/**
 * The pure half of `@`-mention completion: given the composer's text and caret, is the user typing a
 * file reference, and what have they typed so far?
 *
 * Worth its own tested object because every interesting case is a *rejection*, and getting those
 * wrong is what makes a completion popup feel like it is fighting you: an `@` in an email address, an
 * `@` mid-word, a `@Composable` annotation pasted into the prompt, a reference the user already
 * finished typing. The Swing half only opens a popup when this says there is a query.
 *
 * The reference syntax is the CLI's own and is **verified**: `@path` and `@path#L2-3` are expanded by
 * the CLI before the model sees them (checked against 2.1.235 — the reply quoted the exact lines with
 * no `Read` call), so completing one produces a real reference rather than decorative text.
 */
object MentionQuery {

    /** [start] is the index of the `@` itself, so the caller can replace from there. */
    data class Query(val start: Int, val prefix: String)

    /** How many characters may follow the `@` before this stops looking like a filename being typed. */
    const val MAX_PREFIX = 120

    /**
     * The query under [caret], or null when the user is not typing a reference.
     *
     * An `@` only opens a reference when it starts a word — preceded by nothing, whitespace, or an
     * opening bracket. That one rule is what keeps `you@example.com` and `list@2x.png` from turning
     * the composer into a file browser mid-word.
     */
    fun at(text: String, caret: Int): Query? {
        if (caret < 1 || caret > text.length) return null
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '@') break
            // A filename can contain these; a sentence cannot continue through them unnoticed.
            if (c.isWhitespace() || c == '\n') return null
            i--
        }
        if (i < 0 || text[i] != '@') return null
        val before = if (i == 0) ' ' else text[i - 1]
        if (!(before.isWhitespace() || before == '(' || before == '[')) return null
        val prefix = text.substring(i + 1, caret)
        if (prefix.length > MAX_PREFIX) return null
        return Query(i, prefix)
    }

    /**
     * Ranks candidates for a prefix. A **filename** match beats a path match — typing `Claude` means
     * `ClaudePanel.kt`, not the first of forty paths that happen to contain the word — and a prefix
     * match beats a contained one, so the file you are naming is not buried under files that merely
     * mention it. Ties break on the shorter path, which is the less nested and usually the one meant.
     */
    fun rank(candidates: List<String>, prefix: String, limit: Int = 20): List<String> {
        if (prefix.isBlank()) return candidates.sortedBy { it.length }.take(limit)
        val needle = prefix.lowercase()
        return candidates
            .mapNotNull { path ->
                val lower = path.lowercase()
                val name = lower.substringAfterLast('/')
                val score = when {
                    name.startsWith(needle) -> 0
                    name.contains(needle) -> 1
                    lower.startsWith(needle) -> 2
                    lower.contains(needle) -> 3
                    else -> return@mapNotNull null
                }
                score to path
            }
            .sortedWith(compareBy({ it.first }, { it.second.length }, { it.second }))
            .map { it.second }
            .take(limit)
    }

    /**
     * The text that replaces the query once a path is chosen, trailing space included so the next word
     * does not run into the reference and stop being one.
     */
    fun completion(path: String): String = "@$path "
}
