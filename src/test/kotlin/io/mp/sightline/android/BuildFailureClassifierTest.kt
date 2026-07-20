package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case is built on output Gradle/AGP actually emits. The classifier's whole value is that it is
 * right or silent, so the fixtures matter more than the count.
 */
class BuildFailureClassifierTest {

    private fun gradleFailure(body: String, task: String = ":app:assembleDemoStagingDebug") = """
        > Task $task FAILED

        FAILURE: Build failed with an exception.

        * What went wrong:
        Execution failed for task '$task'.
        $body

        * Try:
        > Run with --stacktrace option to get the stack trace.

        BUILD FAILED in 12s
    """.trimIndent()

    // ---- the rule that matters most ----

    @Test
    fun `a successful build is not a failure`() {
        assertNull(BuildFailureClassifier.classify("BUILD SUCCESSFUL in 8s\n42 actionable tasks"))
        assertNull(BuildFailureClassifier.classify(""))
    }

    /**
     * The load-bearing behaviour: an unrecognised failure must not acquire an invented cause. A build
     * failure is when a developer is most likely to act on what they're told and least able to check it.
     */
    @Test
    fun `an unrecognised failure keeps the raw output and offers no cause`() {
        val f = BuildFailureClassifier.classify(
            gradleFailure("> Something entirely novel went wrong in a way we have never seen"),
        )!!
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertFalse(f.isRecognised)
        assertNull("no fabricated next step", f.suggestion)
        assertTrue(f.headline.contains("wasn't recognised"))
        assertTrue("raw output must survive", f.rawExcerpt.contains("entirely novel"))
        assertEquals(":app:assembleDemoStagingDebug", f.failedTask)
    }

    @Test
    fun `the failed task is extracted from either shape`() {
        assertEquals(
            ":app:kspDebugKotlin",
            BuildFailureClassifier.failedTask("Execution failed for task ':app:kspDebugKotlin'."),
        )
        assertEquals(
            ":core:compileDebugKotlin",
            BuildFailureClassifier.failedTask("> Task :core:compileDebugKotlin FAILED"),
        )
        assertNull(BuildFailureClassifier.failedTask("nothing here"))
    }

    // ---- annotation processing ----

    @Test
    fun `a KSP failure names the processor error and the file`() {
        val out = """
            > Task :app:kspDemoStagingDebugKotlin FAILED
            e: [ksp] /Users/me/proj/app/src/main/java/com/example/data/DeliveryDao.kt:15: Type of the parameter must be a class annotated with @Entity
            Execution failed for task ':app:kspDemoStagingDebugKotlin'.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.KSP_FAILURE, f.kind)
        assertTrue(f.detail!!.contains("@Entity"))
        assertTrue(f.affectedFiles.any { it.endsWith("DeliveryDao.kt") })
        assertEquals(":app:kspDemoStagingDebugKotlin", f.failedTask)
    }

    @Test
    fun `a Dagger missing binding is recognised specifically`() {
        val out = """
            > Task :app:kaptDebugKotlin FAILED
            error: [Dagger/MissingBinding] com.example.data.RouteRepository cannot be provided without an @Provides-annotated method.
            Execution failed for task ':app:kaptDebugKotlin'.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.KAPT_FAILURE, f.kind)
        assertTrue(f.headline.contains("binding"))
        assertTrue(f.detail!!.contains("RouteRepository"))
        assertTrue(f.suggestion!!.contains("@Provides"))
    }

    // ---- version compatibility ----

    @Test
    fun `a Compose compiler mismatch names both versions and how to align them`() {
        val out = gradleFailure(
            "> This version (1.5.4) of the Compose Compiler requires Kotlin version 1.9.20 " +
                "but you appear to be using Kotlin version 1.9.21 which is not known to be compatible.",
            ":app:compileDebugKotlin",
        )
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.COMPOSE_COMPILER, f.kind)
        assertTrue(f.detail!!.contains("1.9.20"))
        assertTrue(f.detail.contains("1.9.21"))
        assertTrue(f.suggestion!!.contains("version catalogue"))
    }

    /** Compose is also a Kotlin mismatch; the more specific diagnosis must win. */
    @Test
    fun `the compose rule beats the generic kotlin rule`() {
        val out = gradleFailure(
            """
            > This version (1.5.4) of the Compose Compiler requires Kotlin version 1.9.20 but you appear to be using Kotlin version 1.9.21
            > The Android Gradle plugin supports only Kotlin Gradle plugin version 1.5.20 and higher.
            """.trimIndent(),
        )
        assertEquals(FailureKind.COMPOSE_COMPILER, BuildFailureClassifier.classify(out)!!.kind)
    }

    @Test
    fun `a JVM target mismatch names both tasks and both targets`() {
        val out = gradleFailure(
            "> Inconsistent JVM-target compatibility detected for tasks 'compileDebugJavaWithJavac' (17) " +
                "and 'compileDebugKotlin' (1.8).",
            ":app:compileDebugKotlin",
        )
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.JVM_TARGET_MISMATCH, f.kind)
        assertTrue(f.detail!!.contains("17"))
        assertTrue(f.detail.contains("1.8"))
    }

    // ---- dependencies ----

    @Test
    fun `an unresolved dependency is distinguished from a conflict`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:checkDebugAarMetadata'.
            > Could not resolve all files for configuration ':app:debugCompileClasspath'.
               > Could not find com.example:routing:2.1.0.
                 Searched in the following locations:
                   - https://repo.maven.apache.org/maven2/com/example/routing/2.1.0/routing-2.1.0.pom
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.UNRESOLVED_DEPENDENCY, f.kind)
        assertEquals("com.example:routing:2.1.0", f.detail)
        assertTrue(f.suggestion!!.contains("repository"))
    }

    @Test
    fun `a duplicate class names both modules`() {
        val out = gradleFailure(
            "> Duplicate class kotlinx.coroutines.AwaitAll found in modules " +
                "jetified-kotlinx-coroutines-core-1.6.0 and jetified-kotlinx-coroutines-core-jvm-1.6.0",
            ":app:checkDebugDuplicateClasses",
        )
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.DUPLICATE_CLASS, f.kind)
        assertTrue(f.detail!!.contains("AwaitAll"))
        assertTrue(f.suggestion!!.contains("dependencies"))
    }

    @Test
    fun `a version conflict is recognised only alongside its header`() {
        val out = gradleFailure(
            """
            > Conflict(s) found for the following module(s):
                - androidx.core:core between versions 1.12.0 and 1.9.0
            """.trimIndent(),
        )
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.DEPENDENCY_CONFLICT, f.kind)
        assertTrue(f.detail!!.contains("1.12.0"))
    }

    // ---- manifest and resources ----

    /** AGP's own suggestion is better than anything invented, so it should be preferred verbatim. */
    @Test
    fun `a manifest merge conflict reuses AGP's own suggestion`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:processDemoStagingDebugMainManifest'.
            > Manifest merger failed : Attribute application@appComponentFactory value=(androidx.core.app.CoreComponentFactory) from [androidx.core:core:1.0.0] AndroidManifest.xml:22:18-91
              is also present at [com.android.support:support-compat:28.0.0] AndroidManifest.xml:22:18-91 value=(android.support.v4.app.CoreComponentFactory).
              Suggestion: add 'tools:replace="android:appComponentFactory"' to <application> element at AndroidManifest.xml:5:5-19:19 to override.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.MANIFEST_MERGE, f.kind)
        assertTrue(f.suggestion!!.contains("tools:replace"))
        assertTrue(f.detail!!.contains("appComponentFactory"))
    }

    @Test
    fun `a duplicate resource points at the res files`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:mergeDebugResources'.
            > [string/app_name] /Users/me/proj/app/src/main/res/values/strings.xml	Error: Duplicate resources
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.DUPLICATE_RESOURCE, f.kind)
        assertTrue(f.affectedFiles.any { it.contains("/res/values/strings.xml") })
    }

    // ---- shrinking ----

    @Test
    fun `R8 missing classes are listed`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:minifyReleaseWithR8'.
            > com.android.tools.r8.CompilationFailedException: Compilation failed to complete
               > Missing class com.google.errorprone.annotations.Immutable (referenced from: com.example.Foo)
                 Missing class javax.annotation.Nullable (referenced from: com.example.Bar)
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.R8, f.kind)
        assertTrue(f.detail!!.contains("errorprone"))
        assertTrue(f.suggestion!!.contains("dontwarn"))
    }

    // ---- build configuration ----

    @Test
    fun `a version catalogue error points at the toml`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Invalid catalog definition:
              - Problem: In version catalog libs, alias 'androidx core' is not a valid alias.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.VERSION_CATALOG, f.kind)
        assertEquals(listOf("gradle/libs.versions.toml"), f.affectedFiles)
        assertTrue(f.detail!!.contains("not a valid alias"))
    }

    @Test
    fun `a configuration cache problem suggests confirming before changing anything`() {
        val out = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Configuration cache problems found in this build.
            1 problem was found storing the configuration cache.
              - Task `:app:customTask` of type `CustomTask`: cannot serialize object of type 'org.gradle.api.Project'
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.CONFIGURATION_CACHE, f.kind)
        assertTrue(f.suggestion!!.contains("--no-configuration-cache"))
    }

    // ---- environment ----

    @Test
    fun `a missing SDK and an unaccepted licence are told apart`() {
        val noSdk = BuildFailureClassifier.classify(
            gradleFailure("> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable."),
        )!!
        assertEquals(FailureKind.SDK_MISSING, noSdk.kind)
        assertTrue(noSdk.suggestion!!.contains("local.properties"))

        val licence = BuildFailureClassifier.classify(
            gradleFailure("> Failed to install the following Android SDK packages as some licences have not been accepted."),
        )!!
        assertEquals(FailureKind.SDK_MISSING, licence.kind)
        assertTrue(licence.suggestion!!.contains("licenses"))
    }

    @Test
    fun `an out of memory failure is recognised`() {
        val f = BuildFailureClassifier.classify(
            gradleFailure("> java.lang.OutOfMemoryError: Java heap space"),
        )!!
        assertEquals(FailureKind.OUT_OF_MEMORY, f.kind)
        assertTrue(f.suggestion!!.contains("org.gradle.jvmargs"))
    }

    // ---- generic fallbacks ----

    @Test
    fun `kotlin compilation errors are collected with their files`() {
        val out = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///Users/me/proj/app/src/main/java/com/example/RouteViewModel.kt:42:9 Unresolved reference: isRefreshing
            e: file:///Users/me/proj/app/src/main/java/com/example/RouteScreen.kt:17:5 Type mismatch: inferred type is String but Int was expected
            Execution failed for task ':app:compileDebugKotlin'.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.COMPILATION_ERROR, f.kind)
        assertTrue(f.headline.contains("2 errors"))
        assertEquals(2, f.affectedFiles.size)
        assertTrue(f.affectedFiles.any { it.endsWith("RouteViewModel.kt") })
        // The compiler already said what to do; repeating it would be filler.
        assertNull(f.suggestion)
    }

    @Test
    fun `test failures report the counts`() {
        val out = """
            > Task :app:testDemoStagingDebugUnitTest FAILED

            RouteViewModelTest > loadsRoutes FAILED
                java.lang.AssertionError at RouteViewModelTest.kt:42

            12 tests completed, 1 failed

            FAILURE: Build failed with an exception.
        """.trimIndent()
        val f = BuildFailureClassifier.classify(out)!!
        assertEquals(FailureKind.TEST_FAILURE, f.kind)
        assertTrue(f.headline.contains("1 of 12"))
    }

    // ---- the excerpt ----

    /** Gradle's tail pushes the actual cause off the top of any card that shows raw output. */
    @Test
    fun `the excerpt drops Gradle's boilerplate tail`() {
        val out = """
            some earlier noise
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:foo'.
            > the actual cause

            * Try:
            > Run with --stacktrace option to get the stack trace.
            > Get more help at https://help.gradle.org.
        """.trimIndent()
        val excerpt = BuildFailureClassifier.excerpt(out)
        assertTrue(excerpt.startsWith("FAILURE: Build failed"))
        assertTrue(excerpt.contains("the actual cause"))
        assertFalse(excerpt.contains("--stacktrace"))
        assertFalse(excerpt.contains("help.gradle.org"))
        assertFalse("earlier noise should be trimmed", excerpt.contains("earlier noise"))
    }

    @Test
    fun `the excerpt is capped so a card cannot be buried`() {
        val huge = "FAILURE: Build failed with an exception.\n" + (1..500).joinToString("\n") { "line $it" }
        assertTrue(BuildFailureClassifier.excerpt(huge).lines().size <= 40)
    }

    @Test
    fun `every recognised failure carries a headline and its raw output`() {
        val samples = listOf(
            gradleFailure("> Could not find com.example:lib:1.0."),
            gradleFailure("> Duplicate class a.B found in modules x and y"),
            gradleFailure("> Manifest merger failed : something"),
            gradleFailure("> java.lang.OutOfMemoryError: Java heap space"),
        )
        for (s in samples) {
            val f = BuildFailureClassifier.classify(s)!!
            assertTrue("headline must not be blank", f.headline.isNotBlank())
            assertTrue("raw excerpt must survive", f.rawExcerpt.isNotBlank())
            assertNotNull(f.failedTask)
        }
    }
}
