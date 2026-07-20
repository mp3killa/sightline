package io.mp.sightline.ide

import io.mp.sightline.interaction.UserQuestionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-shot dispatch the UI and the sandbox test bridge share for AskUserQuestion resolution. */
class QuestionCoordinatorTest {

    private fun pending(id: String, sink: (QuestionResolution) -> Unit) =
        PendingQuestion(id, null, UserQuestionRequest(emptyList()), "{}", sink)

    @Test fun resolvesAnsweredExactlyOnce() {
        val c = QuestionCoordinator()
        var count = 0
        var last: QuestionResolution? = null
        c.register(pending("r1") { count++; last = it })
        assertTrue(c.hasPending())
        assertTrue(c.respond("r1", QuestionResolution.Answered("{}")))
        assertEquals(1, count)
        assertTrue(last is QuestionResolution.Answered)
        assertFalse("second respond is a no-op", c.respond("r1", QuestionResolution.Cancelled))
        assertEquals(1, count)
        assertFalse(c.hasPending())
    }

    @Test fun cancelResolves() {
        val c = QuestionCoordinator()
        var res: QuestionResolution? = null
        c.register(pending("r2") { res = it })
        assertTrue(c.respond("r2", QuestionResolution.Cancelled))
        assertEquals(QuestionResolution.Cancelled, res)
    }

    @Test fun respondUnknownIdReturnsFalse() {
        assertFalse(QuestionCoordinator().respond("nope", QuestionResolution.Cancelled))
    }

    @Test fun clearDropsPendingWithoutInvokingHandler() {
        val c = QuestionCoordinator()
        var count = 0
        c.register(pending("r3") { count++ })
        c.clear()
        assertFalse(c.hasPending())
        assertEquals(0, count)
    }

    @Test fun listenersFireOnRegisterAndResolve() {
        val c = QuestionCoordinator()
        var notifications = 0
        c.addListener { notifications++ }
        c.register(pending("r4") {})
        c.respond("r4", QuestionResolution.Answered("{}"))
        assertEquals(2, notifications)
    }
}
