package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSelectionTest {

    private val modules = listOf(":app", ":core", ":feature:route")

    /** A three-module project, the shape the M2 gate names. */
    private val allFiles = listOf(
        "app/src/main/java/com/example/ui/RouteViewModel.kt",
        "app/src/test/java/com/example/ui/RouteViewModelTest.kt",
        "app/src/androidTest/java/com/example/ui/RouteScreenAndroidTest.kt",
        "app/src/main/java/com/example/ui/RouteScreen.kt",
        "core/src/main/java/com/example/core/Clock.kt",
        "core/src/test/java/com/example/core/ClockTest.kt",
        "core/src/main/java/com/example/core/Untested.kt",
        "feature/route/src/main/java/com/example/route/RouteRepository.kt",
        "feature/route/src/test/java/com/example/route/RouteRepositoryTest.kt",
    )

    // ---- classification ----

    @Test
    fun `source sets decide the test kind`() {
        assertEquals(TestKind.UNIT, TestSelection.kindOf("app/src/test/java/FooTest.kt"))
        assertEquals(TestKind.INSTRUMENTATION, TestSelection.kindOf("app/src/androidTest/java/FooTest.kt"))
        assertNull(TestSelection.kindOf("app/src/main/java/Foo.kt"))
        assertFalse(TestSelection.isTestFile("app/src/main/java/Foo.kt"))
    }

    /** `androidTest` contains `test` as a substring — checking the wrong one first mislabels every one. */
    @Test
    fun `androidTest is not mistaken for a unit test`() {
        assertEquals(
            TestKind.INSTRUMENTATION,
            TestSelection.kindOf("app/src/androidTest/java/com/example/RouteScreenTest.kt"),
        )
    }

    @Test
    fun `flavoured source sets still classify`() {
        assertEquals(TestKind.UNIT, TestSelection.kindOf("app/src/testDemoStaging/java/FooTest.kt"))
    }

    // ---- module ownership ----

    @Test
    fun `the owning module is found, longest path first`() {
        assertEquals(":app", TestSelection.moduleFor("app/src/main/java/A.kt", modules))
        assertEquals(":core", TestSelection.moduleFor("core/src/main/java/B.kt", modules))
        assertEquals(
            ":feature:route",
            TestSelection.moduleFor("feature/route/src/main/java/C.kt", modules + ":feature"),
        )
        assertNull(TestSelection.moduleFor("scripts/build.sh", modules))
    }

    // ---- fully-qualified names ----

    @Test
    fun `fqcn is derived from the source root, not from any package-shaped directory`() {
        assertEquals(
            "com.example.ui.RouteViewModel",
            TestSelection.fqcnOf("app/src/main/java/com/example/ui/RouteViewModel.kt"),
        )
        assertEquals(
            "com.example.core.Clock",
            TestSelection.fqcnOf("core/src/main/kotlin/com/example/core/Clock.kt"),
        )
        assertNull(TestSelection.fqcnOf("app/src/main/res/values/strings.xml"))
        assertNull(TestSelection.fqcnOf("README.md"))
    }

    // ---- planning ----

    @Test
    fun `changing production code selects its test`() {
        val plan = TestSelection.planFor(
            listOf("app/src/main/java/com/example/ui/RouteViewModel.kt"),
            allFiles,
            modules,
        )
        assertEquals(1, plan.targets.size)
        val t = plan.targets.single()
        assertEquals("com.example.ui.RouteViewModelTest", t.fqcn)
        assertEquals(TestKind.UNIT, t.kind)
        assertEquals(":app", t.gradlePath)
        assertFalse(plan.needsDevice)
    }

    /** A changed test runs itself — the strongest signal available, no inference needed. */
    @Test
    fun `changing a test selects that test directly`() {
        val plan = TestSelection.planFor(
            listOf("app/src/test/java/com/example/ui/RouteViewModelTest.kt"),
            allFiles,
            modules,
        )
        assertEquals("com.example.ui.RouteViewModelTest", plan.targets.single().fqcn)
    }

    @Test
    fun `an instrumented test is recognised and flags that a device is needed`() {
        val plan = TestSelection.planFor(
            listOf("app/src/main/java/com/example/ui/RouteScreen.kt"),
            allFiles,
            modules,
        )
        assertEquals(TestKind.INSTRUMENTATION, plan.targets.single().kind)
        assertTrue(plan.needsDevice)
    }

    @Test
    fun `changes across modules select tests in each`() {
        val plan = TestSelection.planFor(
            listOf(
                "app/src/main/java/com/example/ui/RouteViewModel.kt",
                "core/src/main/java/com/example/core/Clock.kt",
                "feature/route/src/main/java/com/example/route/RouteRepository.kt",
            ),
            allFiles,
            modules,
        )
        assertEquals(setOf(":app", ":core", ":feature:route"), plan.byModule().keys)
        assertEquals(3, plan.targets.size)
    }

    /**
     * The behaviour that keeps this honest. A green run that quietly skipped the relevant test is worse
     * than no run, so a production change with no test is *reported*, not dropped.
     */
    @Test
    fun `changed files with no test are reported as uncovered`() {
        val plan = TestSelection.planFor(
            listOf("core/src/main/java/com/example/core/Untested.kt"),
            allFiles,
            modules,
        )
        assertTrue(plan.isEmpty)
        assertEquals(listOf("core/src/main/java/com/example/core/Untested.kt"), plan.uncovered)
    }

    @Test
    fun `non-source changes are ignored entirely`() {
        val plan = TestSelection.planFor(
            listOf("README.md", "app/src/main/res/values/strings.xml", "gradle/libs.versions.toml"),
            allFiles,
            modules,
        )
        assertTrue(plan.isEmpty)
        assertTrue("a resource is not 'uncovered code'", plan.uncovered.isEmpty())
    }

    @Test
    fun `the same test selected twice appears once`() {
        val plan = TestSelection.planFor(
            listOf(
                "app/src/main/java/com/example/ui/RouteViewModel.kt",
                "app/src/test/java/com/example/ui/RouteViewModelTest.kt",
            ),
            allFiles,
            modules,
        )
        assertEquals(1, plan.targets.size)
    }

    @Test
    fun `planning for a single file is the same machinery`() {
        val plan = TestSelection.planForFile("core/src/main/java/com/example/core/Clock.kt", allFiles, modules)
        assertEquals("com.example.core.ClockTest", plan.targets.single().fqcn)
    }

    // ---- commands ----

    @Test
    fun `commands group by module and kind, filtered to the selected tests`() {
        val plan = TestSelection.planFor(
            listOf(
                "app/src/main/java/com/example/ui/RouteViewModel.kt",
                "core/src/main/java/com/example/core/Clock.kt",
            ),
            allFiles,
            modules,
        )
        val commands = TestSelection.commands(plan) { "demoStagingDebug" }
            .filterIsInstance<TaskResolution.Resolved>()
            .map { it.task.task }

        assertEquals(2, commands.size)
        assertTrue(commands.any { it.startsWith(":app:testDemoStagingDebugUnitTest --tests") })
        assertTrue(commands.any { it.contains("com.example.ui.RouteViewModelTest") })
        assertTrue(commands.any { it.startsWith(":core:testDemoStagingDebugUnitTest --tests") })
    }

    /** Several tests in one module become one invocation with several filters, not several invocations. */
    @Test
    fun `tests in one module share a single Gradle invocation`() {
        val plan = TestSelection.planFor(
            listOf(
                "app/src/test/java/com/example/ui/RouteViewModelTest.kt",
                "core/src/test/java/com/example/core/ClockTest.kt",
            ),
            allFiles,
            listOf(":app", ":core"),
        )
        val appCommand = TestSelection.commands(plan) { "debug" }
            .filterIsInstance<TaskResolution.Resolved>()
            .single { it.task.task.startsWith(":app:") }
        assertEquals(1, Regex("--tests").findAll(appCommand.task.task).count())
    }

    @Test
    fun `an unresolvable variant surfaces the refusal rather than a wrong command`() {
        val plan = TestSelection.planFor(
            listOf("app/src/main/java/com/example/ui/RouteViewModel.kt"),
            allFiles,
            modules,
        )
        val results = TestSelection.commands(plan) { null }
        assertTrue(results.single() is TaskResolution.Refused)
    }

    @Test
    fun `instrumented and unit tests in one module are separate invocations`() {
        val plan = TestSelection.planFor(
            listOf(
                "app/src/main/java/com/example/ui/RouteViewModel.kt",
                "app/src/main/java/com/example/ui/RouteScreen.kt",
            ),
            allFiles,
            modules,
        )
        val commands = TestSelection.commands(plan) { "debug" }
            .filterIsInstance<TaskResolution.Resolved>()
            .map { it.task.task }
        assertEquals(2, commands.size)
        assertTrue(commands.any { it.contains("testDebugUnitTest") })
        assertTrue(commands.any { it.contains("connectedDebugAndroidTest") })
    }
}
