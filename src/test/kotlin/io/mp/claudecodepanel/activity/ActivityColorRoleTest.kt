package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant

class ActivityColorRoleTest {

    @Test fun everyStateMapsToARole() {
        for (state in ActivityNodeState.values()) {
            assertNotNull("no role for $state", ActivityColorRoles.roleForState(state))
        }
    }

    @Test fun keyStatesMapAsExpected() {
        assertEquals(ActivityColorRole.IDLE, ActivityColorRoles.roleForState(ActivityNodeState.IDLE))
        assertEquals(ActivityColorRole.EDITING, ActivityColorRoles.roleForState(ActivityNodeState.EDITING))
        assertEquals(ActivityColorRole.SUCCESS, ActivityColorRoles.roleForState(ActivityNodeState.PASSED))
        assertEquals(ActivityColorRole.ERROR, ActivityColorRoles.roleForState(ActivityNodeState.FAILED))
        assertEquals(ActivityColorRole.DISCOVERED, ActivityColorRoles.roleForState(ActivityNodeState.SEARCHING))
        assertEquals(ActivityColorRole.READING, ActivityColorRoles.roleForState(ActivityNodeState.READING))
    }

    @Test fun nodeTypeOverridesForTaskAndPatch() {
        val now = Instant.now()
        val task = ActivityNode("t", ActivityNodeType.TASK, "task", state = ActivityNodeState.ANALYSING, firstSeenAt = now, lastSeenAt = now)
        val patch = ActivityNode("p", ActivityNodeType.PATCH, "patch", state = ActivityNodeState.CREATED, firstSeenAt = now, lastSeenAt = now)
        assertEquals(ActivityColorRole.TASK, ActivityColorRoles.roleForNode(task))
        assertEquals(ActivityColorRole.SUGGESTION, ActivityColorRoles.roleForNode(patch))
    }
}
