package io.mp.claudecodepanel.ui.markdown

/**
 * Finds candidate **project-file references** in Markdown text — `CLAUDE.md`, `docs/ARCHITECTURE.md`,
 * `MainActivity.kt`, `Foo.kt:420`, `src/main/Foo.kt#L18`. Platform-free and unit-tested. Detection is
 * deliberately conservative — only tokens ending in a known source/doc extension qualify, so version
 * numbers (`0.6.0`), abbreviations (`e.g.`) and URLs never match — and whether a candidate is actually
 * a real, unique project file is decided *later* by an injected resolver (never guessed here).
 */
object FileRefDetector {

    /** A detected reference: its [start]/[end] offsets in the source text, the [path], and optional [line]. */
    data class FileRef(val start: Int, val end: Int, val path: String, val line: Int?) {
        /** The link target the renderer/handler round-trips: `file:<path>[:line]`. */
        fun href(): String = "file:$path" + (line?.let { ":$it" } ?: "")
    }

    private const val EXT = "kt|kts|java|xml|gradle|md|markdown|json|ya?ml|properties|txt|pro|cfg|toml|sql|proto"
    private val REF = Regex(
        "(?<![\\w./#-])((?:[\\w.-]+/)*[\\w.-]+\\.(?:$EXT))(?::(\\d+)|#L(\\d+))?(?![\\w/])",
        RegexOption.IGNORE_CASE,
    )

    fun detect(text: String): List<FileRef> = REF.findAll(text).map { m ->
        val line = (m.groupValues[2].ifBlank { m.groupValues[3] }).toIntOrNull()
        FileRef(m.range.first, m.range.last + 1, m.groupValues[1], line)
    }.toList()

    /**
     * Rewrites the parsed blocks so every detected file reference that [resolves] confirms becomes an
     * [MdLink] with a `file:` href; unresolved candidates stay plain text. Pure — [resolves] injects the
     * platform's project lookup, so this is testable with a fake. Inline code and existing links are left
     * untouched.
     */
    fun linkify(blocks: List<MdBlock>, resolves: (String) -> Boolean): List<MdBlock> = blocks.map { block(it, resolves) }

    private fun block(b: MdBlock, resolves: (String) -> Boolean): MdBlock = when (b) {
        is MdParagraph -> MdParagraph(inlines(b.inlines, resolves))
        is MdHeading -> MdHeading(b.level, inlines(b.inlines, resolves))
        is MdList -> MdList(b.ordered, b.start, b.items.map { MdListItem(it.blocks.map { c -> block(c, resolves) }, it.task) })
        is MdQuote -> MdQuote(b.blocks.map { block(it, resolves) })
        is MdCallout -> MdCallout(b.kind, b.blocks.map { block(it, resolves) })
        is MdTable -> MdTable(
            b.header.map { inlines(it, resolves) },
            b.rows.map { row -> row.map { inlines(it, resolves) } },
            b.alignments,
        )
        is MdCodeBlock, MdThematicBreak -> b
    }

    private fun inlines(inlines: List<MdInline>, resolves: (String) -> Boolean): List<MdInline> = inlines.flatMap { inl ->
        when (inl) {
            is MdText -> splitRefs(inl.text, resolves)
            is MdBold -> listOf(MdBold(inlines(inl.inlines, resolves)))
            is MdItalic -> listOf(MdItalic(inlines(inl.inlines, resolves)))
            is MdStrikethrough -> listOf(MdStrikethrough(inlines(inl.inlines, resolves)))
            is MdCode, is MdLink -> listOf(inl)
        }
    }

    private fun splitRefs(text: String, resolves: (String) -> Boolean): List<MdInline> {
        val refs = detect(text).filter { resolves(it.path) }
        if (refs.isEmpty()) return listOf(MdText(text))
        val out = ArrayList<MdInline>()
        var cursor = 0
        for (ref in refs) {
            if (ref.start > cursor) out.add(MdText(text.substring(cursor, ref.start)))
            out.add(MdLink(listOf(MdText(text.substring(ref.start, ref.end))), ref.href()))
            cursor = ref.end
        }
        if (cursor < text.length) out.add(MdText(text.substring(cursor)))
        return out
    }
}
