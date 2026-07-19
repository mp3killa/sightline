package io.mp.claudecodepanel.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ActivityInterpreterTest {

    private val interp = ActivityInterpreter { Instant.parse("2026-07-19T10:00:00Z") }
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun readBecomesFileRead() {
        val ev = interp.toolUse("t1", "Read", obj("""{"file_path":"/a/Foo.kt"}"""))
        assertEquals(1, ev.size)
        val e = ev[0] as FileRead
        assertEquals("/a/Foo.kt", e.path)
        assertEquals(1f, e.confidence, 0.0001f)
    }

    @Test fun editVsWrite() {
        val edit = interp.toolUse("e", "Edit", obj("""{"file_path":"/a/Foo.kt"}"""))[0] as FileEdited
        assertTrue(!edit.created)
        val write = interp.toolUse("w", "Write", obj("""{"file_path":"/a/New.kt"}"""))[0] as FileEdited
        assertTrue(write.created)
    }

    @Test fun deniedEditCorrelatesPathFromPendingToolUse() {
        interp.toolUse("e1", "Edit", obj("""{"file_path":"/a/Foo.kt"}"""))
        val ev = interp.toolDenied("e1", "Edit", null).single() as ActivityDenied
        assertEquals("/a/Foo.kt", ev.path)
        assertTrue(!ev.cancelled)
    }

    @Test fun deniedToolFallsBackToInputWhenNoPending() {
        // No prior toolUse recorded the id, so path is recovered from the approval request input.
        val ev = interp.toolDenied("unknown", "Write", obj("""{"file_path":"/a/New.kt"}"""))
            .single() as ActivityDenied
        assertEquals("/a/New.kt", ev.path)
    }

    @Test fun deniedToolResultDoesNotDoubleFireAfterDeny() {
        interp.toolUse("e2", "Edit", obj("""{"file_path":"/a/Foo.kt"}"""))
        interp.toolDenied("e2", "Edit", null)
        // pending was consumed by the denial, so a stray result can't resurrect the edit.
        assertTrue(interp.toolResult("e2", "boom", isError = true).none { it is FileEdited })
    }

    @Test fun adbCommandGetsHumanDescription() {
        val e = interp.toolUse("a1", "Bash", obj("""{"command":"adb install app-debug.apk"}""")).single() as CommandRun
        assertEquals("Installing app (adb)", e.description)
    }

    @Test fun gradleLintIsAnalysisAndResultAttachesWarningToFile() {
        val use = interp.toolUse("l1", "Bash", obj("""{"command":"./gradlew :app:lintDebug"}"""))
        assertTrue(use.any { it is GradleTaskRun })
        val res = interp.toolResult(
            "l1",
            "src/main/AndroidManifest.xml:9: Warning: Missing attribute [AllowBackup]\nBUILD SUCCESSFUL",
            isError = false,
        )
        val warn = res.filterIsInstance<WarningObserved>().single()
        assertEquals("src/main/AndroidManifest.xml", warn.path)
        assertTrue(res.any { it is BuildReported })
    }

    @Test fun standaloneDetektAttachesFindings() {
        interp.toolUse("d1", "Bash", obj("""{"command":"detekt --input app/src"}"""))
        val res = interp.toolResult("d1", "app/src/main/Foo.kt:10:5: Magic number [MagicNumber]", isError = false)
        assertTrue(res.filterIsInstance<WarningObserved>().any { it.path == "app/src/main/Foo.kt" })
    }

    @Test fun grepBecomesSearch() {
        val e = interp.toolUse("g", "Grep", obj("""{"pattern":"ViewModel","path":"app/src"}"""))[0] as FileSearched
        assertEquals("ViewModel", e.query)
        assertEquals("app/src", e.path)
    }

    @Test fun gradleTestCommandEmitsSingleTestNode() {
        val ev = interp.toolUse("b1", "Bash", obj("""{"command":"./gradlew :app:test"}"""))
        assertTrue(ev.any { it is TestStarted })
        // A gradle test task is one Testing node, not also a duplicate Gradle-task node.
        assertTrue(ev.none { it is GradleTaskRun })
    }

    @Test fun gradleNonTestCommandEmitsGradleTask() {
        val ev = interp.toolUse("b9", "Bash", obj("""{"command":"./gradlew :app:assembleDebug"}"""))
        assertTrue(ev.any { it is GradleTaskRun })
    }

    @Test fun plainBashIsCommand() {
        val ev = interp.toolUse("b2", "Bash", obj("""{"command":"ls -la","description":"list"}"""))
        val e = ev.single() as CommandRun
        assertEquals("list", e.description)
    }

    @Test fun bashTestResultParsesSummary() {
        interp.toolUse("b3", "Bash", obj("""{"command":"./gradlew test"}"""))
        val ev = interp.toolResult("b3", "3 tests completed, 1 failed\ncom.x.FooTest > a FAILED", isError = true)
        val report = ev.filterIsInstance<TestReported>().single()
        assertEquals(2, report.passed)
        assertEquals(1, report.failed)
    }

    @Test fun toolErrorAttributedToTouchedFile() {
        interp.toolUse("e9", "Edit", obj("""{"file_path":"/a/Broken.kt"}"""))
        val ev = interp.toolResult("e9", "String literal is not properly closed", isError = true)
        val err = ev.filterIsInstance<ErrorObserved>().single()
        assertEquals("/a/Broken.kt", err.path)
    }

    @Test fun mcpSymbolInspection() {
        val e = interp.toolUse("s1", "mcp__studio__get_symbol_info", obj("""{"symbolName":"DriverRepository"}"""))[0] as SymbolInspected
        assertEquals("DriverRepository", e.name)
    }

    @Test fun statusIsLowConfidenceAndCreatesNoFileEvent() {
        val ev = interp.status("Thinking")
        val s = ev.single() as StatusUpdated
        assertTrue(s.confidence < 1f)
    }

    @Test fun resetClearsPendingCorrelation() {
        interp.toolUse("x", "Bash", obj("""{"command":"./gradlew test"}"""))
        interp.reset()
        // After reset the result can't be correlated, so no test summary is produced from the id.
        val ev = interp.toolResult("x", "3 tests completed, 0 failed", isError = false)
        assertTrue(ev.none { it is TestReported })
    }

    @Test fun gradleNonZeroExitReportsBuildFailureWithoutSummary() {
        interp.toolUse("g1", "Bash", obj("""{"command":"./gradlew assembleDebug"}"""))
        // A failure that prints no parseable "BUILD FAILED" line — the non-zero exit is the only signal.
        val ev = interp.toolResult("g1", "Execution failed for task ':app:processDebug'.", isError = true)
        assertFalse(ev.filterIsInstance<BuildReported>().single().success)
        assertTrue("no redundant generic error node", ev.none { it is ErrorObserved })
    }

    @Test fun gradleExitZeroWithoutSummaryReportsNothing() {
        interp.toolUse("g3", "Bash", obj("""{"command":"./gradlew tasks"}"""))
        // Exit 0, no build summary — must NOT fabricate a success or failure.
        val ev = interp.toolResult("g3", "Available tasks:\nassemble", isError = false)
        assertTrue(ev.none { it is BuildReported })
    }

    @Test fun genericBashErrorStaysGenericError() {
        interp.toolUse("b9", "Bash", obj("""{"command":"ls /nope"}"""))
        val ev = interp.toolResult("b9", "ls: /nope: No such file or directory", isError = true)
        assertTrue(ev.none { it is BuildReported })
        assertTrue(ev.any { it is ErrorObserved })
    }
}
