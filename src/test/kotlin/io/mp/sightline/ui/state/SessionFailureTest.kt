package io.mp.sightline.ui.state

import io.mp.sightline.ui.state.SessionFailure.Action
import io.mp.sightline.ui.state.SessionFailure.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFailureTest {

    /** The message that prompted the card: it must classify, and it must offer the sign-in route. */
    @Test
    fun `expired oauth session is an auth failure with sign-in first`() {
        val advice = SessionFailure.classify(
            "Failed to authenticate: OAuth session expired and could not be refreshed",
            canRetry = true,
        )
        assertEquals(Kind.AUTH, advice.kind)
        assertEquals(Action.SIGN_IN, advice.actions.first())
        // Retry is offered, but after signing in — retrying first reproduces the failure exactly.
        assertTrue(advice.actions.indexOf(Action.RETRY) > advice.actions.indexOf(Action.SIGN_IN))
    }

    @Test
    fun `sign-in command is the shell subcommand, not the REPL slash command`() {
        assertEquals("claude auth login", SessionFailure.SIGN_IN_COMMAND)
        assertTrue(SessionFailure.SIGN_IN_HINT.contains("claude auth login"))
    }

    @Test
    fun `an unrecognised message invents nothing`() {
        val raw = "Something went sideways in a way nobody has seen before"
        val advice = SessionFailure.classify(raw)
        assertEquals(Kind.UNKNOWN, advice.kind)
        assertEquals(raw, advice.headline)
        assertNull(advice.explanation)
        assertEquals(raw, advice.detail)
    }

    @Test
    fun `an unknown headline is the first non-blank line, clipped, with the exit code`() {
        val advice = SessionFailure.classify("\n\nboom\n  stack frame\n  stack frame", exitCode = 2)
        assertEquals("Claude exited (code 2): boom", advice.headline)
        // The full text survives for Copy details — the headline is a view, not a truncation.
        assertTrue(advice.detail.contains("stack frame"))

        val long = SessionFailure.classify("x".repeat(400))
        assertTrue(long.headline.length <= 180)
        assertTrue(long.headline.endsWith("…"))
        assertEquals(400, long.detail.length)
    }

    @Test
    fun `an exit with nothing on stderr still says what is known`() {
        val advice = SessionFailure.classify("", exitCode = 1)
        assertEquals(Kind.UNKNOWN, advice.kind)
        assertTrue(advice.headline.contains("code 1"))
        // Nothing to copy, so no button that would copy an empty string.
        assertFalse(advice.actions.contains(Action.COPY))
    }

    @Test
    fun `retry is only offered when the caller says it is safe`() {
        for (raw in listOf("OAuth session expired", "rate limit reached", "fetch failed", "mystery")) {
            assertFalse(SessionFailure.classify(raw, canRetry = false).actions.contains(Action.RETRY))
            assertTrue(SessionFailure.classify(raw, canRetry = true).actions.contains(Action.RETRY))
        }
    }

    @Test
    fun `a missing CLI points at the health check and settings, never at retry`() {
        val byCode = SessionFailure.classify("", exitCode = 127, canRetry = true)
        assertEquals(Kind.CLI_MISSING, byCode.kind)
        assertEquals(listOf(Action.HEALTH, Action.SETTINGS), byCode.actions)

        val byText = SessionFailure.classify("/bin/sh: claude: command not found", canRetry = true)
        assertEquals(Kind.CLI_MISSING, byText.kind)
        assertFalse(byText.actions.contains(Action.RETRY))
    }

    @Test
    fun `the other kinds classify from wording the CLI actually prints`() {
        assertEquals(Kind.RATE_LIMIT, SessionFailure.classify("Claude usage limit reached").kind)
        assertEquals(Kind.BAD_ARGUMENT, SessionFailure.classify("error: unknown option '--nope'").kind)
        assertEquals(Kind.NETWORK, SessionFailure.classify("request failed: getaddrinfo ENOTFOUND api").kind)
        assertEquals(Kind.AUTH, SessionFailure.classify("Please run /login to continue").kind)
    }

    /** Every offered action carries a label, and none of them is the raw enum name. */
    @Test
    fun `actions are labelled for a button`() {
        for (a in Action.values()) {
            assertTrue(a.label.isNotBlank())
            assertFalse(a.label == a.name)
        }
    }

    /** Copy details is the completeness claim, so it rides on the full text every time there is any. */
    @Test
    fun `copy is offered whenever there is detail`() {
        val advice = SessionFailure.classify("Failed to authenticate: OAuth session expired\nline two")
        assertTrue(advice.actions.contains(Action.COPY))
        assertTrue(advice.detail.contains("line two"))
    }
}
