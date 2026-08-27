package io.mp.sightline.ui.state

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay and reply fixtures are the CLI's own, captured from the end-to-end 2.1.235 probe recorded
 * in docs/PROTOCOL.md §6 — the one that edited a file and restored it.
 */
class CheckpointPolicyTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    // ---- recognising the replay that carries the checkpoint id ----

    @Test fun readsTheTextOfAReplayedUserMessage() {
        assertEquals(
            "Add a docstring to add()",
            CheckpointPolicy.replayedText(
                obj("""{"type":"user","uuid":"581fc98e-08cd-41de-b976-1bb1bcce5cd0","message":{"role":"user","content":[{"type":"text","text":"Add a docstring to add()"}]}}"""),
            ),
        )
    }

    @Test fun aTurnsToolOutputIsNotAReplay() {
        // These arrive many times per turn and would otherwise consume every checkpoint slot.
        assertNull(
            CheckpointPolicy.replayedText(
                obj("""{"type":"user","uuid":"x","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"ok"}]}}"""),
            ),
        )
    }

    @Test fun anEventWithNoUuidCarriesNoCheckpoint() {
        assertNull(CheckpointPolicy.replayedText(obj("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}""")))
        assertNull(CheckpointPolicy.replayedText(obj("""{"type":"assistant","uuid":"x","message":{"content":[{"type":"text","text":"hi"}]}}""")))
        assertNull(CheckpointPolicy.replayedText(obj("""{"type":"user","uuid":"x"}""")))
    }

    @Test fun theInterruptMessageCannotClaimSomeoneElsesCheckpoint() {
        // After a Stop the CLI emits its own user message with a uuid. It parses as a replay, so the
        // guard has to be the text comparison, not the shape.
        val interrupt = obj("""{"type":"user","uuid":"deadbeef","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""")
        val text = CheckpointPolicy.replayedText(interrupt)!!
        assertFalse(CheckpointPolicy.isSameMessage("Add a docstring to add()", text))
    }

    @Test fun theClisOwnInterruptMarkerIsExcludedBeforeTheQueueIsTouched() {
        // It is shaped exactly like a replay — same type, same uuid, same text content — so nothing about
        // its *shape* excludes it. Letting it through would consume the checkpoint of a message the user
        // actually sent.
        assertTrue(CheckpointPolicy.isCliSynthetic("[Request interrupted by user]"))
        assertTrue(CheckpointPolicy.isCliSynthetic("  [Request interrupted by user]\n"))
        assertFalse(CheckpointPolicy.isCliSynthetic("run the tests"))
        assertFalse(CheckpointPolicy.isCliSynthetic(""))
    }

    @Test fun matchingIgnoresSurroundingWhitespaceOnly() {
        assertTrue(CheckpointPolicy.isSameMessage("  run the tests\n", "run the tests"))
        assertFalse(CheckpointPolicy.isSameMessage("run the tests", "run the test"))
    }

    // ---- when it is offered ----

    @Test fun nothingIsOfferedWithoutBothTheSettingAndACheckpoint() {
        assertTrue(CheckpointPolicy.offerable(enabled = true, checkpointId = "abc"))
        assertFalse(CheckpointPolicy.offerable(enabled = false, checkpointId = "abc"))
        assertFalse(CheckpointPolicy.offerable(enabled = true, checkpointId = null))
        assertFalse(CheckpointPolicy.offerable(enabled = true, checkpointId = "  "))
    }

    // ---- the limits, which must never be dropped ----

    @Test fun theConfirmationNamesTheMessageAndRepeatsTheLimits() {
        val text = CheckpointPolicy.confirmation("Refactor the authentication module")
        assertTrue(text, text.contains("Refactor the authentication module"))
        assertTrue(text, text.contains(CheckpointPolicy.LIMITS))
    }

    @Test fun theLimitsNameBothUntrackedSources() {
        // A user who thinks this undoes a Bash command's damage stops looking exactly when they should
        // start — so both exclusions are spelled out, not implied.
        assertTrue(CheckpointPolicy.LIMITS.contains("command"))
        assertTrue(CheckpointPolicy.LIMITS.contains("subagent"))
        assertTrue(CheckpointPolicy.LIMITS.contains("git"))
    }

    @Test fun everySuccessfulOutcomeStillCarriesTheLimits() {
        assertTrue(CheckpointPolicy.outcome(true, 0, null).text.contains(CheckpointPolicy.LIMITS))
        assertTrue(CheckpointPolicy.outcome(true, 2, null).text.contains(CheckpointPolicy.LIMITS))
    }

    @Test fun aLongMessageIsPreviewedNotPasted() {
        val long = "a".repeat(200)
        val text = CheckpointPolicy.confirmation(long)
        assertFalse(text, text.contains(long))
        assertTrue(text, text.contains("…"))
    }

    // ---- outcomes ----

    @Test fun theVerifiedSuccessReadsAsARestore() {
        val n = CheckpointPolicy.outcome(canRewind = true, skippedLinks = 0, error = null)
        assertFalse(n.isError)
        assertTrue(n.text, n.text.startsWith("Restored"))
    }

    @Test fun aRefusalSaysNothingWasRestoredAndWhy() {
        // The verified shape for a message with no checkpoint: a *success* reply carrying canRewind:false.
        val n = CheckpointPolicy.outcome(false, 0, "No file checkpoint found for this message.")
        assertTrue(n.isError)
        assertTrue(n.text, n.text.contains("Nothing was restored"))
        assertTrue(n.text, n.text.contains("No file checkpoint found"))
    }

    @Test fun aPartialRestoreIsReportedAsAProblemNotASuccess() {
        // The failure this whole class is written around: a partial restore that reads as a whole one.
        val n = CheckpointPolicy.outcome(canRewind = true, skippedLinks = 3, error = null)
        assertTrue(n.isError)
        assertTrue(n.text, n.text.contains("except 3"))
    }
}
