package io.mp.sightline.android

/** Where a test runs. The distinction is the point: one needs a device, the other doesn't. */
enum class TestKind(val label: String) {
    /** `src/test/…` — a JVM unit test. Fast, no device. */
    UNIT("JVM unit test"),

    /** `src/androidTest/…` — instrumented. Needs a device or emulator. */
    INSTRUMENTATION("Instrumented test"),
}

/** A test source file, with the module it belongs to. */
data class TestTarget(
    val relativePath: String,
    val fqcn: String,
    val kind: TestKind,
    val gradlePath: String,
)

/** What to run, and — just as importantly — what was changed but has no test. */
data class TestPlan(
    val targets: List<TestTarget>,
    /** Changed production files no test was found for. Stated, never silently dropped. */
    val uncovered: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = targets.isEmpty()
    val needsDevice: Boolean get() = targets.any { it.kind == TestKind.INSTRUMENTATION }
    fun byModule(): Map<String, List<TestTarget>> = targets.groupBy { it.gradlePath }
}

/**
 * Works out which tests are worth running for a set of changed files.
 *
 * Path-based rather than PSI-based on purpose. The source-set layout (`src/test`, `src/androidTest`) is
 * an AGP contract, and the `Foo` → `FooTest` convention is near-universal, so this resolves the common
 * case with no index and no read action — which means it is unit-tested and works during indexing, when
 * a developer who just changed something is most likely to ask.
 *
 * Where it can't resolve, it says so: [TestPlan.uncovered] lists changed files with no matching test.
 * Silently running fewer tests than the user expects is the failure mode worth engineering against —
 * a green run that skipped the relevant test is worse than no run.
 */
object TestSelection {

    // Variant-specific source sets are real and common — AGP creates `src/testDebug/`,
    // `src/testDemoStaging/`, `src/androidTestDebug/`. Matching only the bare `src/test/` silently
    // classifies every flavoured test as production code, which then selects no tests at all.
    private val UNIT_MARKER = Regex("""/src/test[^/]*/""")
    private val ANDROID_TEST_MARKER = Regex("""/src/androidTest[^/]*/""")

    /** Conventional suffixes, most specific first, so `FooTest` doesn't shadow `FooAndroidTest`. */
    private val TEST_SUFFIXES = listOf("AndroidTest", "InstrumentedTest", "Test", "Spec")

    fun isTestFile(relativePath: String): Boolean = kindOf(relativePath) != null

    fun kindOf(relativePath: String): TestKind? {
        val p = "/" + relativePath.replace('\\', '/').removePrefix("/")
        return when {
            // androidTest first: it is not a prefix of `test`, but checking the looser pattern first
            // would still be a latent ordering bug the day either pattern loosens.
            ANDROID_TEST_MARKER.containsMatchIn(p) -> TestKind.INSTRUMENTATION
            UNIT_MARKER.containsMatchIn(p) -> TestKind.UNIT
            else -> null
        }
    }

    /** The Gradle path of the module owning [relativePath], given the modules that exist. */
    fun moduleFor(relativePath: String, modules: List<String>): String? {
        val p = relativePath.replace('\\', '/')
        // Longest directory prefix wins, so `:feature:route` beats `:feature` for a file inside it.
        return modules
            .filter { p.startsWith(GradleModuleParser.gradlePathToDirectory(it) + "/") }
            .maxByOrNull { GradleModuleParser.gradlePathToDirectory(it).length }
    }

    /**
     * `app/src/main/java/com/example/RouteViewModel.kt` → `com.example.RouteViewModel`.
     * Anchored on the source-root markers AGP guarantees, so a package-shaped directory elsewhere in
     * the path can't be mistaken for the package.
     */
    fun fqcnOf(relativePath: String): String? {
        val p = relativePath.replace('\\', '/')
        val marker = listOf("/java/", "/kotlin/").firstOrNull { p.contains(it) } ?: return null
        val afterRoot = p.substringAfter(marker)
        val withoutExt = afterRoot.substringBeforeLast('.')
        if (withoutExt.isBlank() || !p.endsWith(".kt") && !p.endsWith(".java")) return null
        return withoutExt.replace('/', '.')
    }

    /**
     * The plan for a set of changed files.
     *
     * [allFiles] is every project file we can see, which is how a *production* change finds its test:
     * changing `RouteViewModel.kt` should run `RouteViewModelTest`, and that mapping only exists if we
     * can look for the test.
     */
    fun planFor(
        changedFiles: List<String>,
        allFiles: List<String>,
        modules: List<String>,
    ): TestPlan {
        val targets = LinkedHashMap<String, TestTarget>()
        val uncovered = mutableListOf<String>()

        val testsByName: Map<String, List<String>> = allFiles
            .filter { isTestFile(it) }
            .groupBy { it.substringAfterLast('/').substringBeforeLast('.') }

        for (changed in changedFiles) {
            if (!changed.endsWith(".kt") && !changed.endsWith(".java")) continue

            // A changed test runs itself — no inference needed, and the strongest signal there is.
            kindOf(changed)?.let { kind ->
                addTarget(targets, changed, kind, modules)
                return@let
            }
            if (isTestFile(changed)) continue

            val simpleName = changed.substringAfterLast('/').substringBeforeLast('.')
            val matches = TEST_SUFFIXES.flatMap { suffix -> testsByName["$simpleName$suffix"].orEmpty() }
            if (matches.isEmpty()) {
                uncovered += changed
                continue
            }
            for (m in matches) {
                kindOf(m)?.let { addTarget(targets, m, it, modules) }
            }
        }
        return TestPlan(targets.values.toList(), uncovered.distinct())
    }

    /** Tests for one file — the "run tests related to this file" action. */
    fun planForFile(relativePath: String, allFiles: List<String>, modules: List<String>): TestPlan =
        planFor(listOf(relativePath), allFiles, modules)

    private fun addTarget(
        into: MutableMap<String, TestTarget>,
        path: String,
        kind: TestKind,
        modules: List<String>,
    ) {
        val fqcn = fqcnOf(path) ?: return
        val module = moduleFor(path, modules) ?: return
        into.putIfAbsent(path, TestTarget(path, fqcn, kind, module))
    }

    /**
     * The commands for a plan, one per (module, kind). Grouped because Gradle is far faster running one
     * task with several `--tests` filters than one invocation per test.
     */
    fun commands(plan: TestPlan, variantOf: (String) -> String?): List<TaskResolution> =
        plan.targets
            .groupBy { it.gradlePath to it.kind }
            .map { (key, tests) ->
                val (gradlePath, kind) = key
                val variant = variantOf(gradlePath)
                val intent = if (kind == TestKind.UNIT) BuildIntent.UNIT_TEST else BuildIntent.INSTRUMENTATION_TEST
                when (val base = GradleTasks.resolve(intent, gradlePath, variant)) {
                    is TaskResolution.Refused -> base
                    is TaskResolution.Resolved -> {
                        val filters = tests.joinToString(" ") { "--tests \"${it.fqcn}\"" }
                        TaskResolution.Resolved(
                            base.task.copy(
                                task = "${base.task.task} $filters",
                                note = "${tests.size} test${if (tests.size == 1) "" else "s"}",
                            ),
                        )
                    }
                }
            }
}
