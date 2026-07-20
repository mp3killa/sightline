package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleTasksTest {

    private fun task(intent: BuildIntent, module: String = ":app", variant: String? = "demoStagingDebug"): String {
        val r = GradleTasks.resolve(intent, module, variant)
        assertTrue("expected a resolved task, got $r", r is TaskResolution.Resolved)
        return (r as TaskResolution.Resolved).task.task
    }

    @Test
    fun `variant tasks are named the way AGP generates them`() {
        assertEquals(":app:assembleDemoStagingDebug", task(BuildIntent.ASSEMBLE))
        assertEquals(":app:bundleDemoStagingDebug", task(BuildIntent.BUNDLE))
        assertEquals(":app:installDemoStagingDebug", task(BuildIntent.INSTALL))
        assertEquals(":app:testDemoStagingDebugUnitTest", task(BuildIntent.UNIT_TEST))
        assertEquals(":app:connectedDemoStagingDebugAndroidTest", task(BuildIntent.INSTRUMENTATION_TEST))
        assertEquals(":app:lintDemoStagingDebug", task(BuildIntent.LINT))
    }

    @Test
    fun `a plain debug variant works too`() {
        assertEquals(":core:testDebugUnitTest", task(BuildIntent.UNIT_TEST, ":core", "debug"))
    }

    @Test
    fun `nested module paths are preserved`() {
        assertEquals(":feature:route:assembleDebug", task(BuildIntent.ASSEMBLE, ":feature:route", "debug"))
    }

    @Test
    fun `a module path without a leading colon is normalised`() {
        assertEquals(":app:assembleDebug", task(BuildIntent.ASSEMBLE, "app", "debug"))
    }

    /**
     * The expensive mistake this class exists to prevent. `assemble` builds every variant; on a project
     * with two flavour dimensions that is twenty builds, not a slower version of the right one.
     */
    @Test
    fun `an unknown variant is refused rather than degraded to the aggregate task`() {
        val r = GradleTasks.resolve(BuildIntent.ASSEMBLE, ":app", null)
        assertTrue(r is TaskResolution.Refused)
        val refusal = (r as TaskResolution.Refused).refusal
        assertTrue(refusal.reason.contains("every variant"))
        assertTrue(refusal.hint.contains("Pick a variant"))
    }

    @Test
    fun `a blank variant is refused the same way`() {
        assertTrue(GradleTasks.resolve(BuildIntent.UNIT_TEST, ":app", "  ") is TaskResolution.Refused)
    }

    /** Clean has no variant, so it must not be caught by the refusal. */
    @Test
    fun `clean resolves without a variant`() {
        assertEquals(":app:clean", task(BuildIntent.CLEAN, ":app", null))
        assertTrue(GradleTasks.resolve(BuildIntent.CLEAN, ":app", null) is TaskResolution.Resolved)
    }

    @Test
    fun `a root-level task drops the module prefix`() {
        assertEquals(":clean", task(BuildIntent.CLEAN, ":", null))
    }

    @Test
    fun `commands run through the wrapper`() {
        val r = GradleTasks.resolve(BuildIntent.ASSEMBLE, ":app", "debug") as TaskResolution.Resolved
        assertEquals(listOf("./gradlew", ":app:assembleDebug"), GradleTasks.command(r.task))
        assertEquals(
            listOf("./gradlew", ":app:assembleDebug", "--offline"),
            GradleTasks.command(r.task, listOf("--offline")),
        )
    }

    /** `--tests` needs a fully-qualified pattern; a bare class name silently matches nothing. */
    @Test
    fun `a single test is filtered by fully-qualified name`() {
        val r = GradleTasks.singleTest(":app", "debug", "com.example.RouteViewModelTest") as TaskResolution.Resolved
        assertEquals(""":app:testDebugUnitTest --tests "com.example.RouteViewModelTest"""", r.task.task)

        val method = GradleTasks.singleTest(":app", "debug", "com.example.RouteViewModelTest", "loadsRoutes")
        assertEquals(
            """:app:testDebugUnitTest --tests "com.example.RouteViewModelTest.loadsRoutes"""",
            (method as TaskResolution.Resolved).task.task,
        )
    }

    @Test
    fun `a single test inherits the unknown-variant refusal`() {
        assertTrue(GradleTasks.singleTest(":app", null, "com.example.FooTest") is TaskResolution.Refused)
    }

    @Test
    fun `gradle commands are recognised for output scanning`() {
        assertTrue(GradleTasks.looksLikeBuildCommand("./gradlew :app:assembleDebug"))
        assertTrue(GradleTasks.looksLikeBuildCommand("gradle build"))
        assertTrue(GradleTasks.looksLikeBuildCommand("cd app && ../gradlew test"))
        assertFalse(GradleTasks.looksLikeBuildCommand("adb devices"))
        assertFalse(GradleTasks.looksLikeBuildCommand("ls gradlewish"))
    }
}
