package io.mp.claudecodepanel.ui.state

import io.mp.claudecodepanel.activity.ErrorObserved
import io.mp.claudecodepanel.activity.FileEdited
import io.mp.claudecodepanel.activity.FileRead
import io.mp.claudecodepanel.activity.StatusUpdated
import io.mp.claudecodepanel.activity.TaskCompleted
import io.mp.claudecodepanel.activity.TestReported
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StatusModelTest {

    private val t = Instant.parse("2026-07-19T10:00:00Z")
    private fun model() = StatusModel { t }

    @Test fun startsReady() {
        assertEquals(StatusKind.READY, model().view.kind)
    }

    @Test fun taskStartedShowsWorkingFallback() {
        val m = model()
        val v = m.taskStarted()
        assertEquals(StatusKind.WORKING, v.kind)
        assertEquals(StatusModel.WORKING_FALLBACK, v.primary)
    }

    @Test fun toolActivityDescribesTheFile() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/app/src/ClaudePanel.kt", at = t))
        assertEquals(StatusKind.EDITING, m.view.kind)
        assertEquals("Editing ClaudePanel.kt", m.view.primary)
    }

    @Test fun lateThinkingDoesNotOverwriteActiveFileContext() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/app/src/ClaudePanel.kt", at = t))
        m.apply(StatusUpdated("Thinking", null, t))
        assertEquals("Editing ClaudePanel.kt", m.view.primary)
        assertEquals(StatusKind.EDITING, m.view.kind)
    }

    @Test fun contextualStreamStatusShowsWhenNoToolYet() {
        val m = model()
        m.taskStarted()
        m.apply(StatusUpdated("Planning", "3 steps", t))
        assertEquals("Planning", m.view.primary)
        assertEquals("3 steps", m.view.secondary)
    }

    @Test fun errorOutranksToolActivityAndPersistsOverLaterTool() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/a/X.kt", at = t))
        m.apply(ErrorObserved(null, "boom", t))
        assertEquals(StatusKind.ERROR, m.view.kind)
        m.apply(FileRead("/a/Y.kt", at = t))
        assertEquals(StatusKind.ERROR, m.view.kind)
    }

    @Test fun newerOutcomeReplacesOlderOutcome() {
        val m = model()
        m.taskStarted()
        m.apply(ErrorObserved(null, "boom", t))
        m.apply(TestReported(passed = 3, failed = 0, failedNames = emptyList(), at = t))
        assertEquals(StatusKind.SUCCESS, m.view.kind)
        assertEquals("3 tests passed", m.view.primary)
    }

    @Test fun permissionIsHighestAndResolvingRestoresPriorContext() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/a/X.kt", at = t))
        m.permissionRequested()
        assertEquals(StatusKind.PERMISSION, m.view.kind)
        m.permissionResolved()
        assertEquals(StatusKind.EDITING, m.view.kind)
        assertEquals("Editing X.kt", m.view.primary)
    }

    @Test fun completedSettlesToSummaryEvenOverActiveTool() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/a/X.kt", at = t))
        m.apply(TaskCompleted("Done", isError = false, at = t))
        assertEquals(StatusKind.SUCCESS, m.view.kind)
        assertEquals("Done", m.view.primary)
    }

    @Test fun resetReturnsToReady() {
        val m = model()
        m.taskStarted()
        m.reset()
        assertEquals(StatusKind.READY, m.view.kind)
    }
}
