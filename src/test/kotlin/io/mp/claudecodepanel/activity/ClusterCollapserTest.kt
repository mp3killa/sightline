package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClusterCollapserTest {

    private var t = Instant.parse("2026-07-19T10:00:00Z")
    private fun tick(): Instant { t = t.plusSeconds(1); return t }

    private fun node(
        id: String,
        type: ActivityNodeType,
        category: ActivityCategory = ActivityCategory.SHELL,
        state: ActivityNodeState = ActivityNodeState.COMPLETED,
        pinned: Boolean = false,
    ): ActivityNode {
        val at = tick()
        return ActivityNode(id, type, id, category = category, state = state, pinned = pinned, firstSeenAt = at, lastSeenAt = at)
    }

    private fun commands(n: Int, state: ActivityNodeState = ActivityNodeState.COMPLETED) =
        (0 until n).map { node("cmd:$it", ActivityNodeType.COMMAND, state = state) }

    @Test fun foldsHistoricalCommandsIntoOneAggregate() {
        val plan = ClusterCollapser.plan(commands(6), minGroup = 4)
        assertEquals(1, plan.aggregates.size)
        val agg = plan.aggregates.single()
        assertEquals(6, agg.count)
        assertEquals("6 commands", agg.label)
        assertEquals(6, plan.hiddenIds.size)
        assertTrue(plan.hiddenIds.containsAll(agg.memberIds))
        assertEquals(agg, plan.aggregateFor("cmd:3"))
    }

    @Test fun leavesSmallGroupsAlone() {
        val plan = ClusterCollapser.plan(commands(3), minGroup = 4)
        assertTrue(plan.isEmpty)
        assertEquals(ClusterCollapser.Plan.EMPTY, plan)
    }

    @Test fun neverFoldsActiveFailedPinnedOrKeptNodes() {
        val nodes = commands(3) +
            node("cmd:active", ActivityNodeType.COMMAND, state = ActivityNodeState.ANALYSING) +
            node("cmd:failed", ActivityNodeType.COMMAND, state = ActivityNodeState.FAILED) +
            node("cmd:pinned", ActivityNodeType.COMMAND, pinned = true) +
            node("cmd:focused", ActivityNodeType.COMMAND)
        val plan = ClusterCollapser.plan(nodes, keepIds = setOf("cmd:focused"), minGroup = 4)
        // Only the 3 plain completed commands are foldable — below the threshold, so nothing folds.
        assertTrue(plan.isEmpty)
        assertFalse("cmd:active" in plan.hiddenIds)
        assertFalse("cmd:failed" in plan.hiddenIds)
        assertFalse("cmd:pinned" in plan.hiddenIds)
        assertFalse("cmd:focused" in plan.hiddenIds)
    }

    @Test fun separatesByCategoryAndType() {
        val nodes = commands(4) +
            (0 until 4).map { node("test:$it", ActivityNodeType.TEST, category = ActivityCategory.TESTING, state = ActivityNodeState.PASSED) } +
            (0 until 4).map { node("gradle:$it", ActivityNodeType.GRADLE_TASK, category = ActivityCategory.GRADLE_BUILD) }
        val plan = ClusterCollapser.plan(nodes, minGroup = 4)
        assertEquals(3, plan.aggregates.size)
        assertTrue(plan.aggregates.any { it.type == ActivityNodeType.COMMAND && it.label == "4 commands" })
        assertTrue(plan.aggregates.any { it.type == ActivityNodeType.TEST && it.label == "4 test runs" })
        assertTrue(plan.aggregates.any { it.type == ActivityNodeType.GRADLE_TASK && it.label == "4 Gradle tasks" })
    }

    @Test fun expandedAggregateKeepsItsMembersVisible() {
        val aggId = ClusterCollapser.aggregateId(ActivityCategory.SHELL, ActivityNodeType.COMMAND)
        val plan = ClusterCollapser.plan(commands(6), minGroup = 4, expanded = setOf(aggId))
        assertTrue(plan.isEmpty)
    }

    @Test fun doesNotFoldNonRepetitiveTypes() {
        // Files are never aggregated even when many pile up — only command/test/gradle history is.
        val files = (0 until 10).map { node("file:$it", ActivityNodeType.FILE, category = ActivityCategory.UI_COMPOSE, state = ActivityNodeState.COMPLETED) }
        assertTrue(ClusterCollapser.plan(files, minGroup = 4).isEmpty)
    }
}
