package io.mp.sightline.ui.markdown

/**
 * The platform-free core of **live** Markdown rendering: while a message streams, the whole
 * accumulated text is re-parsed on a throttled tick, but only the components whose blocks actually
 * changed are rebuilt. Markdown blocks are separated by structure (blank lines, fences, headings),
 * so appending tokens almost always mutates only the *last* block — everything before it parses to
 * a structurally identical [MdBlock] and its Swing component can be kept as-is. That is what makes
 * per-tick rendering affordable: the cost of a tick is one parse plus rebuilding the tail block,
 * not rebuilding the whole transcript entry.
 *
 * Correctness leans on the model being plain data classes: two blocks are "the same" iff they are
 * structurally equal, never by position or guesswork. A block that *does* change retroactively —
 * a paragraph promoted to a setext heading by its `===` underline, a list absorbing a new item —
 * simply compares unequal and is rebuilt from that point on.
 */
object StreamingMarkdown {

    /**
     * How many leading blocks of [next] are structurally identical to [prev] and can keep their
     * already-rendered components. The caller drops components from this index on and renders
     * `next.subList(stablePrefix, next.size)` fresh.
     */
    fun stablePrefix(prev: List<MdBlock>, next: List<MdBlock>): Int {
        val n = minOf(prev.size, next.size)
        var i = 0
        while (i < n && prev[i] == next[i]) i++
        return i
    }

    /** Coalescing interval between live render ticks — fast enough to feel live, slow enough that a tick's parse cost never competes with token intake. */
    const val TICK_MS = 150

    /** Past this many chars a message re-parses on a slower tick: parse cost grows with length, and a document this long is beyond live reading speed anyway. */
    const val LONG_DOC_CHARS = 100_000

    /** The slower tick used past [LONG_DOC_CHARS]. */
    const val LONG_DOC_TICK_MS = 1_000

    /** The tick interval appropriate for a document of [length] chars. */
    fun tickMs(length: Int): Int = if (length > LONG_DOC_CHARS) LONG_DOC_TICK_MS else TICK_MS
}
