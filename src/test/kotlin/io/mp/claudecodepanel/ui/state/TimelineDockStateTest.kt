package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDockStateTest {

    @Test fun autoScrollOnlyWhenExpandedFollowingAndNearBottom() {
        val s = TimelineDockState(expanded = false, following = true)
        assertFalse(s.shouldAutoScroll(userNearBottom = true))
        s.expanded = true
        assertTrue(s.shouldAutoScroll(userNearBottom = true))
        assertFalse(s.shouldAutoScroll(userNearBottom = false))
        s.following = false
        assertFalse(s.shouldAutoScroll(userNearBottom = true))
    }

    @Test fun collapsedSummaryReadsNaturally() {
        val s = TimelineDockState()
        assertEquals("Activity log · 0 events", s.collapsedSummary(0, null))
        assertEquals("Activity log · 1 event", s.collapsedSummary(1, null))
        assertEquals("Activity log · 3 events · Latest: Edited X.kt", s.collapsedSummary(3, "Edited X.kt"))
    }
}
