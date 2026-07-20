package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StackTraceResolverTest {

    /** A real AndroidRuntime crash, logcat prefixes and all — the shape these actually arrive in. */
    private val logcatCrash = """
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: FATAL EXCEPTION: main
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: Process: com.example.driver.staging, PID: 4521
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: java.lang.IllegalStateException: Route was not loaded
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at com.example.ui.RouteDetailsScreen.Content(RouteDetailsScreen.kt:88)
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at android.app.ActivityThread.main(ActivityThread.java:8506)
    """.trimIndent()

    @Test
    fun `a logcat crash parses through its line prefixes`() {
        val t = StackTraceResolver.parse(logcatCrash)!!
        assertEquals("java.lang.IllegalStateException", t.throwable)
        assertEquals("Route was not loaded", t.message)
        assertEquals(4, t.frames.size)

        val first = t.frames.first()
        assertEquals("com.example.ui.RouteDetailsScreen", first.declaringClass)
        assertEquals("Content", first.method)
        assertEquals("RouteDetailsScreen.kt", first.fileName)
        assertEquals(88, first.line)
    }

    @Test
    fun `a clean printStackTrace parses too`() {
        val t = StackTraceResolver.parse(
            """
            java.lang.NullPointerException: Attempt to invoke a method on a null object reference
            	at com.example.data.RouteRepository.fetch(RouteRepository.kt:17)
            	at com.example.domain.GetRouteUseCase.invoke(GetRouteUseCase.kt:9)
            """.trimIndent(),
        )!!
        assertEquals("java.lang.NullPointerException", t.throwable)
        assertEquals(2, t.frames.size)
    }

    @Test
    fun `a throwable with no message parses`() {
        val t = StackTraceResolver.parse(
            "java.lang.ArithmeticException\n\tat com.example.Calc.divide(Calc.kt:5)",
        )!!
        assertEquals("java.lang.ArithmeticException", t.throwable)
        assertNull(t.message)
        assertEquals(1, t.frames.size)
    }

    // ---- the blame frame: the whole reason this exists ----

    /**
     * A crash's top frame is nearly always framework code. Opening `Handler.dispatchMessage` helps
     * nobody; the deepest frame in the user's own package is what they want.
     */
    @Test
    fun `the blame frame is the first frame in the app's own package`() {
        val t = StackTraceResolver.parse(logcatCrash)!!
        val frame = t.blameFrame(listOf("com.example"))!!
        assertEquals("com.example.ui.RouteDetailsScreen", frame.declaringClass)
        assertEquals(88, frame.line)
    }

    @Test
    fun `an all-framework trace has no blame frame rather than a wrong one`() {
        val t = StackTraceResolver.parse(
            "java.lang.NullPointerException\n\tat android.os.Handler.dispatchMessage(Handler.java:106)",
        )!!
        assertNull(t.blameFrame(listOf("com.example")))
    }

    /**
     * The multi-flavour case that would otherwise find nothing: the installed applicationId carries a
     * flavour suffix (`com.example.driver.staging`) while the code lives in the namespace
     * (`com.example.compose`). Blaming on the applicationId alone matches no frames at all.
     */
    @Test
    fun `app prefixes cover both the applicationId and the namespace`() {
        val prefixes = StackTraceResolver.appPrefixes("com.example.driver.staging", "com.example.compose")
        assertTrue(prefixes.contains("com.example.driver.staging"))
        assertTrue("the suffix-stripped id should match too", prefixes.contains("com.example.driver"))
        assertTrue(prefixes.contains("com.example.compose"))
    }

    @Test
    fun `app prefixes tolerate missing inputs`() {
        assertTrue(StackTraceResolver.appPrefixes(null, null).isEmpty())
        assertTrue(StackTraceResolver.appPrefixes("com.example.app", null).contains("com.example.app"))
    }

    // ---- causes ----

    @Test
    fun `a caused-by chain is parsed and the root cause found`() {
        val t = StackTraceResolver.parse(
            """
            java.lang.RuntimeException: Unable to start activity
            	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
            Caused by: java.lang.IllegalStateException: ViewModel not initialised
            	at com.example.ui.RouteActivity.onCreate(RouteActivity.kt:31)
            Caused by: java.lang.NullPointerException: repository was null
            	at com.example.data.RouteRepository.<init>(RouteRepository.kt:12)
            """.trimIndent(),
        )!!
        assertEquals("java.lang.RuntimeException", t.throwable)
        assertEquals(2, t.allCauses().size)
        assertEquals("java.lang.NullPointerException", t.rootCause.throwable)
        assertEquals("repository was null", t.rootCause.message)
    }

    /** `... 24 more` continues a trace; treating it as a terminator would truncate the chain. */
    @Test
    fun `an elided frame count does not end the trace`() {
        val t = StackTraceResolver.parse(
            """
            java.lang.RuntimeException: outer
            	at com.example.A.a(A.kt:1)
            	... 24 more
            Caused by: java.lang.IllegalStateException: inner
            	at com.example.B.b(B.kt:2)
            """.trimIndent(),
        )!!
        assertEquals(1, t.causes.size)
        assertEquals("java.lang.IllegalStateException", t.rootCause.throwable)
    }

    // ---- frames without a source file ----

    @Test
    fun `a native frame parses without a file or line`() {
        val t = StackTraceResolver.parse(
            "java.lang.UnsatisfiedLinkError: no lib\n\tat com.example.Native.init(Native Method)",
        )!!
        val f = t.frames.single()
        assertNull(f.fileName)
        assertNull(f.line)
        // …but we can still name the file it would live in.
        assertEquals("Native.kt", f.sourceFileName)
    }

    @Test
    fun `an inner class maps to its top-level file`() {
        val f = StackFrame("com.example.ui.RouteScreen\$Content\$1", "invoke", null, null)
        assertEquals("RouteScreen", f.topLevelClassSimpleName)
        assertEquals("RouteScreen.kt", f.sourceFileName)
        assertEquals("com.example.ui", f.packageName)
    }

    // ---- honest failure ----

    @Test
    fun `text with no trace yields null`() {
        assertNull(StackTraceResolver.parse(""))
        assertNull(StackTraceResolver.parse("BUILD SUCCESSFUL in 8s"))
        assertNull(StackTraceResolver.parse("just some prose mentioning an Exception in passing"))
    }

    /** A throwable line with no frames under it is not a trace we can act on. */
    @Test
    fun `a bare throwable line without frames is not a trace`() {
        assertNull(StackTraceResolver.parse("java.lang.IllegalStateException: nope"))
    }

    @Test
    fun `several traces in one logcat window are all found`() {
        val two = logcatCrash + "\n" + """
            java.lang.NumberFormatException: For input string: "abc"
            	at com.example.util.Parsing.toInt(Parsing.kt:7)
        """.trimIndent()
        val all = StackTraceResolver.parseAll(two)
        assertEquals(2, all.size)
        assertEquals("java.lang.NumberFormatException", all[1].throwable)
    }

    @Test
    fun `parseAll respects its limit`() {
        val many = (1..20).joinToString("\n") {
            "java.lang.IllegalStateException: e$it\n\tat com.example.A.a(A.kt:$it)"
        }
        assertEquals(3, StackTraceResolver.parseAll(many, limit = 3).size)
    }

    // ---- summary ----

    @Test
    fun `the summary names the throwable and the app frame`() {
        val t = StackTraceResolver.parse(logcatCrash)!!
        assertEquals(
            "IllegalStateException in RouteDetailsScreen.Content (RouteDetailsScreen.kt:88)",
            StackTraceResolver.summarise(t, listOf("com.example")),
        )
    }

    @Test
    fun `the summary reports the root cause, not the wrapper`() {
        val t = StackTraceResolver.parse(
            """
            java.lang.RuntimeException: Unable to start activity
            	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
            Caused by: java.lang.IllegalStateException: ViewModel not initialised
            	at com.example.ui.RouteActivity.onCreate(RouteActivity.kt:31)
            """.trimIndent(),
        )!!
        val summary = StackTraceResolver.summarise(t, listOf("com.example"))
        assertTrue(summary.startsWith("IllegalStateException"))
        assertTrue(summary.contains("RouteActivity.kt:31"))
    }

    @Test
    fun `the summary degrades gracefully with no app frame`() {
        val t = StackTraceResolver.parse(
            "java.lang.NullPointerException\n\tat android.os.Handler.dispatchMessage(Handler.java:106)",
        )!!
        assertNotNull(StackTraceResolver.summarise(t, listOf("com.example")))
        assertTrue(StackTraceResolver.summarise(t, emptyList()).startsWith("NullPointerException"))
    }
}
