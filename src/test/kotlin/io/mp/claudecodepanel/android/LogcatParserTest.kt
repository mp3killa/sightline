package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatParserTest {

    private val capture = """
        06-01 08:14:02.113  4521  4521 I Auth    : signed in
        06-01 08:14:02.220  4521  4602 D OkHttp  : --> GET /v2/routes
        06-01 08:14:02.660  4521  4602 D OkHttp  : <-- 200 OK
        06-01 08:14:03.010  4521  4521 W Choreographer: Skipped 43 frames!
        06-01 08:14:04.400  4521  4521 E AndroidRuntime: FATAL EXCEPTION: main
        06-01 08:14:04.401  4521  4521 E AndroidRuntime: Process: com.example.driver, PID: 4521
        06-01 08:14:04.402  4521  4521 E AndroidRuntime: java.lang.IllegalStateException: Route not loaded
        06-01 08:14:04.403  4521  4521 E AndroidRuntime: 	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)
    """.trimIndent()

    @Test
    fun `threadtime lines parse into their fields`() {
        val entries = LogcatParser.parse(capture)
        val first = entries.first()
        assertEquals("06-01 08:14:02.113", first.timestamp)
        assertEquals(4521, first.pid)
        assertEquals(4521, first.tid)
        assertEquals(LogLevel.INFO, first.level)
        assertEquals("Auth", first.tag)
        assertEquals("signed in", first.message)
    }

    @Test
    fun `the older brief format also parses`() {
        val e = LogcatParser.parseLine("E/AndroidRuntime( 4521): FATAL EXCEPTION: main")!!
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("AndroidRuntime", e.tag)
        assertEquals(4521, e.pid)
        assertNull(e.timestamp)
    }

    /** A logcat window is evidence — quietly dropping lines from it is worse than leaving them unparsed. */
    @Test
    fun `an unrecognised line is kept, not dropped`() {
        val e = LogcatParser.parseLine("	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)")!!
        assertEquals("	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)", e.raw)
        assertEquals("", e.tag)
    }

    @Test
    fun `blank lines are skipped`() {
        assertNull(LogcatParser.parseLine(""))
        assertNull(LogcatParser.parseLine("   "))
    }

    // ---- filtering ----

    @Test
    fun `level filtering keeps the threshold and above`() {
        val entries = LogcatParser.parse(capture)
        val errors = LogcatParser.filter(entries, minLevel = LogLevel.ERROR)
        assertTrue(errors.none { it.tag == "OkHttp" })
        assertTrue(errors.any { it.tag == "AndroidRuntime" })
    }

    /**
     * The subtle one: a stack frame is a continuation line with no level of its own. Filtering it out
     * would leave the exception header with no trace under it — cutting off exactly what was wanted.
     */
    @Test
    fun `stack frames survive a level filter`() {
        val entries = LogcatParser.parse(
            "06-01 08:14:04.402  4521  4521 E AndroidRuntime: java.lang.IllegalStateException\n" +
                "	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)",
        )
        val errors = LogcatParser.filter(entries, minLevel = LogLevel.ERROR)
        assertTrue(errors.any { it.raw.contains("RouteViewModel.kt:42") })
    }

    @Test
    fun `pid filtering keeps only that process`() {
        val mixed = capture + "\n06-01 08:14:05.000  9999  9999 I Other   : from another app"
        val entries = LogcatParser.parse(mixed)
        val ours = LogcatParser.filter(entries, pid = 4521)
        assertTrue(ours.none { it.message.contains("another app") })
        assertTrue(ours.any { it.tag == "Auth" })
    }

    @Test
    fun `noise suppression drops framework chatter only when asked`() {
        val entries = LogcatParser.parse(capture)
        assertTrue(LogcatParser.filter(entries).any { it.tag == "Choreographer" })
        assertFalse(LogcatParser.filter(entries, hideNoise = true).any { it.tag == "Choreographer" })
        // …and never drops the app's own logs.
        assertTrue(LogcatParser.filter(entries, hideNoise = true).any { it.tag == "Auth" })
    }

    // ---- grouping ----

    @Test
    fun `repeated messages fold with a count`() {
        val repeated = (1..5).joinToString("\n") {
            "06-01 08:14:0$it.000  4521  4521 W Retry   : retrying request"
        }
        val grouped = LogcatParser.group(LogcatParser.parse(repeated))
        assertEquals(1, grouped.size)
        assertEquals(5, grouped.single().repeats)
        assertTrue(grouped.single().display.contains("×5"))
    }

    @Test
    fun `distinct messages are not folded`() {
        val grouped = LogcatParser.group(LogcatParser.parse(capture))
        assertTrue(grouped.all { it.repeats == 1 })
        assertEquals(1, grouped.first().repeats)
        assertFalse(grouped.first().display.contains("×"))
    }

    /** Only *consecutive* repeats fold; an interleaved message means they weren't a burst. */
    @Test
    fun `non-consecutive repeats stay separate`() {
        val text = """
            06-01 08:14:01.000  4521  4521 W Retry   : retrying
            06-01 08:14:02.000  4521  4521 I Other   : something else
            06-01 08:14:03.000  4521  4521 W Retry   : retrying
        """.trimIndent()
        assertEquals(3, LogcatParser.group(LogcatParser.parse(text)).size)
    }

    // ---- signals ----

    @Test
    fun `a crash is detected with its throwable and process`() {
        val signals = LogcatParser.signals(LogcatParser.parse(capture))
        val crash = signals.single { it.kind == LogSignal.Kind.CRASH }
        assertTrue(crash.summary.contains("IllegalStateException"))
        assertEquals("com.example.driver", crash.process)
    }

    @Test
    fun `an ANR is detected with its process`() {
        val signals = LogcatParser.signals(
            LogcatParser.parse("06-01 08:14:04.400  1000  1000 E ActivityManager: ANR in com.example.driver (com.example.driver/.MainActivity)"),
        )
        val anr = signals.single()
        assertEquals(LogSignal.Kind.ANR, anr.kind)
        assertEquals("com.example.driver", anr.process)
    }

    @Test
    fun `strict mode, OOM, process death and start are detected`() {
        val text = """
            06-01 08:14:01.000  4521  4521 D StrictMode: StrictMode policy violation; ~duration=120 ms
            06-01 08:14:02.000  4521  4521 E Heap    : java.lang.OutOfMemoryError: Failed to allocate
            06-01 08:14:03.000  1000  1000 I ActivityManager: Process com.example.driver (pid 4521) has died
            06-01 08:14:04.000  1000  1000 I ActivityManager: Start proc 4600:com.example.driver/u0a123
        """.trimIndent()
        val kinds = LogcatParser.signals(LogcatParser.parse(text)).map { it.kind }
        assertTrue(LogSignal.Kind.STRICT_MODE in kinds)
        assertTrue(LogSignal.Kind.OUT_OF_MEMORY in kinds)
        assertTrue(LogSignal.Kind.PROCESS_DEATH in kinds)
        assertTrue(LogSignal.Kind.PROCESS_START in kinds)
    }

    /** Conservative: an ordinary log produces no signals rather than a speculative one. */
    @Test
    fun `an ordinary log produces no signals`() {
        val text = """
            06-01 08:14:01.000  4521  4521 D Route   : loaded 12 routes
            06-01 08:14:02.000  4521  4521 I Auth    : token refreshed
            06-01 08:14:03.000  4521  4521 W Net     : slow response, 2400ms
        """.trimIndent()
        assertTrue(LogcatParser.signals(LogcatParser.parse(text)).isEmpty())
    }

    // ---- capture args ----

    /** Without `-d`, logcat streams forever and the call never returns. */
    @Test
    fun `capture args dump and exit rather than streaming`() {
        val args = LogcatParser.captureArgs()
        assertTrue(args.contains("-d"))
        assertTrue(args.containsAll(listOf("-v", "threadtime")))
    }

    @Test
    fun `capture args can scope to a pid and a level`() {
        val args = LogcatParser.captureArgs(pid = 4521, minLevel = LogLevel.WARN, maxLines = 500)
        assertTrue(args.contains("--pid=4521"))
        assertTrue(args.contains("*:W"))
        assertTrue(args.contains("500"))
    }

    @Test
    fun `a verbose capture adds no level filter`() {
        assertFalse(LogcatParser.captureArgs().any { it.startsWith("*:") })
    }
}
