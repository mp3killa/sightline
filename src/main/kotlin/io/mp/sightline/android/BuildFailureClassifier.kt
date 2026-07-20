package io.mp.sightline.android

/**
 * What went wrong, in Android terms. [UNKNOWN] is a first-class outcome, not a gap — see
 * [BuildFailureClassifier].
 */
enum class FailureKind(val label: String) {
    UNRESOLVED_DEPENDENCY("Dependency not found"),
    DEPENDENCY_CONFLICT("Dependency conflict"),
    DUPLICATE_CLASS("Duplicate class"),
    KOTLIN_AGP_INCOMPATIBLE("Kotlin / plugin version mismatch"),
    COMPOSE_COMPILER("Compose compiler mismatch"),
    KSP_FAILURE("Annotation processing failed (KSP)"),
    KAPT_FAILURE("Annotation processing failed (KAPT)"),
    MANIFEST_MERGE("Manifest merge conflict"),
    DUPLICATE_RESOURCE("Duplicate resource"),
    R8("R8 / shrinking failed"),
    JVM_TARGET_MISMATCH("Java / Kotlin target mismatch"),
    VERSION_CATALOG("Version catalogue error"),
    CONFIGURATION_CACHE("Configuration cache problem"),
    VARIANT_RESOLUTION("Variant resolution failed"),
    SDK_MISSING("Android SDK problem"),
    OUT_OF_MEMORY("Out of memory"),
    COMPILATION_ERROR("Compilation error"),
    TEST_FAILURE("Tests failed"),

    /** Nothing recognised. Carries the raw output and offers **no** cause. */
    UNKNOWN("Build failed"),
}

/**
 * A classified build failure. [rawExcerpt] is always populated, so the user can always get to the
 * original text — a diagnosis that hides its evidence is not one you can check.
 */
data class BuildFailure(
    val kind: FailureKind,
    /** One sentence naming the cause. For [FailureKind.UNKNOWN] this states that it is unrecognised. */
    val headline: String,
    val failedTask: String? = null,
    /** File paths mentioned by the failure, project-relative where the output gave them that way. */
    val affectedFiles: List<String> = emptyList(),
    /** The specific message — the line that actually says what broke. */
    val detail: String? = null,
    /** Concrete next step, or null when we genuinely don't know one. Never filler. */
    val suggestion: String? = null,
    val rawExcerpt: String = "",
) {
    val isRecognised: Boolean get() = kind != FailureKind.UNKNOWN
}

/**
 * Turns raw Gradle output into a typed [BuildFailure] — the "interpretation" leg of docs/ANDROID.md §0,
 * and the reason a Gradle failure is worth a card rather than a wall of text.
 *
 * **The rule that matters more than any pattern here: an unrecognised failure classifies as
 * [FailureKind.UNKNOWN] and shows the raw output.** A build failure is the moment a developer is most
 * likely to act on what they are told and least able to sanity-check it, so a confidently wrong cause
 * costs far more than an honest "not recognised". Every rule below is anchored to text AGP or Gradle
 * actually emits; none of them guess from a keyword appearing somewhere.
 *
 * Rules are ordered most-specific first and the first match wins, because these overlap: a KSP failure
 * also contains "Execution failed for task", and a Compose compiler mismatch is also a Kotlin version
 * mismatch. The more specific diagnosis is the more useful one.
 */
object BuildFailureClassifier {

    fun classify(output: String): BuildFailure? {
        if (output.isBlank()) return null
        if (!looksFailed(output)) return null

        val task = failedTask(output)
        val excerpt = excerpt(output)

        for (rule in rules) {
            rule.match(output)?.let { partial ->
                return partial.copy(
                    failedTask = partial.failedTask ?: task,
                    rawExcerpt = excerpt,
                )
            }
        }
        return BuildFailure(
            kind = FailureKind.UNKNOWN,
            headline = task?.let { "$it failed, and the cause wasn't recognised." }
                ?: "The build failed, and the cause wasn't recognised.",
            failedTask = task,
            // Deliberately no suggestion. An invented next step on an unrecognised failure is exactly
            // the confidently-wrong answer this classifier exists to avoid.
            suggestion = null,
            rawExcerpt = excerpt,
        )
    }

    /** Only classify output that actually reports a failure — a successful build has nothing to explain. */
    private fun looksFailed(output: String): Boolean =
        output.contains("FAILURE: Build failed") ||
            output.contains("BUILD FAILED") ||
            Regex("""(?m)^\s*> Task \S+ FAILED""").containsMatchIn(output) ||
            output.contains("Execution failed for task")

    /** `Execution failed for task ':app:kspDebugKotlin'.` → `:app:kspDebugKotlin` */
    fun failedTask(output: String): String? =
        Regex("""Execution failed for task '([^']+)'""").find(output)?.groupValues?.get(1)
            ?: Regex("""(?m)^\s*> Task (\S+) FAILED""").find(output)?.groupValues?.get(1)

    // ---- rules ----

    private class Rule(val match: (String) -> BuildFailure?)

    private fun rule(build: (String) -> BuildFailure?) = Rule(build)

    private val rules: List<Rule> = listOf(
        // --- annotation processing: most specific, and the most common real failure ---
        rule { out ->
            val ksp = Regex("""(?m)^e: \[ksp] (.+)$""").find(out) ?: return@rule null
            val files = kspFiles(out)
            BuildFailure(
                kind = FailureKind.KSP_FAILURE,
                headline = "A KSP annotation processor rejected the code it was given.",
                affectedFiles = files,
                detail = ksp.groupValues[1].trim(),
                suggestion = "Fix the reported symbol, then re-run. If the message names generated code, " +
                    "the generator's input changed — a clean build will not fix it on its own.",
            )
        },
        rule { out ->
            if (!out.contains("kapt") && !out.contains("[Dagger/")) return@rule null
            val dagger = Regex("""(?m)^\s*(?:e: )?error: (\[Dagger/[^]]+] .+)$""").find(out)
            val generic = Regex("""(?m)^\s*(?:e: )?error: (.+)$""").find(out)
            val message = (dagger ?: generic)?.groupValues?.get(1)?.trim() ?: return@rule null
            BuildFailure(
                kind = FailureKind.KAPT_FAILURE,
                headline = if (dagger != null)
                    "A Dagger/Hilt binding is missing or ambiguous."
                else "A KAPT annotation processor failed.",
                affectedFiles = pathsIn(out),
                detail = message,
                suggestion = if (dagger != null)
                    "Add the missing @Provides/@Binds, or check the component the binding is requested from."
                else "Fix the processor error above; KAPT errors usually name the offending declaration.",
            )
        },

        // --- version compatibility ---
        rule { out ->
            val m = Regex("""This version \(([^)]+)\) of the Compose Compiler requires Kotlin version ([^\s]+) but you appear to be using Kotlin version ([^\s]+)""")
                .find(out) ?: return@rule null
            BuildFailure(
                kind = FailureKind.COMPOSE_COMPILER,
                headline = "The Compose compiler and the Kotlin compiler are on incompatible versions.",
                detail = "Compose compiler ${m.groupValues[1]} expects Kotlin ${m.groupValues[2]}, " +
                    "but the build is using Kotlin ${m.groupValues[3]}.",
                suggestion = "Align them in the version catalogue — either move Kotlin to " +
                    "${m.groupValues[2]}, or move the Compose compiler to the release that matches " +
                    "Kotlin ${m.groupValues[3]}.",
            )
        },
        rule { out ->
            val m = Regex("""(?m)^.*(?:Android Gradle plugin|The Android Gradle plugin) (?:supports only|requires) Kotlin.*$""")
                .find(out) ?: return@rule null
            BuildFailure(
                kind = FailureKind.KOTLIN_AGP_INCOMPATIBLE,
                headline = "The Kotlin Gradle plugin and the Android Gradle plugin are incompatible.",
                detail = m.value.trim(),
                suggestion = "Raise the Kotlin Gradle plugin to at least the version named above.",
            )
        },
        rule { out ->
            val m = Regex("""Inconsistent JVM-target compatibility detected for tasks '([^']+)' \(([^)]+)\) and '([^']+)' \(([^)]+)\)""")
                .find(out) ?: return@rule null
            BuildFailure(
                kind = FailureKind.JVM_TARGET_MISMATCH,
                headline = "Java and Kotlin are compiling to different JVM targets.",
                detail = "${m.groupValues[1]} targets ${m.groupValues[2]}, " +
                    "${m.groupValues[3]} targets ${m.groupValues[4]}.",
                suggestion = "Set both to the same target — `compileOptions { … }` and " +
                    "`kotlin { compilerOptions { jvmTarget … } }` in the module's build file.",
            )
        },

        // --- dependencies ---
        rule { out ->
            val m = Regex("""(?m)^\s*> Could not find (\S+?)\.?$""").find(out)
                ?: Regex("""(?m)^\s*> Could not resolve (\S+?)\.?$""").find(out)
                ?: return@rule null
            BuildFailure(
                kind = FailureKind.UNRESOLVED_DEPENDENCY,
                headline = "A dependency could not be found in any configured repository.",
                // `\S+` is greedy enough to swallow Gradle's sentence-ending period into the coordinate.
                detail = m.groupValues[1].trim().removeSuffix("."),
                suggestion = "Check the coordinate for a typo, and that a repository serving it is " +
                    "declared in `settings.gradle` `dependencyResolutionManagement`.",
            )
        },
        rule { out ->
            val m = Regex("""Duplicate class (\S+) found in modules (.+)$""", RegexOption.MULTILINE)
                .find(out) ?: return@rule null
            BuildFailure(
                kind = FailureKind.DUPLICATE_CLASS,
                headline = "The same class is on the classpath from two different dependencies.",
                detail = "${m.groupValues[1]} — from ${m.groupValues[2].trim()}",
                suggestion = "Exclude the duplicate from one dependency, or align both onto one version. " +
                    "`./gradlew :app:dependencies` shows which one pulls it in.",
            )
        },
        rule { out ->
            val m = Regex("""(?m)^\s*- (\S+) between versions (\S+) and (\S+)""").find(out)
                ?: return@rule null
            if (!out.contains("Conflict(s) found")) return@rule null
            BuildFailure(
                kind = FailureKind.DEPENDENCY_CONFLICT,
                headline = "Two dependency versions conflict and Gradle could not pick one.",
                detail = "${m.groupValues[1]}: ${m.groupValues[2]} vs ${m.groupValues[3]}",
                suggestion = "Pin the version in the catalogue, or add a resolution strategy.",
            )
        },

        // --- Android resources and manifest ---
        rule { out ->
            val m = Regex("""Manifest merger failed\s*:?\s*(.+)""").find(out) ?: return@rule null
            val suggestion = Regex("""Suggestion: (.+)""").find(out)?.groupValues?.get(1)?.trim()
            BuildFailure(
                kind = FailureKind.MANIFEST_MERGE,
                headline = "The merged manifest has a conflict between your manifest and a library's.",
                affectedFiles = pathsIn(out).filter { it.endsWith("AndroidManifest.xml") },
                detail = m.groupValues[1].trim().lineSequence().first().trim(),
                // AGP's own suggestion is better than anything invented, so prefer it verbatim.
                suggestion = suggestion
                    ?: "Add a `tools:replace` or `tools:remove` for the conflicting attribute in your manifest.",
            )
        },
        rule { out ->
            if (!out.contains("Duplicate resources")) return@rule null
            BuildFailure(
                kind = FailureKind.DUPLICATE_RESOURCE,
                headline = "The same resource is declared twice.",
                affectedFiles = pathsIn(out).filter { it.contains("/res/") },
                detail = Regex("""(?m)^.*Duplicate resources.*$""").find(out)?.value?.trim(),
                suggestion = "Remove or rename one of the declarations. A flavour's resource overrides " +
                    "`main`'s silently; two in the *same* source set is the error.",
            )
        },

        // --- shrinking ---
        rule { out ->
            if (!out.contains("R8") && !out.contains("CompilationFailedException")) return@rule null
            val missing = Regex("""Missing class (\S+)""").findAll(out).map { it.groupValues[1] }.toList()
            BuildFailure(
                kind = FailureKind.R8,
                headline = if (missing.isNotEmpty())
                    "R8 could not find classes that the code references."
                else "R8 failed while shrinking the build.",
                detail = if (missing.isNotEmpty()) missing.take(5).joinToString(", ") else null,
                suggestion = if (missing.isNotEmpty())
                    "Add `-dontwarn`/`-keep` rules for these, or the dependency that provides them is " +
                        "missing at runtime."
                else "Check the R8 output above; a missing keep rule is the usual cause.",
            )
        },

        // --- build configuration ---
        rule { out ->
            if (!out.contains("Invalid catalog definition") && !out.contains("VersionCatalogProblem")) return@rule null
            BuildFailure(
                kind = FailureKind.VERSION_CATALOG,
                headline = "The version catalogue is invalid.",
                affectedFiles = listOf("gradle/libs.versions.toml"),
                detail = Regex("""(?m)^\s*- Problem: (.+)$""").find(out)?.groupValues?.get(1)?.trim(),
                suggestion = "Fix the alias or version in `gradle/libs.versions.toml`.",
            )
        },
        rule { out ->
            if (!out.contains("Configuration cache problems")) return@rule null
            BuildFailure(
                kind = FailureKind.CONFIGURATION_CACHE,
                headline = "A task is not compatible with the configuration cache.",
                detail = Regex("""(?m)^\s*- (Task .+)$""").find(out)?.groupValues?.get(1)?.trim(),
                suggestion = "Fix the task, or run with `--no-configuration-cache` to confirm that is " +
                    "the only problem before changing anything else.",
            )
        },
        rule { out ->
            val m = Regex("""(?m)^.*(?:No matching variant|Could not determine the dependencies of|no matching configuration).*$""")
                .find(out) ?: return@rule null
            if (!out.contains("variant")) return@rule null
            BuildFailure(
                kind = FailureKind.VARIANT_RESOLUTION,
                headline = "Gradle could not match a variant between modules.",
                detail = m.value.trim(),
                suggestion = "Usually a missing `matchingFallbacks` when one module declares flavours " +
                    "the other doesn't.",
            )
        },

        // --- environment ---
        rule { out ->
            if (!out.contains("SDK location not found") && !out.contains("licences have not been accepted") &&
                !out.contains("licenses have not been accepted")
            ) {
                return@rule null
            }
            val licence = out.contains("have not been accepted")
            BuildFailure(
                kind = FailureKind.SDK_MISSING,
                headline = if (licence) "An SDK package's licence has not been accepted."
                else "The Android SDK location is not configured.",
                suggestion = if (licence) "Run `sdkmanager --licenses`, or accept them in the SDK Manager."
                else "Set `sdk.dir` in `local.properties`, or the ANDROID_HOME environment variable.",
            )
        },
        rule { out ->
            if (!out.contains("OutOfMemoryError") && !out.contains("Java heap space")) return@rule null
            BuildFailure(
                kind = FailureKind.OUT_OF_MEMORY,
                headline = "The build ran out of memory.",
                detail = Regex("""(?m)^.*(?:OutOfMemoryError|Java heap space).*$""").find(out)?.value?.trim(),
                suggestion = "Raise `org.gradle.jvmargs` in `gradle.properties` (and `kotlin.daemon.jvmargs` " +
                    "if the Kotlin daemon is the one dying).",
            )
        },

        // --- generic, and therefore last ---
        rule { out ->
            val errors = Regex("""(?m)^e: (?:file://)?(\S+?):(\d+)(?::(\d+))? (.+)$""").findAll(out).toList()
            if (errors.isEmpty()) return@rule null
            BuildFailure(
                kind = FailureKind.COMPILATION_ERROR,
                headline = if (errors.size == 1) "The Kotlin compiler reported an error."
                else "The Kotlin compiler reported ${errors.size} errors.",
                affectedFiles = errors.map { it.groupValues[1] }.distinct(),
                detail = errors.first().let { "${it.groupValues[1]}:${it.groupValues[2]} — ${it.groupValues[4]}" },
                suggestion = null, // the compiler already said what to do; repeating it adds nothing
            )
        },
        rule { out ->
            val m = Regex("""There were failing tests""").find(out)
                ?: Regex("""(\d+) tests? completed, (\d+) failed""").find(out)
                ?: return@rule null
            val counts = Regex("""(\d+) tests? completed, (\d+) failed""").find(out)
            BuildFailure(
                kind = FailureKind.TEST_FAILURE,
                headline = counts?.let { "${it.groupValues[2]} of ${it.groupValues[1]} tests failed." }
                    ?: "Tests failed.",
                detail = m.value.trim(),
                suggestion = "Open the HTML report Gradle printed above for the failing assertions.",
            )
        },
    )

    // ---- helpers ----

    /** File paths KSP mentions, which it prints as `path:line: message` rather than Kotlin's `e:` form. */
    private fun kspFiles(out: String): List<String> =
        Regex("""(?m)^e: \[ksp] (?:file://)?([^\s:]+\.kt):\d+""")
            .findAll(out).map { it.groupValues[1] }.distinct().toList()

    /** Source paths anywhere in the output. Conservative: only known Android/JVM source and resource files. */
    private fun pathsIn(out: String): List<String> =
        Regex("""(?:file://)?((?:/|\w:[/\\])?[^\s:'"()]+\.(?:kt|java|xml))""")
            .findAll(out).map { it.groupValues[1] }.distinct().take(12).toList()

    /**
     * The part of the output worth showing on a card. Gradle prints a long tail — "Try:", "Get more
     * help", deprecation notices, the build scan advert — that pushes the actual cause off the top.
     */
    fun excerpt(output: String, maxLines: Int = 40): String {
        val lines = output.lines()
        val start = lines.indexOfFirst {
            it.contains("FAILURE: Build failed") || it.contains("* What went wrong") ||
                it.contains("FAILED") || it.startsWith("e: ")
        }.takeIf { it >= 0 } ?: 0
        val end = lines.indexOfFirst { i -> i.startsWith("* Try:") || i.startsWith("* Get more help") }
            .takeIf { it > start } ?: lines.size
        return lines.subList(start, end.coerceAtMost(lines.size))
            .dropLastWhile { it.isBlank() }
            .take(maxLines)
            .joinToString("\n")
    }
}
