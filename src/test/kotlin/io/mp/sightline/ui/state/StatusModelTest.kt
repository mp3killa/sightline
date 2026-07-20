package io.mp.sightline.ui.state

import io.mp.sightline.activity.ErrorObserved
import io.mp.sightline.activity.FileEdited
import io.mp.sightline.activity.FileRead
import io.mp.sightline.activity.StatusUpdated
import io.mp.sightline.activity.TaskCompleted
import io.mp.sightline.activity.TestReported
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

    @Test fun completedSettlesToConciseLabelEvenOverActiveTool() {
        val m = model()
        m.taskStarted()
        m.apply(FileEdited("/a/X.kt", at = t))
        m.apply(TaskCompleted("Done", isError = false, at = t))
        assertEquals(StatusKind.SUCCESS, m.view.kind)
        assertEquals("Completed", m.view.primary)
    }

    @Test fun completionNeverPutsResponseProseInStatus() {
        // The TaskCompleted summary is the full final assistant text; it must never reach the strip.
        val m = model()
        m.taskStarted()
        val longResponse = "Here is a long assistant answer that explains the whole thing in detail. ".repeat(5)
        m.apply(TaskCompleted(longResponse, isError = false, at = t))
        assertEquals("Completed", m.view.primary)
        assertEquals(StatusKind.SUCCESS, m.view.kind)
    }

    @Test fun erroredCompletionShowsStoppedNotProse() {
        val m = model()
        m.taskStarted()
        m.apply(TaskCompleted("A long explanation of what went wrong and why it stopped.", isError = true, at = t))
        assertEquals("Stopped", m.view.primary)
        assertEquals(StatusKind.WARNING, m.view.kind)
    }

    @Test fun resetReturnsToReady() {
        val m = model()
        m.taskStarted()
        m.reset()
        assertEquals(StatusKind.READY, m.view.kind)
    }
}
