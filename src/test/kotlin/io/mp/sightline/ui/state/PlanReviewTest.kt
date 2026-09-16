package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanReviewTest {

    @Test fun `a plan needs text to be a plan`() {
        assertNull(PlanReview.of(null, "/tmp/p.md"))
        assertNull("an empty plan must fall back to the ordinary approval card", PlanReview.of("   ", null))
        val plan = PlanReview.of("# Steps\n1. Do it", "/tmp/p.md")!!
        assertEquals("# Steps\n1. Do it", plan.markdown)
        assertEquals("/tmp/p.md", plan.filePath)
    }

    @Test fun `a blank file path is no file path`() {
        assertNull(PlanReview.of("# Steps", "  ")!!.filePath)
    }

    /**
     * Denying ExitPlanMode with nothing actionable made the model re-propose the same plan three times
     * in a row (observed against 2.1.235). Both rejection messages therefore say the plan was not
     * approved *and* what to do now.
     */
    @Test fun `a revision states the outcome and carries the replacement`() {
        val msg = PlanReview.revisedMessage("  1. Do the other thing  ")
        assertTrue(msg, msg.startsWith("The plan was not approved."))
        assertTrue(msg, msg.contains("1. Do the other thing"))
        assertTrue(msg, msg.contains("follow it as written"))
    }

    @Test fun `keep-planning says so, with or without a reason`() {
        val bare = PlanReview.keepPlanningMessage(null)
        assertTrue(bare, bare.contains("Stay in plan mode"))
        val withReason = PlanReview.keepPlanningMessage("  covers the migration but not the rollback ")
        assertTrue(withReason, withReason.endsWith("covers the migration but not the rollback"))
        assertTrue(withReason, withReason.startsWith("The plan was not approved."))
        // A blank reason must not produce a dangling colon.
        assertEquals(bare, PlanReview.keepPlanningMessage("   "))
    }

    @Test fun `an unchanged edit is not a change`() {
        assertFalse(PlanReview.isChanged("# Plan\n1. a", "  # Plan\n1. a  "))
        assertTrue(PlanReview.isChanged("# Plan\n1. a", "# Plan\n1. b"))
    }
}
