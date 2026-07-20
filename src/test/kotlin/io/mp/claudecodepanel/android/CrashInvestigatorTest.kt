package io.mp.claudecodepanel.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four-heading contract. The distinction between what was read and what was inferred is the whole
 * feature — a developer acting on an inference presented as a fact loses more time than one given nothing.
 */
class CrashInvestigatorTest {

    private val logcatText = """
        06-01 08:14:04.400  4521  4521 E AndroidRuntime: FATAL EXCEPTION: main
        06-01 08:14:04.401  4521  4521 E AndroidRuntime: Process: com.example.driver, PID: 4521
        06-01 08:14:04.402  4521  4521 E AndroidRuntime: java.lang.IllegalStateException: Route not loaded
        06-01 08:14:04.403  4521  4521 E AndroidRuntime: 	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)
        06-01 08:14:04.404  4521  4521 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
    """.trimIndent()

    private val entries = LogcatParser.parse(logcatText)
    private val signal = LogcatParser.signals(entries).single()
    private val trace = StackTraceResolver.parse(logcatText)
    private val prefixes = listOf("com.example")

    private fun investigate(
        recentlyChanged: List<String> = emptyList(),
        apiLevel: Int? = null,
        variant: String? = null,
        entries: List<LogEntry> = this.entries,
        trace: ParsedStackTrace? = this.trace,
    ) = CrashInvestigator.investigate(signal, entries, trace, prefixes, recentlyChanged, apiLevel, variant)

    // ---- confirmed: read straight off the evidence ----

    @Test
    fun `the throwable and the app frame are confirmed`() {
        val confirmed = investigate().byConfidence(Confidence.CONFIRMED).map { it.statement }
        assertTrue(confirmed.any { it.contains("IllegalStateException") && it.contains("Route not loaded") })
        assertTrue(confirmed.any { it.contains("RouteViewModel.load") && it.contains("42") })
        assertTrue(confirmed.any { it.contains("com.example.driver") })
    }

    @Test
    fun `resolved context facts are confirmed when supplied`() {
        val confirmed = investigate(apiLevel = 35, variant = "demoStagingDebug")
            .byConfidence(Confidence.CONFIRMED).map { it.statement }
        assertTrue(confirmed.any { it.contains("API level 35") })
        assertTrue(confirmed.any { it.contains("demoStagingDebug") })
    }

    @Test
    fun `every confirmed finding cites its evidence`() {
        assertTrue(investigate().byConfidence(Confidence.CONFIRMED).all { !it.evidence.isNullOrBlank() })
    }

    // ---- likely: one step of reasoning away ----

    @Test
    fun `a process death in the window is likely, not confirmed`() {
        val withDeath = entries + LogcatParser.parse(
            "06-01 08:14:05.000  1000  1000 I ActivityManager: Process com.example.driver (pid 4521) has died",
        )
        val likely = investigate(entries = withDeath).byConfidence(Confidence.LIKELY).map { it.statement }
        assertTrue(likely.any { it.contains("restarted") })
    }

    @Test
    fun `an ANR is explained as a blocked main thread`() {
        val anrEntries = LogcatParser.parse(
            "06-01 08:14:04.400  1000  1000 E ActivityManager: ANR in com.example.driver",
        )
        val anrSignal = LogcatParser.signals(anrEntries).single()
        val result = CrashInvestigator.investigate(anrSignal, anrEntries, null, prefixes)
        assertTrue(
            result.byConfidence(Confidence.LIKELY).any { it.statement.contains("main thread") },
        )
    }

    @Test
    fun `strict mode violations in the window are surfaced`() {
        val withStrict = entries + LogcatParser.parse(
            "06-01 08:14:03.000  4521  4521 D StrictMode: StrictMode policy violation; ~duration=120 ms",
        )
        assertTrue(
            investigate(entries = withStrict).byConfidence(Confidence.LIKELY)
                .any { it.statement.contains("StrictMode") },
        )
    }

    // ---- possible: consistent, not established ----

    @Test
    fun `a recently changed file in the trace is only a contributing factor`() {
        val result = investigate(recentlyChanged = listOf("app/src/main/java/com/example/ui/RouteViewModel.kt"))
        val possible = result.byConfidence(Confidence.POSSIBLE)
        assertTrue(possible.any { it.statement.contains("RouteViewModel.kt") })
        // …and never promoted to a cause.
        assertFalse(
            result.byConfidence(Confidence.CONFIRMED).any { it.statement.contains("changed recently") },
        )
    }

    @Test
    fun `an unrelated recent change is not offered`() {
        val result = investigate(recentlyChanged = listOf("app/src/main/java/com/example/other/Unrelated.kt"))
        assertTrue(result.byConfidence(Confidence.POSSIBLE).isEmpty())
    }

    // ---- missing: stated, so the reader doesn't fill the gap with an assumption ----

    @Test
    fun `absent evidence is named rather than left as a silent gap`() {
        val missing = investigate().byConfidence(Confidence.MISSING).map { it.statement }
        assertTrue(missing.any { it.contains("API level") })
        assertTrue(missing.any { it.contains("recent-change") })
    }

    @Test
    fun `no trace at all is reported as missing evidence with a next step`() {
        val result = CrashInvestigator.investigate(signal, entries, null, prefixes)
        val missing = result.byConfidence(Confidence.MISSING).map { it.statement }
        assertTrue(missing.any { it.contains("No stack trace") && it.contains("logcat") })
    }

    @Test
    fun `an all-framework trace says so rather than blaming a random frame`() {
        val frameworkText = """
            06-01 08:14:04.400  4521  4521 E AndroidRuntime: FATAL EXCEPTION: main
            06-01 08:14:04.402  4521  4521 E AndroidRuntime: java.lang.NullPointerException: oops
            06-01 08:14:04.403  4521  4521 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
        """.trimIndent()
        val fwEntries = LogcatParser.parse(frameworkText)
        val result = CrashInvestigator.investigate(
            LogcatParser.signals(fwEntries).single(), fwEntries,
            StackTraceResolver.parse(frameworkText), prefixes,
        )
        assertTrue(
            result.byConfidence(Confidence.MISSING).any { it.statement.contains("No frame in the trace") },
        )
    }

    @Test
    fun `not knowing the app package is reported honestly`() {
        val result = CrashInvestigator.investigate(signal, entries, trace, appPrefixes = emptyList())
        assertTrue(
            result.byConfidence(Confidence.MISSING).any { it.statement.contains("package isn't known") },
        )
    }

    // ---- rendering ----

    @Test
    fun `the formatted report groups by confidence in descending certainty`() {
        val text = CrashInvestigator.format(investigate(apiLevel = 35))
        val confirmedAt = text.indexOf("Confirmed:")
        val missingAt = text.indexOf("Missing evidence:")
        assertTrue(confirmedAt >= 0)
        assertTrue(missingAt > confirmedAt)
        assertTrue(text.startsWith("Crash:"))
    }

    @Test
    fun `an empty confidence group is omitted rather than shown empty`() {
        val text = CrashInvestigator.format(investigate())
        assertFalse("no contributing factors were found", text.contains("Contributing factors:"))
    }

    @Test
    fun `a thin investigation is detectable so it can say so`() {
        val bare = CrashInvestigator.investigate(
            LogSignal(LogSignal.Kind.CRASH, "something", "raw"), emptyList(), null, emptyList(),
        )
        assertTrue(bare.isThinlyEvidenced)
        assertFalse(investigate(apiLevel = 35).isThinlyEvidenced)
    }
}
