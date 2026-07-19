package io.mp.claudecodepanel.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
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

    @Test fun grepBecomesSearch() {
        val e = interp.toolUse("g", "Grep", obj("""{"pattern":"ViewModel","path":"app/src"}"""))[0] as FileSearched
        assertEquals("ViewModel", e.query)
        assertEquals("app/src", e.path)
    }

    @Test fun gradleTestCommandEmitsTaskAndTestStart() {
        val ev = interp.toolUse("b1", "Bash", obj("""{"command":"./gradlew :app:test"}"""))
        assertTrue(ev.any { it is GradleTaskRun })
        assertTrue(ev.any { it is TestStarted })
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
}
