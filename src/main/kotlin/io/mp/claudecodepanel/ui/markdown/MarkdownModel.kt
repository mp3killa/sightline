package io.mp.claudecodepanel.ui.markdown

/**
 * A platform-free presentation model for a parsed Markdown message. [MarkdownDocParser] builds this from
 * the bundled CommonMark/GFM parser; the Swing layer (`BlockRenderer`) turns it into components. Keeping
 * the model Swing-free means the parse is fully headless-unit-tested (mirrors `activity/`, `ui/state/`).
 */

sealed interface MdBlock

data class MdHeading(val level: Int, val inlines: List<MdInline>) : MdBlock
data class MdParagraph(val inlines: List<MdInline>) : MdBlock
data class MdList(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
data class MdCodeBlock(val language: String?, val code: String) : MdBlock
data class MdQuote(val blocks: List<MdBlock>) : MdBlock
data class MdTable(
    val header: List<List<MdInline>>,
    val rows: List<List<List<MdInline>>>,
    val alignments: List<MdAlign>,
) : MdBlock
object MdThematicBreak : MdBlock
/** A restrained note/warning callout, derived from an explicit `> [!WARNING]`-style block quote (Phase 2). */
data class MdCallout(val kind: MdCalloutKind, val blocks: List<MdBlock>) : MdBlock

data class MdListItem(val blocks: List<MdBlock>, val task: MdTask = MdTask.NONE)

enum class MdTask { NONE, UNCHECKED, CHECKED }
enum class MdAlign { LEFT, CENTER, RIGHT }
enum class MdCalloutKind { NOTE, TIP, IMPORTANT, WARNING, CAUTION }

sealed interface MdInline

data class MdText(val text: String) : MdInline
data class MdBold(val inlines: List<MdInline>) : MdInline
data class MdItalic(val inlines: List<MdInline>) : MdInline
data class MdStrikethrough(val inlines: List<MdInline>) : MdInline
data class MdCode(val text: String) : MdInline
/** A hyperlink. [href] is a URL for real links, or empty for reference links we can't resolve. */
data class MdLink(val inlines: List<MdInline>, val href: String) : MdInline

/** Flattens inline content to plain text (for tooltips, link labels, accessibility, tests). */
fun List<MdInline>.plainText(): String = joinToString("") { it.plainText() }

fun MdInline.plainText(): String = when (this) {
    is MdText -> text
    is MdBold -> inlines.plainText()
    is MdItalic -> inlines.plainText()
    is MdStrikethrough -> inlines.plainText()
    is MdCode -> text
    is MdLink -> inlines.plainText()
}
