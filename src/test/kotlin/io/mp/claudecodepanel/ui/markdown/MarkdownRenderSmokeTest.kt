package io.mp.claudecodepanel.ui.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.JTextPane

/**
 * Constructs the Swing component tree for a rich message under the real platform (theme, editor fonts)
 * and asserts it builds cleanly. Visual correctness is verified live in `runIde`; this guards against
 * construction-time exceptions and mismatched block/component counts. BasePlatformTestCase runs on the
 * EDT, so this exercises the components on the correct thread.
 */
class MarkdownRenderSmokeTest : BasePlatformTestCase() {

    private val richDoc = """
        # Title

        A paragraph with **bold**, *italic*, `code`, ~~old~~ and a [link](https://example.com).

        ## Section

        - one
        - two with `code`
          - nested
        - [x] done
        - [ ] todo

        1. first
        2. second

        | Doc | Status |
        |---|:-:|
        | CLAUDE.md | Exists |
        | AGENTS.md | Missing |

        ```kotlin
        fun foo() = 1
        ```

        > a quoted line

        ---
    """.trimIndent()

    fun testParsesTheExpectedBlockVariety() {
        val blocks = MarkdownDocParser.parse(richDoc)
        assertTrue(blocks.any { it is MdHeading })
        assertTrue(blocks.any { it is MdParagraph })
        // Consecutive bullets + tasks merge into one unordered list (CommonMark); the ordered list is a
        // second top-level list. The nested list lives inside its parent item, not at top level.
        val lists = blocks.filterIsInstance<MdList>()
        assertTrue("bullet + ordered lists at top level", lists.size >= 2)
        assertTrue("a task item was parsed", lists.flatMap { it.items }.any { it.task != MdTask.NONE })
        assertTrue("a nested list inside an item", lists.flatMap { it.items }.flatMap { it.blocks }.any { it is MdList })
        assertTrue(blocks.any { it is MdTable })
        assertTrue(blocks.any { it is MdCodeBlock })
        assertTrue(blocks.any { it is MdQuote })
        assertTrue(blocks.any { it === MdThematicBreak })
    }

    fun testRendersEveryBlockWithoutError() {
        val blocks = MarkdownDocParser.parse(richDoc)
        val comps = BlockRenderer().render(blocks)
        assertEquals(blocks.size, comps.size)
        comps.forEach { c ->
            assertNotNull(c.preferredSize)
            assertTrue("finite height", c.preferredSize.height >= 0)
            assertTrue("finite width", c.preferredSize.width >= 0)
        }
    }

    fun testTableComponentHasRealSize() {
        val table = MarkdownDocParser.parse("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |").single() as MdTable
        val comp = BlockRenderer().render(listOf(table)).single()
        assertTrue("table has positive height", comp.preferredSize.height > 0)
        assertTrue("table has positive width", comp.preferredSize.width > 0)
    }

    fun testCodeAndHeadingConstructCleanly() {
        val comps = BlockRenderer().render(MarkdownDocParser.parse("### H\n\n```\ncode line\n```"))
        assertEquals(2, comps.size)
    }

    fun testCalloutRendersCleanly() {
        val blocks = MarkdownDocParser.parse("> [!WARNING]\n> The docs are stale")
        assertTrue(blocks.single() is MdCallout)
        assertTrue(BlockRenderer().render(blocks).single().preferredSize.height > 0)
    }

    fun testLongCodeBlockRendersCollapsedButKeepsFullText() {
        val code = (1..80).joinToString("\n") { "val line$it = $it" }
        val block = MarkdownDocParser.parse("```kotlin\n$code\n```").single() as MdCodeBlock
        assertTrue("fixture is long enough to collapse", CodeBlockLayout.isCollapsible(block.code))

        val comp = BlockRenderer().render(listOf(block)).single()
        comp.setSize(600, comp.preferredSize.height)
        comp.doLayout()

        // Collapsed: shorter than the whole block would be, but still a real, positive height.
        assertTrue("collapsed block has height", comp.preferredSize.height > 0)
        val fullHeight = descendants(comp).filterIsInstance<JTextPane>().single().preferredSize.height
        assertTrue("collapsed view is shorter than the full text", comp.preferredSize.height < fullHeight)

        // The text itself is never truncated — collapsing is a view cap, so Copy still yields everything.
        val area = descendants(comp).filterIsInstance<JTextPane>().single()
        assertEquals(block.code, area.text)
    }

    fun testShortCodeBlockHasNoExpandToggle() {
        val comp = BlockRenderer().render(MarkdownDocParser.parse("```\none\ntwo\n```")).single()
        val labels = descendants(comp).filterIsInstance<JButton>().map { it.text }
        assertEquals("only Copy on a short block", listOf("Copy"), labels)
    }

    fun testLongCodeBlockOffersAnExpandToggle() {
        val code = (1..80).joinToString("\n") { "line $it" }
        val comp = BlockRenderer().render(MarkdownDocParser.parse("```\n$code\n```")).single()
        val buttons = descendants(comp).filterIsInstance<JButton>()
        val toggle = buttons.firstOrNull { it.text.startsWith("Expand") }
        assertNotNull("a long block offers Expand", toggle)
        assertEquals("Expand (80 lines)", toggle!!.text)

        toggle.doClick()
        assertEquals("Collapse", toggle.text)
    }

    fun testKnownLanguageIsLexedIntoHighlightSpans() {
        val code = "fun main() {\n    val greeting = \"hi\"\n}\n"
        val spans = CodeHighlighting.spans(project, "kotlin", code)
        assertFalse("Kotlin has a registered highlighter on the test classpath", spans.isEmpty())
        spans.forEach { assertTrue("span is a real range", it.end > it.start && it.start >= 0) }
        assertTrue("spans stay inside the text", spans.all { it.end <= code.length })
    }

    fun testUnknownLanguageYieldsNoSpans() {
        assertTrue(CodeHighlighting.spans(project, "no-such-language", "whatever").isEmpty())
        assertTrue(CodeHighlighting.spans(project, null, "whatever").isEmpty())
    }

    fun testUnlexableFragmentStillRenders() {
        // Chat code is often a fragment; a highlighter that chokes must degrade to plain text, not throw.
        val fragment = "fun broken( { \"unterminated"
        CodeHighlighting.spans(project, "kotlin", fragment) // must not throw
        val comp = BlockRenderer(project).render(MarkdownDocParser.parse("```kotlin\n$fragment\n```")).single()
        assertTrue(comp.preferredSize.height > 0)
    }

    fun testHighlightingPreservesTheExactCode() {
        val block = MarkdownDocParser.parse("```kotlin\nclass A {\n    // comment\n}\n```").single() as MdCodeBlock
        val pane = descendants(BlockRenderer(project).render(listOf(block)).single())
            .filterIsInstance<JTextPane>().single()
        // Copy hands over block.code, so the rendered pane must hold exactly that — colouring adds
        // attributes, never characters.
        assertEquals("colouring must never alter the text Copy yields", block.code, pane.text)
    }

    fun testWideTableGetsAHorizontalScroller() {
        val cols = 8
        val header = (1..cols).joinToString("|", "|", "|") { " Column heading $it " }
        val sep = (1..cols).joinToString("|", "|", "|") { "---" }
        val row = (1..cols).joinToString("|", "|", "|") { " cell value $it " }
        val comp = BlockRenderer().render(MarkdownDocParser.parse("$header\n$sep\n$row")).single()

        val scrollers = descendants(comp).filterIsInstance<JScrollPane>()
        assertTrue("the table is hosted in a scroller", scrollers.isNotEmpty())
        assertEquals(
            "vertical scrolling stays off — the transcript owns that axis",
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            scrollers.first().verticalScrollBarPolicy,
        )
        assertTrue("table still has a real height", comp.preferredSize.height > 0)
    }

    /**
     * Regression: the marker slot was a fixed width by list *kind*, so a task list — which is
     * unordered — gave the wider ☑/☐ glyph the narrow "•" slot and JBLabel truncated it to "…".
     * Caught by the headless gallery preview, invisible to every prior assertion.
     */
    fun testTaskAndWideMarkersAreNotTruncated() {
        fun markerLabels(md: String) = descendants(BlockRenderer().render(MarkdownDocParser.parse(md)).single())
            .filterIsInstance<javax.swing.JLabel>()
            .filter { it.text.isNotBlank() && it.text.length <= 5 }

        for (label in markerLabels("- [x] done\n- [ ] todo")) {
            val slot = label.parent
            assertTrue(
                "task marker '${label.text}' must fit its slot (slot=${slot.preferredSize.width}, marker=${label.preferredSize.width})",
                slot.preferredSize.width >= label.preferredSize.width,
            )
        }
        // An ordered list past "99." outgrows the ordered slot for the same reason.
        for (label in markerLabels("100. hundred\n101. hundred one")) {
            val slot = label.parent
            assertTrue(
                "ordered marker '${label.text}' must fit its slot",
                slot.preferredSize.width >= label.preferredSize.width,
            )
        }
    }

    /**
     * Regression: `(` / `)` / `[` / `]` were dropped as "delimiter tokens" everywhere, so every
     * **literal** parenthesis or bracket vanished from assistant prose — "(Tier 2)" rendered as
     * "Tier 2". It only looked fine in code spans, which take a verbatim path. Spotted in the
     * headless gallery preview; no prior assertion covered it.
     */
    fun testLiteralParensAndBracketsSurviveInProse() {
        fun itemText(md: String) =
            textOf(MarkdownDocParser.parse(md).filterIsInstance<MdList>().single().items.single().blocks)
        fun paraText(md: String) = textOf(MarkdownDocParser.parse(md))

        val plain = itemText("- Composer alignment inside SPLIT (Tier 2)")
        val task = itemText("- [ ] Composer alignment inside SPLIT (Tier 2)")
        assertTrue("plain bullet keeps parens, got: '$plain'", plain.contains("(Tier 2)"))
        assertTrue("task item keeps parens, got: '$task'", task.contains("(Tier 2)"))

        val para = paraText("Call it later (optional) and index with array[0] too.")
        assertTrue("paragraph keeps parens, got: '$para'", para.contains("(optional)"))
        assertTrue("paragraph keeps brackets, got: '$para'", para.contains("array[0]"))
    }

    /**
     * M8: a `file:` reference offers Open / Reveal in Project on right-click. A web URL has nothing to
     * reveal, and a host that can't reveal (no callback) must get no menu rather than a dead item —
     * so the popup is built only for the case that can actually work.
     */
    fun testFileReferenceOffersRevealOnlyWhenItCanWork() {
        var revealed: String? = null
        val blocks = FileRefDetector.linkify(MarkdownDocParser.parse("Open CLAUDE.md now.")) { true }
        val link = (blocks.single() as MdParagraph).inlines.filterIsInstance<MdLink>().single()
        assertTrue("a resolved file ref becomes a file: link", link.href.startsWith("file:"))

        // With a reveal callback the renderer constructs cleanly and the callback is reachable.
        val comp = BlockRenderer(project, onLink = {}, onReveal = { revealed = it }).render(blocks).single()
        assertTrue(comp.preferredSize.height > 0)
        assertNull("constructing must not fire anything", revealed)

        // Without one, it still renders — just with no context menu.
        assertTrue(BlockRenderer(project, onLink = {}).render(blocks).single().preferredSize.height > 0)
    }

    /** The bracket fix must not leak link syntax into rendered link labels. */
    fun testLinkLabelsStillRenderWithoutTheirBrackets() {
        val blocks = MarkdownDocParser.parse("See [the docs](https://example.com) for more.")
        val link = (blocks.single() as MdParagraph).inlines.filterIsInstance<MdLink>().single()
        assertEquals("the docs", link.inlines.plainText())
        assertEquals("https://example.com", link.href)
        assertFalse("no stray brackets in the label", link.inlines.plainText().contains("["))
    }

    private fun textOf(blocks: List<MdBlock>): String = buildString {
        fun walk(bs: List<MdBlock>) {
            bs.forEach { b ->
                when (b) {
                    is MdParagraph -> append(b.inlines.plainText())
                    is MdHeading -> append(b.inlines.plainText())
                    is MdList -> b.items.forEach { walk(it.blocks) }
                    else -> {}
                }
            }
        }
        walk(blocks)
    }

    private fun descendants(root: Component): List<Component> {
        val out = ArrayList<Component>()
        fun walk(c: Component) {
            out.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun testResolvedFileLinkRendersCleanly() {
        var opened: String? = null
        val blocks = FileRefDetector.linkify(MarkdownDocParser.parse("Open CLAUDE.md now.")) { true }
        val comp = BlockRenderer { opened = it }.render(blocks).single()
        assertNotNull(comp.preferredSize)
        assertNull(opened) // constructed only, no click
    }
}
