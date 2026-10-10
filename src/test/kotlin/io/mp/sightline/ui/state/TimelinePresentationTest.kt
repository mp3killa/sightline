package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class TimelinePresentationTest {

    @Test fun everyOutcomeHasItsOwnMarker() {
        val markers = ToolOutcome.values().map { TimelinePresentation.markerFor(it) }
        assertEquals(markers.size, markers.toSet().size)
        assertEquals(TimelineMarker.ERROR, TimelinePresentation.markerFor(ToolOutcome.ERROR))
        // A denied step must never share the success dot.
        assertNotEquals(TimelineMarker.SUCCESS, TimelinePresentation.markerFor(ToolOutcome.BLOCKED))
    }

    @Test fun builtInToolsKeepTheirName() {
        assertEquals("Bash", TimelinePresentation.toolTitle("Bash"))
        assertEquals("MultiEdit", TimelinePresentation.toolTitle("MultiEdit"))
    }

    @Test fun mcpToolsDropTheServerPrefix() {
        assertEquals("GetDiagnostics", TimelinePresentation.toolTitle("mcp__ide__getDiagnostics"))
        assertEquals("Capture logcat", TimelinePresentation.toolTitle("mcp__ide__capture_logcat"))
    }

    @Test fun readRangeStatesOnlyWhatWasRequested() {
        assertNull(TimelinePresentation.readRange(null, null))
        assertNull(TimelinePresentation.readRange(0, 50))
        assertEquals("(from line 97)", TimelinePresentation.readRange(97, null))
        assertEquals("(lines 10–39)", TimelinePresentation.readRange(10, 30))
    }

    @Test fun thoughtLabelStreamsThenStatesDuration() {
        assertEquals("Thinking…", TimelinePresentation.thoughtLabel(null, streaming = true))
        assertEquals("Thought for 1s", TimelinePresentation.thoughtLabel(120, streaming = false))
        assertEquals("Thought for 2s", TimelinePresentation.thoughtLabel(2_400, streaming = false))
        assertEquals("Thought for 1m 5s", TimelinePresentation.thoughtLabel(65_000, streaming = false))
        assertEquals("Thought", TimelinePresentation.thoughtLabel(null, streaming = false))
    }

    @Test fun shortOutputIsShownWhole() {
        val p = TimelinePresentation.outputPreview("a\nb\n")
        assertEquals("a\nb", p.shown)
        assertFalse(p.truncated)
    }

    @Test fun longOutputIsCutAndTheRestCounted() {
        val p = TimelinePresentation.outputPreview((1..10).joinToString("\n") { "line $it" })
        assertEquals("line 1\nline 2\nline 3", p.shown)
        assertEquals(7, p.hiddenLines)
        assertEquals("Show 7 more lines", TimelinePresentation.moreLinesText(p.hiddenLines))
        assertEquals("Show 1 more line", TimelinePresentation.moreLinesText(1))
    }

    @Test fun timestampFollowsLocale() {
        val t = LocalTime.of(20, 27)
        assertTrue(TimelinePresentation.timestamp(t, Locale.US).startsWith("8:27"))
        assertEquals("20:27", TimelinePresentation.timestamp(t, Locale.GERMANY))
    }
}

class WorkingVerbsTest {

    @Test fun wordIsStableWithinARotationWindow() {
        assertEquals(WorkingVerbs.verbAt(3, 0), WorkingVerbs.verbAt(3, WorkingVerbs.ROTATE_MILLIS - 1L))
    }

    @Test fun wordRotatesAfterTheWindow() {
        assertNotEquals(WorkingVerbs.verbAt(3, 0), WorkingVerbs.verbAt(3, WorkingVerbs.ROTATE_MILLIS.toLong()))
    }

    @Test fun anySeedIsSafe() {
        assertTrue(WorkingVerbs.verbAt(Int.MIN_VALUE, 0).isNotBlank())
        assertTrue(WorkingVerbs.verbAt(-7, -100).isNotBlank())
    }

    @Test fun elapsedAppearsOnceInformative() {
        assertFalse(WorkingVerbs.line(0, 1_000).contains("·"))
        assertTrue(WorkingVerbs.line(0, 12_000).endsWith("· 12s"))
        assertTrue(WorkingVerbs.line(0, 125_000).endsWith("· 2m 5s"))
    }
}

class StickyPromptTest {

    @Test fun nothingIsPinnedBeforeAnyPromptScrollsAway() {
        assertNull(StickyPrompt.select(listOf(40, 900), viewTop = 0))
        assertNull(StickyPrompt.select(listOf(40), viewTop = 39))
    }

    @Test fun theLatestPromptAboveTheViewIsPinned() {
        assertEquals(0, StickyPrompt.select(listOf(40, 900), viewTop = 300))
        assertEquals(1, StickyPrompt.select(listOf(40, 900), viewTop = 950))
    }

    @Test fun noPromptsMeansNoBar() {
        assertNull(StickyPrompt.select(emptyList(), viewTop = 500))
    }
}
