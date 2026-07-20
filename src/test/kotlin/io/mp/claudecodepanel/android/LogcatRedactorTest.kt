package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy gate. A leak here is a real one — this text goes to a third-party API — so the corpus is
 * built from log lines a real Android app actually emits, not from synthetic patterns.
 */
class LogcatRedactorTest {

    private fun redact(line: String) = LogcatRedactor.redactLine(line)

    private fun assertScrubbed(secret: String, line: String) {
        val out = redact(line)
        assertFalse("secret survived redaction:\n  in:  $line\n  out: $out", out.contains(secret))
    }

    // ---- auth material ----

    @Test
    fun `bearer tokens are removed`() {
        assertScrubbed(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.abc123signature",
            "D/OkHttp: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.abc123signature",
        )
    }

    /**
     * The ordering bug HealthSanitizer had to fix, re-tested here: the header rule's value stops at the
     * first space, so running it before the credential rule eats "Bearer" and leaves the token.
     */
    @Test
    fun `the credential form is scrubbed before the header rule can truncate it`() {
        val out = redact("Authorization: Bearer abcdefghijklmnop")
        assertFalse(out.contains("abcdefghijklmnop"))
    }

    @Test
    fun `a bare JWT anywhere is removed`() {
        assertScrubbed(
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYW1lIjoiam9lIn0.dQw4w9WgXcQ",
            "D/Auth: refreshed session eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYW1lIjoiam9lIn0.dQw4w9WgXcQ ok",
        )
    }

    @Test
    fun `secret-shaped key values are removed`() {
        for (line in listOf(
            "D/Api: api_key=sk_live_51H8xKl2eZvKYlo2C",
            "D/Api: apiKey: abc123def456",
            """D/Api: {"access_token":"ya29.a0AfH6SMBx"}""",
            "D/Login: password=hunter2secret",
            "D/Api: client_secret = GOCSPX-1234abcd",
            "D/Session: sessionId=9f8e7d6c5b4a3210",
        )) {
            val out = redact(line)
            assertTrue("nothing redacted in: $line", out.contains("[redacted]"))
        }
    }

    @Test
    fun `cookies are removed`() {
        assertScrubbed("session=abc123; user=joe", "D/Net: Cookie: session=abc123; user=joe")
    }

    // ---- identity ----

    @Test
    fun `email addresses are removed`() {
        assertScrubbed("driver@example.com", "I/Auth: signed in as driver@example.com")
    }

    @Test
    fun `device identifiers are removed`() {
        assertScrubbed("9774d56d682e549c", "D/Device: android_id=9774d56d682e549c")
        assertScrubbed("358240051111110", "D/Device: imei 358240051111110")
        assertScrubbed("R5CT10ABCDE", "D/Device: serial: R5CT10ABCDE")
    }

    @Test
    fun `ip addresses are removed`() {
        assertScrubbed("192.168.1.44", "D/Socket: connected to 192.168.1.44")
    }

    @Test
    fun `phone numbers are removed`() {
        assertScrubbed("+27 82 555 1234", "D/Sms: sending to +27 82 555 1234")
    }

    /**
     * The most sensitive thing in a driver app's log, and the easiest to miss because it looks like
     * ordinary numbers.
     */
    @Test
    fun `coordinates are removed in both shapes`() {
        assertScrubbed("-26.204103, 28.047305", "D/Location: fix at -26.204103, 28.047305 accuracy 12m")
        assertScrubbed("-26.204103", "D/Location: lat=-26.204103 lng=28.047305")
    }

    @Test
    fun `home paths lose the username`() {
        val out = redact("W/Art: could not read /Users/devuser/Library/cache")
        assertFalse(out.contains("devuser"))
        assertTrue(out.contains("/Users/…"))
    }

    // ---- what must SURVIVE: over-redaction that destroys the log is its own failure ----

    @Test
    fun `ordinary log lines are untouched`() {
        for (line in listOf(
            "D/RouteViewModel: loaded 12 routes for today",
            "I/Choreographer: Skipped 43 frames! The application may be doing too much work on its main thread.",
            "E/AndroidRuntime: FATAL EXCEPTION: main",
            "	at com.example.ui.RouteDetailsScreen.Content(RouteDetailsScreen.kt:88)",
            "D/OkHttp: --> GET https://api.example.com/v2/routes",
            "D/OkHttp: <-- 200 OK (438ms, 18.4KB)",
        )) {
            assertEquals("should be untouched: $line", line, redact(line))
        }
    }

    /** A stack trace is the point of capturing a crash; scrubbing its class names would destroy it. */
    @Test
    fun `stack frames survive intact`() {
        val frame = "	at com.example.data.RouteRemoteDataSource${'$'}fetch${'$'}2.invokeSuspend(RouteRemoteDataSource.kt:141)"
        assertEquals(frame, redact(frame))
    }

    @Test
    fun `version numbers and ratios are not mistaken for coordinates`() {
        for (line in listOf(
            "I/App: version 1.0, build 2.0",
            "D/Perf: ratio 0.5, 1.5",
            "D/Anim: interpolated 12.5, 90.0",
        )) {
            assertEquals(line, redact(line))
        }
    }

    @Test
    fun `long class and package names are not mistaken for blobs`() {
        for (line in listOf(
            "D/DI: binding com.example.data.repository.DefaultRouteRepositoryImplementation",
            "D/Compose: recomposing AndroidComposeViewAccessibilityDelegateCompat",
        )) {
            assertEquals(line, redact(line))
        }
    }

    @Test
    fun `an ordinary long number is not treated as an IMEI`() {
        assertEquals("D/Perf: elapsed 1234567890 ns", redact("D/Perf: elapsed 1234567890 ns"))
    }

    // ---- fail closed ----

    /**
     * The rule that separates this from HealthSanitizer: a line too long to reason about is **dropped
     * whole**, not passed through partly scrubbed. A truncated log is an inconvenience; a leaked token
     * is an incident.
     */
    @Test
    fun `an oversized line is dropped rather than partly scrubbed`() {
        val payload = "D/Api: response " + "x".repeat(3000)
        val report = LogcatRedactor.redact(payload)
        assertFalse(report.text.contains("xxxxxxxxxx"))
        assertTrue(report.text.contains("line dropped"))
        assertEquals(1, report.droppedLines)
    }

    @Test
    fun `a normal-length line is not dropped`() {
        val report = LogcatRedactor.redact("D/Route: loaded 12 routes")
        assertEquals(0, report.droppedLines)
        assertFalse(report.changedAnything)
    }

    // ---- reporting: silently altering evidence is its own harm ----

    @Test
    fun `redactions are counted and described`() {
        val report = LogcatRedactor.redact(
            """
            I/Auth: signed in as driver@example.com
            D/Api: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig
            D/Location: fix at -26.204103, 28.047305
            D/Route: loaded 12 routes
            """.trimIndent(),
        )
        assertTrue(report.changedAnything)
        assertTrue(report.totalRedactions >= 3)
        val summary = report.summary()
        assertTrue(summary.contains("redacted"))
        assertTrue("the log body must survive", report.text.contains("loaded 12 routes"))
    }

    @Test
    fun `an unchanged capture reports nothing`() {
        val report = LogcatRedactor.redact("D/Route: fine\nD/Route: also fine")
        assertFalse(report.changedAnything)
        assertEquals("", report.summary())
    }

    // ---- idempotence: attaching twice must not double-scrub or re-expose ----

    @Test
    fun `redaction is idempotent`() {
        val corpus = """
            I/Auth: signed in as driver@example.com
            D/Api: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig
            D/Location: lat=-26.204103 lng=28.047305
            D/Device: android_id=9774d56d682e549c
            W/Art: could not read /Users/devuser/Library/cache
        """.trimIndent()
        val once = LogcatRedactor.redact(corpus).text
        val twice = LogcatRedactor.redact(once).text
        assertEquals(once, twice)
    }

    // ---- the backstop ----

    @Test
    fun `the safety check passes on redacted output and fails on raw`() {
        val raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig for driver@example.com"
        assertTrue("raw text must be flagged", LogcatRedactor.looksUnsafe(raw))
        assertFalse(
            "redacted text must pass the backstop",
            LogcatRedactor.looksUnsafe(LogcatRedactor.redact(raw).text),
        )
    }

    /** A whole realistic capture, end to end — nothing sensitive may survive. */
    @Test
    fun `a full realistic capture is clean afterwards`() {
        val capture = """
            06-01 08:14:02.113  4521  4521 I Auth    : signed in as driver@example.com
            06-01 08:14:02.220  4521  4602 D OkHttp  : --> GET https://api.example.com/v2/routes
            06-01 08:14:02.221  4521  4602 D OkHttp  : Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0NTIxIn0.7Hk9signature
            06-01 08:14:02.660  4521  4602 D OkHttp  : <-- 200 OK (438ms, 18.4KB)
            06-01 08:14:03.010  4521  4521 D Location: fix at -26.204103, 28.047305 accuracy 12m
            06-01 08:14:03.100  4521  4521 D Device  : android_id=9774d56d682e549c
            06-01 08:14:04.400  4521  4521 D Route   : loaded 12 routes for today
        """.trimIndent()

        val out = LogcatRedactor.redact(capture).text
        for (secret in listOf(
            "driver@example.com",
            "eyJhbGciOiJIUzI1NiJ9",
            "-26.204103",
            "28.047305",
            "9774d56d682e549c",
        )) {
            assertFalse("'$secret' survived:\n$out", out.contains(secret))
        }
        // …while the log remains useful.
        assertTrue(out.contains("loaded 12 routes"))
        assertTrue(out.contains("200 OK"))
        assertTrue(out.contains("GET https://api.example.com/v2/routes"))
    }
}
