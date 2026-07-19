package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputParsersTest {

    @Test fun detectsGradleAndTestCommands() {
        assertTrue(OutputParsers.looksLikeGradle("./gradlew build"))
        assertTrue(OutputParsers.isTestCommand("./gradlew :app:testDebugUnitTest"))
        assertFalse(OutputParsers.isTestCommand("ls -la"))
    }

    @Test fun extractsGradleTasks() {
        assertEquals(
            listOf("clean", ":app:testDebug"),
            OutputParsers.extractGradleTasks("./gradlew clean :app:testDebug --stacktrace"),
        )
    }

    @Test fun parsesGradleTestSummary() {
        val s = OutputParsers.parseTestSummary("> Task :app:test\n12 tests completed, 2 failed\nBUILD FAILED")
        assertEquals(10, s!!.passed)
        assertEquals(2, s.failed)
    }

    @Test fun parsesJunitSummary() {
        val s = OutputParsers.parseTestSummary("Tests run: 5, Failures: 1, Errors: 0")
        assertEquals(4, s!!.passed)
        assertEquals(1, s.failed)
    }

    @Test fun capturesFailedTestNames() {
        val s = OutputParsers.parseTestSummary("com.example.HomeViewModelTest > loads data FAILED")
        assertEquals(1, s!!.failed)
        assertTrue(s.failedNames.any { it.contains("loads data") })
    }

    @Test fun parsesKotlinCompilerError() {
        val diags = OutputParsers.parseCompilerDiagnostics("e: /src/Foo.kt: (12, 5): unresolved reference: bar")
        assertEquals(1, diags.size)
        assertEquals("/src/Foo.kt", diags[0].path)
        assertEquals(12, diags[0].line)
        assertTrue(diags[0].isError)
    }

    @Test fun parsesJavacDiagnostics() {
        val diags = OutputParsers.parseCompilerDiagnostics("/src/Foo.java:12: error: cannot find symbol")
        assertEquals(1, diags.size)
        assertEquals("/src/Foo.java", diags[0].path)
        assertTrue(diags[0].isError)
    }

    @Test fun buildOutcome() {
        assertEquals(true, OutputParsers.parseBuildOutcome("BUILD SUCCESSFUL in 3s"))
        assertEquals(false, OutputParsers.parseBuildOutcome("BUILD FAILED"))
        assertNull(OutputParsers.parseBuildOutcome("nothing here"))
    }

    @Test fun noFalsePositivesFromProse() {
        assertNull(OutputParsers.parseTestSummary("I will now edit the test file and run it."))
    }

    // ---- Android-first command recognition ----

    @Test fun recognisesInstrumentedTests() {
        assertTrue(OutputParsers.isTestCommand("./gradlew connectedDebugAndroidTest"))
        assertTrue(OutputParsers.isTestCommand("./gradlew connectedCheck"))
    }

    @Test fun recognisesAdbActions() {
        assertEquals("install", OutputParsers.adbAction("adb install app-debug.apk"))
        assertEquals("install", OutputParsers.adbAction("adb -s emulator-5554 install app-debug.apk"))
        assertEquals("logcat", OutputParsers.adbAction("adb logcat -d"))
        assertNull(OutputParsers.adbAction("./gradlew assembleDebug"))
        assertNull(OutputParsers.adbAction("readable text about adb usage"))
    }

    @Test fun adbDescriptionsAreHumanReadable() {
        assertEquals("Installing app (adb)", OutputParsers.adbDescription("adb install app.apk"))
        assertEquals("Launching app (adb)", OutputParsers.adbDescription("adb shell am start -n com.x/.Main"))
        assertEquals("Reading logcat (adb)", OutputParsers.adbDescription("adb logcat"))
    }

    @Test fun classifiesAnalysisTools() {
        assertEquals("detekt", OutputParsers.analysisTool("./gradlew detekt"))
        assertEquals("ktlint", OutputParsers.analysisTool("./gradlew ktlintCheck"))
        assertEquals("lint", OutputParsers.analysisTool("./gradlew :app:lintDebug"))
        // "ktlint" must not be mis-classified as "lint".
        assertEquals("ktlint", OutputParsers.analysisTool("ktlint --format"))
        assertNull(OutputParsers.analysisTool("./gradlew assembleDebug"))
    }

    @Test fun parsesAndroidLintWarnings() {
        val out = OutputParsers.parseAnalysisDiagnostics(
            "src/main/AndroidManifest.xml:9: Warning: App is missing recommended attribute [AllowBackup]",
        )
        assertEquals(1, out.size)
        assertEquals("src/main/AndroidManifest.xml", out[0].path)
        assertEquals(9, out[0].line)
        assertFalse(out[0].isError)
    }

    @Test fun parsesDetektAndKtlintFindings() {
        val out = OutputParsers.parseAnalysisDiagnostics(
            "app/src/main/java/Foo.kt:10:5: This expression contains a magic number. [MagicNumber]",
        )
        assertEquals(1, out.size)
        assertEquals("app/src/main/java/Foo.kt", out[0].path)
        assertEquals(10, out[0].line)
    }

    @Test fun analysisParserIgnoresProse() {
        assertTrue(OutputParsers.parseAnalysisDiagnostics("Running detekt on the project now, 3:15 remaining").isEmpty())
    }
}
