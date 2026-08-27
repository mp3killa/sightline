package io.mp.sightline.ui.state

import io.mp.sightline.ui.state.StopPolicy.StopAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPolicyTest {

    private fun decide(running: Boolean = true, pending: Boolean = false, supported: Boolean = true) =
        StopPolicy.decide(running, pending, supported)

    @Test fun firstPressOnAModernCliInterrupts() {
        assertEquals(StopAction.INTERRUPT, decide())
    }

    @Test fun secondPressEscalatesToTheKill() {
        // The only escalation signal there is: the CLI never reports "still working on stopping".
        assertEquals(StopAction.FORCE, decide(pending = true))
    }

    @Test fun anOlderCliSkipsStraightToTheKill() {
        assertEquals(StopAction.FORCE, decide(supported = false))
    }

    @Test fun pressingStopWithNothingRunningDoesNothing() {
        assertEquals(StopAction.NONE, decide(running = false))
        assertEquals(StopAction.NONE, decide(running = false, pending = true))
        assertEquals(StopAction.NONE, decide(running = false, supported = false))
    }

    // ---- what the panel is allowed to claim ----

    @Test fun neitherPathClaimsToHaveStoppedARunningCommand() {
        // Verified against 2.1.235: a `sleep 40 && touch SENTINEL` completed forty seconds after an
        // acknowledged interrupt. Saying "stopped" to a developer mid-Gradle-build would be false.
        for (action in listOf(StopAction.INTERRUPT, StopAction.FORCE)) {
            val notice = StopPolicy.notice(action, commandInFlight = true)!!
            assertTrue(notice, notice.contains("keep") || notice.contains("keeps"))
            assertTrue(notice, notice.contains("running"))
        }
    }

    @Test fun theCommandCaveatIsOmittedWhenThereIsNoCommand() {
        // Stating it unconditionally sends the reader hunting for a process that was never started.
        for (action in listOf(StopAction.INTERRUPT, StopAction.FORCE)) {
            val notice = StopPolicy.notice(action, commandInFlight = false)!!
            assertFalse(notice, notice.contains("command"))
        }
    }

    @Test fun interruptSaysTheConversationSurvivesAndTheKillSaysItResumes() {
        assertTrue(StopPolicy.notice(StopAction.INTERRUPT, false)!!.contains("conversation is kept"))
        assertTrue(StopPolicy.notice(StopAction.FORCE, false)!!.contains("resumes the conversation"))
    }

    @Test fun aNoOpPressSaysNothing() {
        assertNull(StopPolicy.notice(StopAction.NONE, true))
        assertNull(StopPolicy.notice(StopAction.NONE, false))
    }

    @Test fun aLiveTaskCountsAsSomethingThatMayStillBeRunning() {
        // A subagent's own Bash calls are not individually trackable — the forwarded events carry the
        // subagent's tool ids, which are deliberately not correlated across the boundary. So a Task in
        // flight has to count, or Stop confidently reports nothing running while a subagent's Gradle
        // build carries on.
        assertTrue(StopPolicy.LINGERING_TOOLS.contains("Bash"))
        assertTrue(StopPolicy.LINGERING_TOOLS.contains("Task"))
        // Not everything: a Read or a Grep leaves nothing behind, and claiming otherwise would make the
        // caveat meaningless by attaching it to every stop.
        assertFalse(StopPolicy.LINGERING_TOOLS.contains("Read"))
        assertFalse(StopPolicy.LINGERING_TOOLS.contains("Grep"))
    }

    @Test fun theLabelDistinguishesTheTwoPaths() {
        assertEquals("Interrupting", StopPolicy.statusLabel(StopAction.INTERRUPT))
        assertEquals("Stopping", StopPolicy.statusLabel(StopAction.FORCE))
    }

    @Test fun theUnsupportedNoticeTellsYouWhatToDoAboutIt() {
        assertTrue(StopPolicy.UNSUPPORTED_NOTICE.contains("Updating the CLI"))
    }
}
