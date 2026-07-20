package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEventPresentationTest {

    private fun weight(tool: String?, outcome: ToolOutcome) = ToolEventPresentation.weight(tool, outcome)

    @Test fun routineSuccessfulReadsAreQuiet() {
        for (tool in ToolEventPresentation.READ_ONLY_TOOLS) {
            assertEquals("$tool succeeded — should not shout", ToolWeight.COMPACT, weight(tool, ToolOutcome.OK))
            assertEquals("$tool running", ToolWeight.COMPACT, weight(tool, ToolOutcome.RUNNING))
        }
    }

    @Test fun aSuccessfulCommandIsAlsoQuiet() {
        assertEquals(ToolWeight.COMPACT, weight("Bash", ToolOutcome.OK))
        assertEquals(ToolWeight.COMPACT, weight("Bash", ToolOutcome.RUNNING))
    }

    /** A failure is the one thing a reader must not scroll past. */
    @Test fun anyFailureEarnsACard() {
        for (tool in listOf("Read", "Grep", "Bash", "Edit", null, "SomeUnknownTool")) {
            assertEquals("$tool failed", ToolWeight.CARD, weight(tool, ToolOutcome.ERROR))
        }
    }

    /** Denial is not an error, but the user made that decision and must be able to see it. */
    @Test fun deniedToolsStayVisible() {
        assertEquals(ToolWeight.CARD, weight("Bash", ToolOutcome.BLOCKED))
        assertEquals(ToolWeight.CARD, weight("Read", ToolOutcome.BLOCKED))
    }

    /** A successful edit is the point of the turn — the opposite of a successful read. */
    @Test fun successfulEditsKeepCardWeight() {
        for (tool in ToolEventPresentation.MUTATING_TOOLS) {
            assertEquals("$tool succeeded — still the point of the turn", ToolWeight.CARD, weight(tool, ToolOutcome.OK))
        }
    }

    @Test fun readAndMutateSetsDoNotOverlap() {
        val overlap = ToolEventPresentation.READ_ONLY_TOOLS intersect ToolEventPresentation.MUTATING_TOOLS
        assertTrue("a tool cannot be both read-only and mutating: $overlap", overlap.isEmpty())
    }

    @Test fun isCompactMirrorsWeight() {
        assertTrue(ToolEventPresentation.isCompact("Read", ToolOutcome.OK))
        assertFalse(ToolEventPresentation.isCompact("Read", ToolOutcome.ERROR))
        assertFalse(ToolEventPresentation.isCompact("Edit", ToolOutcome.OK))
    }

    @Test fun disclosureOnlyWhenThereIsABody() {
        assertFalse(ToolEventPresentation.hasDisclosure(0))
        assertTrue(ToolEventPresentation.hasDisclosure(1))
    }
}
