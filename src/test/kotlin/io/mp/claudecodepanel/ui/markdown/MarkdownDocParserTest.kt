package io.mp.claudecodepanel.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocParserTest {

    private fun parse(s: String) = MarkdownDocParser.parse(s)
    private fun para(s: String) = parse(s).single() as MdParagraph

    @Test fun headingsDropTheHashMarkers() {
        val h = parse("### Hello `code`").single() as MdHeading
        assertEquals(3, h.level)
        assertEquals("Hello code", h.inlines.plainText())
        assertFalse(h.inlines.plainText().contains("#"))
    }

    @Test fun inlineFormatting() {
        val p = para("A **bold** and *italic* and `code` and ~~struck~~ and [x](http://y).")
        assertTrue(p.inlines.any { it is MdBold && it.inlines.plainText() == "bold" })
        assertTrue(p.inlines.any { it is MdItalic && it.inlines.plainText() == "italic" })
        assertTrue(p.inlines.any { it is MdCode && it.text == "code" })
        assertTrue(p.inlines.any { it is MdStrikethrough && it.inlines.plainText() == "struck" })
        val link = p.inlines.filterIsInstance<MdLink>().single()
        assertEquals("http://y", link.href)
        assertEquals("x", link.inlines.plainText())
    }

    @Test fun boldContainingInlineCode() {
        val p = para("**bold `c` end**")
        val bold = p.inlines.filterIsInstance<MdBold>().single()
        assertTrue(bold.inlines.any { it is MdCode && it.text == "c" })
        assertEquals("bold c end", bold.inlines.plainText())
    }

    @Test fun unorderedListWithNesting() {
        val list = parse("- one\n- two\n  - nested").single() as MdList
        assertFalse(list.ordered)
        assertEquals(2, list.items.size)
        assertEquals("one", (list.items[0].blocks[0] as MdParagraph).inlines.plainText())
        assertTrue("nested list under item two", list.items[1].blocks.any { it is MdList })
    }

    @Test fun orderedListPreservesStartNumber() {
        val ol = parse("3. c\n4. d").single() as MdList
        assertTrue(ol.ordered)
        assertEquals(3, ol.start)
        assertEquals(2, ol.items.size)
    }

    @Test fun taskListStates() {
        val tl = parse("- [x] done\n- [ ] todo").single() as MdList
        assertEquals(MdTask.CHECKED, tl.items[0].task)
        assertEquals(MdTask.UNCHECKED, tl.items[1].task)
        assertEquals("done", (tl.items[0].blocks[0] as MdParagraph).inlines.plainText())
    }

    @Test fun fencedCodePreservesLanguageAndBody() {
        val code = parse("```kotlin\nfun foo() = 1\nval x = 2\n```").single() as MdCodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun foo() = 1\nval x = 2", code.code)
    }

    @Test fun blockQuote() {
        val q = parse("> quoted text").single() as MdQuote
        assertEquals("quoted text", (q.blocks[0] as MdParagraph).inlines.plainText())
    }

    @Test fun explicitAlertBecomesCallout() {
        val c = parse("> [!WARNING]\n> The docs are stale").single() as MdCallout
        assertEquals(MdCalloutKind.WARNING, c.kind)
        assertEquals("The docs are stale", (c.blocks[0] as MdParagraph).inlines.plainText())
    }

    @Test fun calloutMarkerIsStrippedFromText() {
        val c = parse("> [!NOTE] inline note body").single() as MdCallout
        assertEquals(MdCalloutKind.NOTE, c.kind)
        val body = (c.blocks[0] as MdParagraph).inlines.plainText()
        assertTrue(body.startsWith("inline note"))
        assertFalse(body.contains("[!"))
    }

    @Test fun plainQuoteIsNotACallout() {
        // Callouts are syntax-driven only — an ordinary quote (no [!KIND]) never becomes one.
        assertTrue(parse("> just an ordinary quotation").single() is MdQuote)
    }

    @Test fun thematicBreak() {
        assertTrue(parse("above\n\n---\n\nbelow").any { it is MdThematicBreak })
    }

    // ---- the proposal's exact regression fixture ----

    private val docsStatus = """
        ## Docs status

        | Doc | Status |
        |---|---|
        | CLAUDE.md | Already exists |
        | docs/ARCHITECTURE.md | Already exists |
        | docs/CONVENTIONS.md | Already exists |
        | CONTRIBUTING.md | Already exists |
        | AGENTS.md | Already exists |
    """.trimIndent()

    @Test fun docsStatusFixtureRendersHeadingAndTable() {
        val blocks = parse(docsStatus)
        val h = blocks.filterIsInstance<MdHeading>().single()
        assertEquals(2, h.level)
        assertEquals("Docs status", h.inlines.plainText())

        val table = blocks.filterIsInstance<MdTable>().single()
        assertEquals(listOf("Doc", "Status"), table.header.map { it.plainText() })
        assertEquals(5, table.rows.size)
        assertEquals("CLAUDE.md", table.rows[0][0].plainText())
        assertEquals("Already exists", table.rows[0][1].plainText())
        assertEquals("AGENTS.md", table.rows[4][0].plainText())

        // The `##` marker and the `|---|---|` separator row must never leak as text.
        assertTrue(blocks.none { it is MdParagraph && it.inlines.plainText().contains("#") })
        assertTrue(blocks.none { it is MdParagraph && it.inlines.plainText().contains("---") })
    }

    @Test fun tableAlignments() {
        val t = parse("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |").single() as MdTable
        assertEquals(listOf(MdAlign.LEFT, MdAlign.CENTER, MdAlign.RIGHT), t.alignments)
    }

    // ---- graceful fallback ----

    @Test fun unterminatedCodeFenceStaysReadable() {
        val blocks = parse("```\nunfinished code")
        assertTrue(blocks.isNotEmpty())
        val text = blocks.joinToString("\n") { b ->
            when (b) { is MdCodeBlock -> b.code; is MdParagraph -> b.inlines.plainText(); else -> "" }
        }
        assertTrue("content preserved", text.contains("unfinished code"))
    }

    @Test fun malformedTableFallsBackToText() {
        val blocks = parse("| a | b |\njust some text without a separator")
        val text = blocks.filterIsInstance<MdParagraph>().joinToString(" ") { it.inlines.plainText() }
        assertTrue(text.contains("just some text"))
    }

    @Test fun blankInputYieldsNothing() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n  ").isEmpty())
    }

    @Test fun plainProseIsOneParagraph() {
        val p = para("Just a normal sentence with no markdown.")
        assertEquals("Just a normal sentence with no markdown.", p.inlines.plainText())
    }
}
