package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers here are **captured from 2.1.235**, not invented: a three-turn session reported
 * occupancy 34,065 → 35,297 → 35,380 with `contextWindow` 200,000, while `input_tokens` stayed at 10
 * throughout. A test written against made-up figures would only prove the formula agrees with itself.
 */
class ContextUsageTest {

    @Test
    fun `occupancy is every input class plus the output`() {
        // Turn 1 of the captured session.
        assertEquals(
            34_065L,
            ContextUsage.occupancy(inputTokens = 10, cacheCreationTokens = 8_572, cacheReadTokens = 25_437, outputTokens = 46),
        )
    }

    @Test
    fun `occupancy grows across turns while input tokens do not`() {
        val turns = listOf(
            ContextUsage.occupancy(10, 8_572, 25_437, 46),
            ContextUsage.occupancy(10, 1_172, 34_009, 106),
            ContextUsage.occupancy(10, 150, 35_181, 39),
        )
        assertEquals(listOf(34_065L, 35_297L, 35_380L), turns)
        // The point of the whole class: the conversation's history rides in cache_read, so occupancy
        // is cumulative. Reporting `input_tokens` would have shown a constant 10.
        assertTrue(turns[0] < turns[1] && turns[1] < turns[2])
    }

    @Test
    fun `a percentage is shown only when the CLI has stated the window`() {
        val known = ContextUsage.view(ContextUsage.Snapshot(35_380, 200_000))!!
        assertEquals("35.4k / 200k · 18%", known.text)

        // No window yet: tokens alone, and the tooltip says why there is no percentage rather than
        // leaving the bare number looking truncated.
        val unknown = ContextUsage.view(ContextUsage.Snapshot(35_380, null))!!
        assertEquals("35.4k", unknown.text)
        assertTrue(unknown.detail, unknown.detail.contains("has not been reported yet"))
        assertEquals(ContextUsage.Level.NORMAL, unknown.level)
    }

    @Test
    fun `an empty conversation shows nothing at all`() {
        assertNull(ContextUsage.view(null))
        assertNull(ContextUsage.view(ContextUsage.Snapshot(0, 200_000)))
    }

    @Test
    fun `levels escalate with occupancy and never guess at a compaction`() {
        assertEquals(ContextUsage.Level.NORMAL, ContextUsage.view(ContextUsage.Snapshot(100_000, 200_000))!!.level)
        assertEquals(ContextUsage.Level.HIGH, ContextUsage.view(ContextUsage.Snapshot(150_000, 200_000))!!.level)
        assertEquals(ContextUsage.Level.CRITICAL, ContextUsage.view(ContextUsage.Snapshot(190_000, 200_000))!!.level)
        // An unknown window can never be an alarm — not knowing is not a warning.
        assertEquals(ContextUsage.Level.NORMAL, ContextUsage.view(ContextUsage.Snapshot(9_000_000, null))!!.level)

        // Over the window is reported as it is, not clamped: 105% is a fact worth seeing.
        val over = ContextUsage.view(ContextUsage.Snapshot(210_000, 200_000))!!
        assertTrue(over.text, over.text.endsWith("105%"))
    }

    @Test
    fun `the tooltip says what the number is and points at the CLI's own breakdown`() {
        val detail = ContextUsage.detail(ContextUsage.Snapshot(35_380, 200_000))
        assertTrue(detail, detail.contains("last request"))
        // It must not claim to be /context: measured on a fresh session, /context said 30.5k where
        // usage summed to 34.0k. Both true, different measures.
        assertTrue(detail, detail.contains("/context"))
        assertTrue(detail, detail.contains("estimates a little differently"))
    }

    @Test
    fun `compact formatting never implies precision it does not have`() {
        assertEquals("847", ContextUsage.compact(847))
        assertEquals("1.2k", ContextUsage.compact(1_234))
        assertEquals("34.1k", ContextUsage.compact(34_065))
        assertEquals("200k", ContextUsage.compact(200_000))
        assertEquals("1.5M", ContextUsage.compact(1_500_000))
    }

    @Test
    fun `a fraction needs a usable window`() {
        assertNull(ContextUsage.Snapshot(100, null).fraction)
        assertNull(ContextUsage.Snapshot(100, 0).fraction)
        assertNotNull(ContextUsage.Snapshot(100, 200).fraction)
    }
}
