package io.mp.sightline.android

/** What the user wants Gradle to do. Each maps to a different task-name shape. */
enum class BuildIntent(val label: String) {
    ASSEMBLE("Build APK"),
    BUNDLE("Build AAB"),
    INSTALL("Install on device"),
    COMPILE("Compile"),
    UNIT_TEST("Run unit tests"),
    INSTRUMENTATION_TEST("Run instrumented tests"),
    LINT("Run lint"),
    CLEAN("Clean"),
    ;

    /** True when the task name needs a variant to be meaningful — see [GradleTasks.resolve]. */
    val variantSpecific: Boolean get() = this != CLEAN
}

/** A resolved, runnable Gradle invocation. */
data class GradleTask(
    val task: String,
    val intent: BuildIntent,
    /** Set when we deliberately did not fall back to an all-variants task. See [GradleTasks.resolve]. */
    val note: String? = null,
)

/** Why a task could not be resolved, in words a card can show. */
data class GradleTaskRefusal(val reason: String, val hint: String)

sealed interface TaskResolution {
    data class Resolved(val task: GradleTask) : TaskResolution
    data class Refused(val refusal: GradleTaskRefusal) : TaskResolution
}

/**
 * Builds Gradle task names from a module and a build variant.
 *
 * Small, but it protects against a genuinely expensive mistake. AGP generates one task per variant plus
 * an aggregate: `assembleDebug` builds one thing, `assemble` builds **every** variant. On a project with
 * two flavour dimensions — five brands × two environments × two build types is twenty variants — the
 * aggregate is not a slower version of the right command, it is a different command that can run for an
 * hour. So when the variant is unknown this **refuses and says why** rather than silently degrading to
 * the aggregate, which is the one failure mode a helpful default would create.
 */
object GradleTasks {

    /** The wrapper, which is what a project should always be built with. */
    const val WRAPPER = "./gradlew"

    fun resolve(intent: BuildIntent, gradlePath: String, variant: String?): TaskResolution {
        val module = gradlePath.trim().takeIf { it.isNotBlank() && it != ":" }?.removeSuffix(":")
        val prefix = module?.let { if (it.startsWith(":")) it else ":$it" }.orEmpty()

        if (!intent.variantSpecific) {
            return TaskResolution.Resolved(GradleTask("$prefix:${taskStem(intent)}", intent))
        }
        if (variant.isNullOrBlank()) {
            return TaskResolution.Refused(
                GradleTaskRefusal(
                    "No build variant is selected, and ${intent.label.lowercase()} without one would run " +
                        "the aggregate task across every variant.",
                    "Pick a variant in the context strip, or build once so the variant can be read from " +
                        "the build output.",
                ),
            )
        }
        val v = VariantName.capitalized(variant)
        val task = when (intent) {
            BuildIntent.ASSEMBLE -> "assemble$v"
            BuildIntent.BUNDLE -> "bundle$v"
            BuildIntent.INSTALL -> "install$v"
            BuildIntent.COMPILE -> "compile${v}Sources"
            BuildIntent.UNIT_TEST -> "test${v}UnitTest"
            // Instrumented tests are named from the *variant*, and only debuggable variants have them.
            BuildIntent.INSTRUMENTATION_TEST -> "connected${v}AndroidTest"
            BuildIntent.LINT -> "lint$v"
            BuildIntent.CLEAN -> "clean"
        }
        return TaskResolution.Resolved(GradleTask("$prefix:$task", intent))
    }

    private fun taskStem(intent: BuildIntent) = when (intent) {
        BuildIntent.CLEAN -> "clean"
        else -> intent.name.lowercase()
    }

    /** The argv to run, wrapper included. Extra args are appended verbatim. */
    fun command(task: GradleTask, extraArgs: List<String> = emptyList()): List<String> =
        listOf(WRAPPER, task.task) + extraArgs

    /**
     * A single test class or method, for "run just this one".
     * `--tests` needs a fully-qualified pattern; a bare class name silently matches nothing.
     */
    fun singleTest(
        gradlePath: String,
        variant: String?,
        fqcn: String,
        method: String? = null,
    ): TaskResolution {
        val base = resolve(BuildIntent.UNIT_TEST, gradlePath, variant)
        if (base !is TaskResolution.Resolved) return base
        val pattern = if (method.isNullOrBlank()) fqcn else "$fqcn.$method"
        return TaskResolution.Resolved(
            base.task.copy(
                task = "${base.task.task} --tests \"$pattern\"",
                note = "filtered to $pattern",
            ),
        )
    }

    /**
     * Is this Gradle invocation one whose *output* we should scan for a typed failure? Used to decide
     * whether a command's result is worth handing to [BuildFailureClassifier].
     */
    fun looksLikeBuildCommand(command: String): Boolean {
        // Anchored on the executable, the same way OutputParsers.adbAction is: an unanchored substring
        // match calls `ls gradlewish` a build, and prose mentioning Gradle a build command.
        val executables = Regex("""(?:^|[;&|]\s*|\s)(?:\S*/)?(gradlew(?:\.bat)?|gradle)(?:\s|$)""")
        return executables.containsMatchIn(command.trim())
    }
}
