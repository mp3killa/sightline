package io.mp.sightline.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class InteractionCoordinatorsTest {

    private fun approval(id: String, canAllowAlways: Boolean, sink: (ApprovalDecision) -> Unit) =
        PendingApproval(id, "toolu_$id", "Edit", "Allow Edit?", "src/Foo.kt", canAllowAlways) { d, _ -> sink(d) }

    private fun approvalWithReason(id: String, sink: (ApprovalDecision, String?) -> Unit) =
        PendingApproval(id, "toolu_$id", "Bash", "Allow Bash?", null, false, sink)

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

    /**
     * A denial's reason reaches the handler, which is what puts it on the wire — the CLI hands a deny
     * `message` to the model as the tool's result (verified against 2.1.235), so this is the difference
     * between blocking an action and redirecting it.
     */
    @Test fun denyCarriesItsReasonToTheHandler() {
        val c = ApprovalCoordinator()
        var got: Pair<ApprovalDecision, String?>? = null
        c.register(approvalWithReason("r9") { d, reason -> got = d to reason })

        c.respond("r9", ApprovalDecision.DENY, "run the tests instead")
        assertEquals(ApprovalDecision.DENY to "run the tests instead", got)
    }

    /** Blank is not a reason. Sending "" would tell the model nothing while looking deliberate. */
    @Test fun aBlankReasonIsNoReason() {
        val c = ApprovalCoordinator()
        var got: String? = "unset"
        c.register(approvalWithReason("r10") { _, reason -> got = reason })

        c.respond("r10", ApprovalDecision.DENY, "   ")
        assertNull(got)
    }
}
