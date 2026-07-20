package io.mp.sightline.ui.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Adapts the platform-bundled `org.intellij.markdown` CommonMark+GFM parser into the platform-free
 * [MdBlock] model. This is the one place that knows the parser library's AST shape (node **type names**,
 * verified empirically against the real tree), so the parser is swappable and the rest of the Markdown
 * layer never imports it. Headless and deterministic → fully unit-tested.
 *
 * Robust by construction: unknown block nodes fall back to a plain paragraph (never dropped), and
 * [parse] catches any parser exception and returns the whole input as a single plain paragraph, so a
 * malformed or half-streamed message always renders as readable text.
 */
object MarkdownDocParser {

    fun parse(text: String): List<MdBlock> = try {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        root.children.mapNotNull { block(it, text) }.ifEmpty { plainFallback(text) }
    } catch (e: Exception) {
        plainFallback(text)
    }

    private fun plainFallback(text: String): List<MdBlock> =
        if (text.isBlank()) emptyList() else listOf(MdParagraph(listOf(MdText(text))))

    // ---- blocks ----

    private fun block(node: ASTNode, text: String): MdBlock? {
        val name = node.type.name
        return when {
            name.startsWith("ATX_") -> MdHeading(name.removePrefix("ATX_").toIntOrNull() ?: 1, trimEnds(inlines(atxContent(node), text)))
            name == "SETEXT_1" -> MdHeading(1, trimEnds(inlines(node, text)))
            name == "SETEXT_2" -> MdHeading(2, trimEnds(inlines(node, text)))
            name == "PARAGRAPH" -> MdParagraph(inlines(node, text))
            name == "UNORDERED_LIST" -> list(node, text, ordered = false)
            name == "ORDERED_LIST" -> list(node, text, ordered = true)
            name == "CODE_FENCE" -> codeFence(node, text)
            name == "CODE_BLOCK" -> MdCodeBlock(null, dedentIndentedCode(node.getTextInNode(text).toString()))
            name == "BLOCK_QUOTE" -> calloutOf(node.getTextInNode(text).toString())
                ?: MdQuote(node.children.mapNotNull { quoteChild(it, text) })
            name == "TABLE" -> table(node, text)
            name == "HORIZONTAL_RULE" -> MdThematicBreak
            name == "EOL" || name == "WHITE_SPACE" -> null
            else -> node.getTextInNode(text).toString().takeIf { it.isNotBlank() }?.let { MdParagraph(listOf(MdText(it.trim()))) }
        }
    }

    private fun atxContent(node: ASTNode): ASTNode =
        node.children.firstOrNull { it.type.name == "ATX_CONTENT" } ?: node

    // GitHub-style alert: a block quote whose first inner line is [!NOTE|TIP|IMPORTANT|WARNING|CAUTION].
    private val CALLOUT_MARKER = Regex("^\\s*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*", RegexOption.IGNORE_CASE)
    private val QUOTE_MARKER = Regex("^\\s*>\\s?")

    /**
     * A callout iff the block quote's first inner line is an explicit `[!KIND]` marker — never inferred
     * from prose. Works off the **raw** quote text (the parser mangles `[!WARNING]` into a reference
     * link), stripping the `>` markers and the alert marker, then re-parsing the remainder as the body.
     */
    private fun calloutOf(rawQuote: String): MdCallout? {
        val inner = rawQuote.lines().joinToString("\n") { it.replaceFirst(QUOTE_MARKER, "") }
        val m = CALLOUT_MARKER.find(inner) ?: return null
        val kind = MdCalloutKind.valueOf(m.groupValues[1].uppercase())
        return MdCallout(kind, parse(inner.substring(m.range.last + 1).trim()))
    }

    private fun quoteChild(node: ASTNode, text: String): MdBlock? = when {
        // The "> " marker is itself a childless BLOCK_QUOTE node; skip it and structural whitespace.
        node.type.name == "BLOCK_QUOTE" && node.children.isEmpty() -> null
        node.type.name == "EOL" || node.type.name == "WHITE_SPACE" -> null
        else -> block(node, text)
    }

    private fun list(node: ASTNode, text: String, ordered: Boolean): MdList {
        val items = node.children.filter { it.type.name == "LIST_ITEM" }.map { listItem(it, text) }
        val start = if (ordered) {
            node.children.firstOrNull { it.type.name == "LIST_ITEM" }
                ?.children?.firstOrNull { it.type.name == "LIST_NUMBER" }
                ?.getTextInNode(text)?.toString()?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 1
        } else 1
        return MdList(ordered, start, items)
    }

    private fun listItem(node: ASTNode, text: String): MdListItem {
        val task = node.children.firstOrNull { it.type.name == "CHECK_BOX" }?.let {
            if (it.getTextInNode(text).contains('x', ignoreCase = true)) MdTask.CHECKED else MdTask.UNCHECKED
        } ?: MdTask.NONE
        val blocks = node.children.mapNotNull { child ->
            when (child.type.name) {
                "LIST_BULLET", "LIST_NUMBER", "CHECK_BOX", "EOL", "WHITE_SPACE" -> null
                else -> block(child, text)
            }
        }
        return MdListItem(blocks, task)
    }

    private fun codeFence(node: ASTNode, text: String): MdCodeBlock {
        val lang = node.children.firstOrNull { it.type.name == "FENCE_LANG" }
            ?.getTextInNode(text)?.toString()?.trim()?.ifBlank { null }
        val firstEol = node.children.firstOrNull { it.type.name == "EOL" }
        val endFence = node.children.firstOrNull { it.type.name == "CODE_FENCE_END" }
        val code = if (firstEol != null) {
            val from = firstEol.endOffset
            val to = (endFence?.startOffset ?: node.endOffset).coerceAtLeast(from)
            text.substring(from, to).removeSuffix("\n")
        } else {
            // Single-line / unterminated fence: strip the opening fence line only.
            node.getTextInNode(text).toString().substringAfter('\n', "").removeSuffix("\n").removeSuffix("```").trimEnd()
        }
        return MdCodeBlock(lang, code)
    }

    private fun dedentIndentedCode(raw: String): String =
        raw.trimEnd().lines().joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }

    private fun table(node: ASTNode, text: String): MdTable {
        val header = node.children.firstOrNull { it.type.name == "HEADER" }
            ?.let { cells(it, text) } ?: emptyList()
        val rows = node.children.filter { it.type.name == "ROW" }.map { cells(it, text) }
        val aligns = node.children.firstOrNull { it.type.name == "TABLE_SEPARATOR" && it.children.isEmpty() }
            ?.let { parseAligns(it.getTextInNode(text).toString()) } ?: List(header.size) { MdAlign.LEFT }
        return MdTable(header, rows, aligns)
    }

    private fun cells(row: ASTNode, text: String): List<List<MdInline>> =
        row.children.filter { it.type.name == "CELL" }.map { trimEnds(inlines(it, text)) }

    private fun parseAligns(separator: String): List<MdAlign> =
        separator.split('|').map { it.trim() }.filter { it.contains('-') }.map { seg ->
            val left = seg.startsWith(':'); val right = seg.endsWith(':')
            when {
                left && right -> MdAlign.CENTER
                right -> MdAlign.RIGHT
                else -> MdAlign.LEFT
            }
        }

    // ---- inlines ----

    /**
     * @param inLinkLabel true while walking a link's `LINK_TEXT`, whose own children include the
     *   surrounding `[` / `]` tokens. Those are structural *there* and must be dropped — but the same
     *   token types also carry **literal** brackets in ordinary prose, so they may only be dropped in
     *   that scope. Parentheses are never structural here at all (`link()` reads `LINK_DESTINATION`
     *   directly and `inlines()` is never called on the `INLINE_LINK` node), so they are always text.
     *   Dropping them globally silently deleted every literal "(…)" from assistant prose.
     */
    private fun inlines(node: ASTNode, text: String, inLinkLabel: Boolean = false): List<MdInline> {
        val out = ArrayList<MdInline>()
        for (c in node.children) {
            when (c.type.name) {
                "TEXT", "WHITE_SPACE", "SINGLE_QUOTE", "DOUBLE_QUOTE", "COLON",
                "HTML_TAG", "ENTITY", "ESCAPED_BACKTICKS", "CODE_LINE" ->
                    out.add(MdText(c.getTextInNode(text).toString()))
                "EOL" -> out.add(MdText(" ")) // soft line break → space
                "STRONG" -> out.add(MdBold(inlines(c, text)))
                "EMPH" -> if (c.children.isNotEmpty()) out.add(MdItalic(inlines(c, text))) // else: delimiter token
                "STRIKETHROUGH" -> out.add(MdStrikethrough(inlines(c, text)))
                "CODE_SPAN" -> out.add(MdCode(codeSpan(c, text)))
                "INLINE_LINK", "FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK" -> out.add(link(c, text))
                "GFM_AUTOLINK", "AUTOLINK", "EMAIL_AUTOLINK" -> {
                    val url = c.getTextInNode(text).toString().trim('<', '>')
                    out.add(MdLink(listOf(MdText(url)), url))
                }
                // Structural only inside a link label; literal text anywhere else.
                "[", "]" -> if (!inLinkLabel) out.add(MdText(c.getTextInNode(text).toString()))
                // Never structural in this walk — always literal prose.
                "(", ")" -> out.add(MdText(c.getTextInNode(text).toString()))
                // Delimiter / punctuation leaf tokens carried inside inline containers — drop them.
                "BACKTICK", "EMPH_MARKER", "!", "LT", "GT" -> Unit
                else ->
                    if (c.children.isEmpty()) {
                        val t = c.getTextInNode(text).toString()
                        // Skip lone punctuation delimiters (e.g. "~", "*"); keep real text.
                        if (t.isNotEmpty() && !(t.length <= 2 && t.all { it == '~' || it == '*' || it == '_' || it == '`' })) out.add(MdText(t))
                    } else {
                        out.addAll(inlines(c, text))
                    }
            }
        }
        return out
    }

    private fun codeSpan(node: ASTNode, text: String): String =
        node.children.filter { it.type.name != "BACKTICK" }
            .joinToString("") { it.getTextInNode(text) }.trim()

    private fun link(node: ASTNode, text: String): MdInline {
        val labelNode = node.children.firstOrNull { it.type.name == "LINK_TEXT" }
        val label = labelNode?.let { inlines(it, text, inLinkLabel = true) }?.let { trimEnds(it) }
            ?: listOf(MdText(node.getTextInNode(text).toString()))
        val href = node.children.firstOrNull { it.type.name == "LINK_DESTINATION" }
            ?.getTextInNode(text)?.toString()?.trim('<', '>')?.trim() ?: ""
        return MdLink(label.ifEmpty { listOf(MdText(href)) }, href)
    }

    /** Trims leading/trailing whitespace-only text runs (for headings and table cells). */
    private fun trimEnds(inlines: List<MdInline>): List<MdInline> {
        var lo = 0; var hi = inlines.size
        while (lo < hi && (inlines[lo] as? MdText)?.text?.isBlank() == true) lo++
        while (hi > lo && (inlines[hi - 1] as? MdText)?.text?.isBlank() == true) hi--
        return inlines.subList(lo, hi).toList()
    }
}
