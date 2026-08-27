package io.mp.sightline.ui.state

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload shapes are the CLI's own schema declarations from 2.1.235 — docs/PROTOCOL.md §6. */
class SessionNoticesTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val now = 1_760_000_000_000L // a fixed "now" in millis; the clock is always injected

    // ---- compaction ----

    @Test fun anAutoCompactionSaysItRanOutOfRoomAndGivesTheSizes() {
        val n = SessionNotices.compactNotice(
            obj("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":180000,"post_tokens":42000}}"""),
        )!!
        assertTrue(n.text, n.text.contains("ran out of room"))
        assertTrue(n.text, n.text.contains("180k tokens → 42k tokens"))
        // Compaction is normal behaviour, not a failure.
        assertFalse(n.isError)
    }

    @Test fun aManualCompactionDoesNotClaimItRanOutOfRoom() {
        val n = SessionNotices.compactNotice(
            obj("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":90000,"post_tokens":9000}}"""),
        )!!
        assertFalse(n.text, n.text.contains("ran out of room"))
    }

    @Test fun itAlwaysWarnsThatEarlierDetailMayNeedRepeating() {
        // The whole reason for saying anything: Claude "forgetting" is now expected, not a fault.
        val n = SessionNotices.compactNotice(obj("""{"subtype":"compact_boundary"}"""))!!
        assertTrue(n.text, n.text.contains("summary"))
        assertTrue(n.text, n.text.contains("repeating"))
    }

    @Test fun sizesAreOmittedWhenTheCliDidNotGiveBoth() {
        // post_tokens is optional in the schema; half a comparison is worse than none.
        val n = SessionNotices.compactNotice(
            obj("""{"subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":180000}}"""),
        )!!
        assertFalse(n.text, n.text.contains("→"))
    }

    // ---- rate limits ----

    @Test fun sayingNothingIsTheDefaultWhileWithinLimits() {
        val rl = SessionNotices.RateLimits()
        assertNull(rl.onEvent(obj("""{"rate_limit_info":{"status":"allowed"}}"""), now))
    }

    @Test fun aWarningNamesTheBucketTheUtilisationAndTheReset() {
        val rl = SessionNotices.RateLimits()
        val resetsAt = (now / 1000) + 3 * 3600 // epoch seconds, three hours out
        val n = rl.onEvent(
            obj("""{"rate_limit_info":{"status":"allowed_warning","rateLimitType":"seven_day_opus","utilization":91,"resetsAt":$resetsAt}}"""),
            now,
        )!!
        assertTrue(n.text, n.text.contains("weekly Opus"))
        assertTrue(n.text, n.text.contains("91%"))
        assertTrue(n.text, n.text.contains("about 3 hours"))
        assertFalse(n.isError)
    }

    @Test fun beingRejectedIsAnError() {
        val rl = SessionNotices.RateLimits()
        val n = rl.onEvent(obj("""{"rate_limit_info":{"status":"rejected","rateLimitType":"five_hour"}}"""), now)!!
        assertTrue(n.isError)
        assertTrue(n.text, n.text.contains("5-hour usage"))
    }

    @Test fun itSpeaksOnlyWhenTheStatusChanges() {
        // The event fires whenever the *info* changes, which includes utilisation ticking up.
        val rl = SessionNotices.RateLimits()
        assertNotNull(rl.onEvent(obj("""{"rate_limit_info":{"status":"allowed_warning","utilization":80}}"""), now))
        assertNull(rl.onEvent(obj("""{"rate_limit_info":{"status":"allowed_warning","utilization":85}}"""), now))
        assertNull(rl.onEvent(obj("""{"rate_limit_info":{"status":"allowed_warning","utilization":90}}"""), now))
    }

    @Test fun recoveringFromALimitIsWorthOneLine() {
        val rl = SessionNotices.RateLimits()
        rl.onEvent(obj("""{"rate_limit_info":{"status":"rejected"}}"""), now)
        val n = rl.onEvent(obj("""{"rate_limit_info":{"status":"allowed"}}"""), now)!!
        assertTrue(n.text, n.text.contains("reset"))
        assertFalse(n.isError)
    }

    @Test fun anUnknownStatusIsNotGuessedAt() {
        val rl = SessionNotices.RateLimits()
        assertNull(rl.onEvent(obj("""{"rate_limit_info":{"status":"something_new"}}"""), now))
        assertNull(rl.onEvent(obj("""{"rate_limit_info":{}}"""), now))
        assertNull(rl.onEvent(obj("""{}"""), now))
    }

    @Test fun anUnknownBucketIsCalledUsageRatherThanNamedWrongly() {
        val rl = SessionNotices.RateLimits()
        val n = rl.onEvent(obj("""{"rate_limit_info":{"status":"rejected","rateLimitType":"thirty_day_fable"}}"""), now)!!
        assertTrue(n.text, n.text.contains("usage limit"))
    }

    // ---- the reset time, which the schema leaves ambiguous ----

    @Test fun epochSecondsAndMillisecondsBothResolve() {
        assertEquals(1_760_000_000_000L, SessionNotices.normaliseEpoch(1_760_000_000L))
        assertEquals(1_760_000_000_000L, SessionNotices.normaliseEpoch(1_760_000_000_000L))
    }

    @Test fun aValueThatFitsNeitherReadingYieldsNoTimeAtAll() {
        // "resets in about 40 years" would be worse than saying nothing.
        assertNull(SessionNotices.normaliseEpoch(5L))
        assertNull(SessionNotices.normaliseEpoch(0L))
        assertNull(SessionNotices.normaliseEpoch(-1L))
        assertNull(SessionNotices.normaliseEpoch(null))
        assertNull(SessionNotices.normaliseEpoch(99_999_999_999_999L))
        assertEquals("", SessionNotices.resetPhrase(5L, now))
    }

    @Test fun aResetAlreadyInThePastIsNotAnnounced() {
        assertEquals("", SessionNotices.resetPhrase((now / 1000) - 60, now))
    }

    @Test fun theResetPhraseScalesWithHowFarAwayItIs() {
        fun at(seconds: Long) = SessionNotices.resetPhrase(now / 1000 + seconds, now)
        assertTrue(at(45).contains("a minute"))
        assertTrue(at(20 * 60).contains("about 20 minutes"))
        assertTrue(at(5 * 3600).contains("about 5 hours"))
        assertTrue(at(3 * 86400).contains("about 3 days"))
    }
}
