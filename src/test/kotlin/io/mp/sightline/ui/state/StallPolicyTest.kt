package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallPolicyTest {

    private val long = StallPolicy.QUIET_AFTER_MS * 10

    /**
     * The rule that makes this safe to ship in an Android IDE. A Gradle build produces no stream
     * events for minutes and is perfectly healthy; a notice that fired there would cry wolf on the
     * plugin's core workflow, and a warning nobody believes is worse than none.
     */
    @Test
    fun `silence while a tool is running is never reported, at any duration`() {
        assertFalse(StallPolicy.quiet(sinceLastEventMs = long, toolsInFlight = 1, sinceLastNoticeMs = null))
        assertFalse(StallPolicy.quiet(sinceLastEventMs = Long.MAX_VALUE, toolsInFlight = 3, sinceLastNoticeMs = null))
    }

    @Test
    fun `silence with nothing running is reported once past the threshold`() {
        assertFalse(StallPolicy.quiet(StallPolicy.QUIET_AFTER_MS - 1, toolsInFlight = 0, sinceLastNoticeMs = null))
        assertTrue(StallPolicy.quiet(StallPolicy.QUIET_AFTER_MS, toolsInFlight = 0, sinceLastNoticeMs = null))
    }

    @Test
    fun `it does not repeat itself until the repeat interval has passed`() {
        assertFalse(StallPolicy.quiet(long, toolsInFlight = 0, sinceLastNoticeMs = StallPolicy.REPEAT_EVERY_MS - 1))
        assertTrue(StallPolicy.quiet(long, toolsInFlight = 0, sinceLastNoticeMs = StallPolicy.REPEAT_EVERY_MS))
    }

    /**
     * The notice reports what was observed and never diagnoses. Nothing here can tell a slow model
     * from a dead process, and a panel that says "stuck" about a model that is thinking has told the
     * user something false.
     */
    @Test
    fun `the notice states the silence without diagnosing it`() {
        val text = StallPolicy.notice(180_000)
        assertTrue(text, text.contains("No response"))
        assertTrue(text, text.contains("3 minutes"))
        for (word in listOf("stuck", "hung", "crashed", "frozen", "dead")) {
            assertFalse("notice must not diagnose: $text", text.lowercase().contains(word))
        }
        // It offers the two things that actually help, and promises nothing about what Stop kills —
        // StopPolicy is the authority on that.
        assertTrue(text, text.contains("Stop"))
        assertTrue(text, text.contains("health check"))
    }

    @Test
    fun `durations round to whole minutes rather than implying precision`() {
        assertEquals("45 seconds", StallPolicy.duration(45_000))
        assertEquals("a minute", StallPolicy.duration(90_000))
        assertEquals("2 minutes", StallPolicy.duration(150_000))
    }
}
