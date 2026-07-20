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
