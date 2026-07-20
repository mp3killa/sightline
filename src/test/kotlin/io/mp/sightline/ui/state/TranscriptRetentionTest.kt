package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRetentionTest {

    @Test fun normalSessionsNeverEvict() {
        assertFalse(TranscriptRetention.shouldEvict(0))
        assertFalse(TranscriptRetention.shouldEvict(1))
        assertFalse(TranscriptRetention.shouldEvict(TranscriptRetention.MAX_TURNS))
        assertEquals(0, TranscriptRetention.evictCount(TranscriptRetention.MAX_TURNS))
    }

    @Test fun evictsExactlyTheOverflow() {
        assertEquals(1, TranscriptRetention.evictCount(TranscriptRetention.MAX_TURNS + 1))
        assertEquals(50, TranscriptRetention.evictCount(TranscriptRetention.MAX_TURNS + 50))
        assertTrue(TranscriptRetention.shouldEvict(TranscriptRetention.MAX_TURNS + 1))
    }

    @Test fun capIsOverridable() {
        assertEquals(3, TranscriptRetention.evictCount(turnCount = 13, cap = 10))
        assertEquals(0, TranscriptRetention.evictCount(turnCount = 9, cap = 10))
    }

    @Test fun noNoticeUntilSomethingIsDropped() {
        assertEquals("", TranscriptRetention.noticeText(0))
        assertEquals("", TranscriptRetention.noticeText(-1))
    }

    /** The wording must not promise a "load earlier" that cannot work — the turns are really gone. */
    @Test fun noticeStatesTheTurnsAreReleasedNotHidden() {
        val one = TranscriptRetention.noticeText(1)
        val many = TranscriptRetention.noticeText(7)
        assertEquals("1 earlier turn was released to keep this session responsive", one)
        assertTrue(many.startsWith("7 earlier turns were released"))
        listOf(one, many).forEach {
            assertFalse("must not imply the turns can be restored", it.contains("load", ignoreCase = true))
        }
    }
}
