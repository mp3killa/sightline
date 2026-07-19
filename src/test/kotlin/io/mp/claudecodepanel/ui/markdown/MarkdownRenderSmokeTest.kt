package io.mp.claudecodepanel.ui.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
