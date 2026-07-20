package io.mp.sightline.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android half of the interpreter — crash attribution and typed build failures (docs/ANDROID.md M2). */
class ActivityInterpreterAndroidTest {

    private fun input(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun bash(interpreter: ActivityInterpreter, id: String, command: String) {
        interpreter.toolUse(id, "Bash", input("""{"command":"$command"}"""))
    }

    private val crash = """
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: FATAL EXCEPTION: main
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: Process: com.example.driver.staging, PID: 4521
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: java.lang.IllegalStateException: Route was not loaded
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at com.example.ui.RouteViewModel.load(RouteViewModel.kt:42)
        06-01 12:00:00.123  4521  4521 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
    """.trimIndent()

    // ---- crash attribution: the gap M2 closes ----

    /**
     * Before this, every Android crash was emitted with `path = null`, so a FATAL EXCEPTION produced a
     * diagnostics node attached to nothing — unclickable, with no edge to the file that threw.
     */
    @Test
    fun `a logcat crash attaches to the file that threw`() {
        val interpreter = ActivityInterpreter()
        interpreter.appPackagePrefixes = listOf("com.example")
        interpreter.resolveSourceFile = { name ->
            if (name == "RouteViewModel.kt") "/proj/app/src/main/java/com/example/ui/RouteViewModel.kt" else null
        }

        bash(interpreter, "t1", "adb logcat -d")
        val events = interpreter.toolResult("t1", crash, isError = false)

        val error = events.filterIsInstance<ErrorObserved>().single()
        assertEquals("/proj/app/src/main/java/com/example/ui/RouteViewModel.kt", error.path)
        assertTrue(error.message.contains("IllegalStateException"))
    }

    /** Without prefixes we cannot tell app code from framework code, so we attach nothing rather than guess. */
    @Test
    fun `with no app prefixes the crash stays unattached rather than blaming a framework frame`() {
        val interpreter = ActivityInterpreter()
        interpreter.resolveSourceFile = { "/proj/whatever.kt" }

        bash(interpreter, "t1", "adb logcat -d")
        val error = interpreter.toolResult("t1", crash, isError = false).filterIsInstance<ErrorObserved>().single()
        assertNull(error.path)
    }

    @Test
    fun `an unresolvable file leaves the crash unattached`() {
        val interpreter = ActivityInterpreter()
        interpreter.appPackagePrefixes = listOf("com.example")
        interpreter.resolveSourceFile = { null } // e.g. ambiguous name, or indexing

        bash(interpreter, "t1", "adb logcat -d")
        val error = interpreter.toolResult("t1", crash, isError = false).filterIsInstance<ErrorObserved>().single()
        assertNull(error.path)
    }

    @Test
    fun `an all-framework crash attaches to nothing`() {
        val interpreter = ActivityInterpreter()
        interpreter.appPackagePrefixes = listOf("com.example")
        interpreter.resolveSourceFile = { "/proj/anything.kt" }

        val framework = """
            E AndroidRuntime: FATAL EXCEPTION: main
            E AndroidRuntime: Process: com.example.driver, PID: 1
            E AndroidRuntime: java.lang.NullPointerException: oops
            E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:106)
        """.trimIndent()

        bash(interpreter, "t1", "adb logcat -d")
        val error = interpreter.toolResult("t1", framework, isError = false).filterIsInstance<ErrorObserved>().single()
        assertNull(error.path)
    }

    /** A clean logcat is not a crash — the parser stays conservative. */
    @Test
    fun `an ordinary logcat dump produces no error`() {
        val interpreter = ActivityInterpreter()
        interpreter.appPackagePrefixes = listOf("com.example")

        bash(interpreter, "t1", "adb logcat -d")
        val events = interpreter.toolResult("t1", "D/Route: loaded 12 routes\nI/Choreographer: skipped frames", false)
        assertTrue(events.filterIsInstance<ErrorObserved>().isEmpty())
    }

    // ---- typed build failures ----

    /**
     * `firstLine` on a Gradle failure is usually `> Task :app:foo FAILED` — true, and useless as a
     * label. The classifier's headline says what actually broke.
     */
    @Test
    fun `a build failure is labelled with the diagnosed cause, not the first line`() {
        val interpreter = ActivityInterpreter()
        val output = """
            > Task :app:kspDemoStagingDebugKotlin FAILED
            e: [ksp] /proj/app/src/main/java/com/example/data/DeliveryDao.kt:15: Type of the parameter must be a class annotated with @Entity

            FAILURE: Build failed with an exception.
            Execution failed for task ':app:kspDemoStagingDebugKotlin'.

            BUILD FAILED in 12s
        """.trimIndent()

        bash(interpreter, "t1", "./gradlew :app:assembleDemoStagingDebug")
        val events = interpreter.toolResult("t1", output, isError = true)

        val build = events.filterIsInstance<BuildReported>().single()
        assertEquals(false, build.success)
        assertTrue("got: ${build.summary}", build.summary?.contains("KSP annotation processor") == true)
    }

    /** A recognised failure's named files should draw an edge, not sit unattached in diagnostics. */
    @Test
    fun `a diagnosed failure attaches to the files it names`() {
        val interpreter = ActivityInterpreter()
        val output = """
            > Task :app:kspDebugKotlin FAILED
            e: [ksp] /proj/app/src/main/java/com/example/data/DeliveryDao.kt:15: Type of the parameter must be a class annotated with @Entity
            FAILURE: Build failed with an exception.
            BUILD FAILED in 3s
        """.trimIndent()

        bash(interpreter, "t1", "./gradlew :app:assembleDebug")
        val errors = interpreter.toolResult("t1", output, isError = true).filterIsInstance<ErrorObserved>()
        assertTrue(
            "expected an error attached to DeliveryDao.kt, got ${errors.map { it.path }}",
            errors.any { it.path?.endsWith("DeliveryDao.kt") == true },
        )
    }

    /** An unrecognised failure must not acquire a fabricated headline — it keeps the raw first line. */
    @Test
    fun `an unrecognised failure keeps its raw label`() {
        val interpreter = ActivityInterpreter()
        val output = """
            > Task :app:mysteryTask FAILED
            FAILURE: Build failed with an exception.
            Execution failed for task ':app:mysteryTask'.
            > Something we have never seen before
            BUILD FAILED in 2s
        """.trimIndent()

        bash(interpreter, "t1", "./gradlew :app:mysteryTask")
        val build = interpreter.toolResult("t1", output, isError = true).filterIsInstance<BuildReported>().single()
        assertEquals(false, build.success)
        assertTrue(
            "should not claim a cause: ${build.summary}",
            build.summary?.let { it.contains("Task") || it.contains("FAILED") } == true,
        )
    }

    @Test
    fun `a successful build is unaffected`() {
        val interpreter = ActivityInterpreter()
        bash(interpreter, "t1", "./gradlew :app:assembleDebug")
        val events = interpreter.toolResult("t1", "BUILD SUCCESSFUL in 8s\n42 actionable tasks", isError = false)
        val build = events.filterIsInstance<BuildReported>().single()
        assertEquals(true, build.success)
        assertTrue(events.filterIsInstance<ErrorObserved>().isEmpty())
    }
}
