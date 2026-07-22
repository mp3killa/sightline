package io.mp.sightline.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The incremental-render contract: as a message streams, only the blocks that actually changed are
 * rebuilt. Exercised through the real parser on realistic growing inputs, because the interesting
 * cases are the ones where appending text changes an *earlier* parse decision (setext promotion,
 * list absorption) — those must invalidate from the point of change, never silently keep a stale
 * component.
 */
class StreamingMarkdownTest {

    private fun parse(s: String) = MarkdownDocParser.parse(s)

    @Test fun emptyPreviousMeansNothingIsStable() {
        assertEquals(0, StreamingMarkdown.stablePrefix(emptyList(), parse("Hello")))
    }

    @Test fun aGrowingParagraphInvalidatesOnlyItself() {
        val prev = parse("Intro paragraph.\n\nHel")
        val next = parse("Intro paragraph.\n\nHello wor")
        assertEquals(2, next.size)
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun aNewBlockKeepsEveryFinishedBlock() {
        val prev = parse("# Title\n\nFirst paragraph.")
        val next = parse("# Title\n\nFirst paragraph.\n\nSecond")
        assertEquals(3, next.size)
        assertEquals(2, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun aGrowingCodeFenceInvalidatesOnlyTheFence() {
        val prev = parse("Some prose.\n\n```kotlin\nval a = 1\n")
        val next = parse("Some prose.\n\n```kotlin\nval a = 1\nval b = 2\n")
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun aClosedFenceThenNewProseKeepsTheFence() {
        val prev = parse("```kotlin\nval a = 1\n```")
        val next = parse("```kotlin\nval a = 1\n```\n\nAnd then")
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
    }

    /** `Title` alone is a paragraph; the streamed `===` underline retroactively makes it a heading. */
    @Test fun setextPromotionInvalidatesThePromotedBlock() {
        val prev = parse("Stable first.\n\nTitle\n")
        val next = parse("Stable first.\n\nTitle\n=====")
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun aListAbsorbingANewItemIsRebuiltAsOneBlock() {
        val prev = parse("Intro.\n\n- one\n- two\n")
        val next = parse("Intro.\n\n- one\n- two\n- three\n")
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun aTableGrowingARowInvalidatesTheTable() {
        val prev = parse("| a | b |\n|---|---|\n| 1 | 2 |")
        val next = parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(0, StreamingMarkdown.stablePrefix(prev, next))
    }

    @Test fun identicalDocumentsAreFullyStable() {
        val doc = parse("# T\n\npara\n\n```\ncode\n```")
        assertEquals(doc.size, StreamingMarkdown.stablePrefix(doc, doc))
    }

    /** A re-parse can yield *fewer* blocks (e.g. two paragraphs merging); the prefix is capped by the shorter list. */
    @Test fun aShrinkingParseNeverReportsAPrefixPastTheNewSize() {
        val prev = parse("one\n\ntwo")
        val next = parse("one")
        assertEquals(1, StreamingMarkdown.stablePrefix(prev, next))
        val merged = parse("one two") // single, but *changed* first block
        assertEquals(0, StreamingMarkdown.stablePrefix(prev, merged))
    }

    @Test fun tickSlowsDownForVeryLongDocuments() {
        assertEquals(StreamingMarkdown.TICK_MS, StreamingMarkdown.tickMs(10_000))
        assertEquals(StreamingMarkdown.LONG_DOC_TICK_MS, StreamingMarkdown.tickMs(StreamingMarkdown.LONG_DOC_CHARS + 1))
    }
}
