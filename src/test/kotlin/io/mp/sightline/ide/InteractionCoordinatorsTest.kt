package io.mp.sightline.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class InteractionCoordinatorsTest {

    private fun approval(id: String, canAllowAlways: Boolean, sink: (ApprovalDecision) -> Unit) =
        PendingApproval(id, "toolu_$id", "Edit", "Allow Edit?", "src/Foo.kt", canAllowAlways, sink)

    @Test fun approvalAllowDenyRunHandlerOnce() {
        val c = ApprovalCoordinator()
        val decisions = mutableListOf<ApprovalDecision>()
        c.register(approval("r1", canAllowAlways = true) { decisions.add(it) })
        assertTrue(c.hasPending())

        assertEquals(ApprovalOutcome.Applied, c.respond("r1", ApprovalDecision.DENY))
        assertEquals(listOf(ApprovalDecision.DENY), decisions)
        assertFalse(c.hasPending())
        // A second response is a no-op: the handler must not run twice.
        assertTrue(c.respond("r1", ApprovalDecision.ALLOW) is ApprovalOutcome.NotFound)
        assertEquals(1, decisions.size)
    }

    @Test fun allowAlwaysRequiresSuggestionsAndStaysPendingOtherwise() {
        val c = ApprovalCoordinator()
        var ran: ApprovalDecision? = null
        c.register(approval("r2", canAllowAlways = false) { ran = it })
        val outcome = c.respond("r2", ApprovalDecision.ALLOW_ALWAYS)
        assertTrue(outcome is ApprovalOutcome.Unsupported)
        assertNull(ran)             // handler not run
        assertTrue(c.hasPending())  // still resolvable another way
        assertEquals(ApprovalOutcome.Applied, c.respond("r2", ApprovalDecision.ALLOW))
        assertEquals(ApprovalDecision.ALLOW, ran)
    }

    @Test fun unknownApprovalIdIsNotFound() {
        val c = ApprovalCoordinator()
        assertEquals(ApprovalOutcome.NotFound("nope"), c.respond("nope", ApprovalDecision.ALLOW))
    }

    @Test fun listenerFiresOnRegisterAndResolve() {
        val c = ApprovalCoordinator()
        var ticks = 0
        c.addListener { ticks++ }
        c.register(approval("r3", true) {})
        c.respond("r3", ApprovalDecision.ALLOW)
        assertEquals(2, ticks)
    }

    @Test fun diffAcceptCompletesFuture() {
        val c = DiffReviewCoordinator()
        val review = c.create("d1", "src/Foo.kt", "old", "new")
        assertFalse(review.future.isDone)
        assertTrue(c.respond("d1", DiffDecision.ACCEPT))
        assertEquals(DiffDecision.ACCEPT, review.future.get(1, TimeUnit.SECONDS))
        assertFalse(c.hasPending())
    }

    @Test fun diffRejectCompletesFutureAndUnknownIsFalse() {
        val c = DiffReviewCoordinator()
        val review = c.create("d2", "src/Foo.kt", "old", "new")
        assertTrue(c.respond("d2", DiffDecision.REJECT))
        assertEquals(DiffDecision.REJECT, review.future.get(1, TimeUnit.SECONDS))
        assertFalse(c.respond("d2", DiffDecision.ACCEPT)) // already resolved
        assertFalse(c.respond("missing", DiffDecision.ACCEPT))
    }

    @Test fun clearRejectsOutstandingDiffs() {
        val c = DiffReviewCoordinator()
        val review = c.create("d3", "src/Foo.kt", "old", "new")
        c.clear()
        assertEquals(DiffDecision.REJECT, review.future.get(1, TimeUnit.SECONDS))
    }
}
