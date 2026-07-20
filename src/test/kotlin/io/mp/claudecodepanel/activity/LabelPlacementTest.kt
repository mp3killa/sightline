package io.mp.claudecodepanel.activity

import io.mp.claudecodepanel.activity.LabelPlacement.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacementTest {

    private fun box(id: String, x: Int, y: Int, w: Int = 100, h: Int = 14, priority: Int = 0) =
        Candidate(id, x, y, w, h, priority)

    /** The ids whose text actually gets drawn — the placement map's keys. */
    private fun kept(candidates: List<Candidate>, padding: Int = LabelPlacement.PADDING): Set<String> =
        LabelPlacement.place(candidates, padding).keys

    @Test fun wellSeparatedLabelsAllSurvive() {
        val kept = kept(listOf(box("a", 0, 0), box("b", 0, 100), box("c", 0, 200)))
        assertEquals(setOf("a", "b", "c"), kept)
    }

    @Test fun aSingleLabelIsAlwaysDrawn() {
        assertEquals(setOf("only"), kept(listOf(box("only", 0, 0))))
        assertTrue(kept(emptyList()).isEmpty())
    }

    @Test fun overlappingLabelsDropTheLowerPriorityOne() {
        // This is the reported defect: two nodes on nearly the same line, labels overprinting.
        val kept = kept(
            listOf(
                box("ordinary", 40, 100, priority = 0),
                box("error", 0, 100, priority = 200),
            ),
        )
        assertEquals(setOf("error"), kept)
    }

    @Test fun attentionAlwaysWinsACollision() {
        val kept = kept(
            listOf(
                box("anchor", 0, 100, priority = MapDensity.labelPriority(false, ActivityNodeType.CATEGORY)),
                box("hovered", 30, 100, priority = MapDensity.labelPriority(true, ActivityNodeType.COMMAND)),
            ),
        )
        assertEquals("the node the user is pointing at keeps its label", setOf("hovered"), kept)
    }

    @Test fun horizontallyAdjacentLabelsCollideEvenWhenNodesAreFarApart() {
        // A label is far wider than its node, which is exactly why the force layout alone can't prevent this.
        val kept = kept(listOf(box("a", 0, 50, w = 120), box("b", 100, 50, w = 120)))
        assertEquals(1, kept.size)
    }

    @Test fun verticallyStackedLabelsNeedRealSeparation() {
        val touching = kept(listOf(box("a", 0, 0, h = 14), box("b", 0, 14, h = 14)))
        assertEquals("boxes that merely abut still read as one block", 1, touching.size)

        val spaced = kept(listOf(box("a", 0, 0, h = 14), box("b", 0, 40, h = 14)))
        assertEquals(2, spaced.size)
    }

    @Test fun droppingIsTransitiveNotCascading() {
        // Three overlapping labels: the winner survives and both losers drop — the winner is not itself
        // dropped for overlapping the ones it beat.
        val kept = kept(
            listOf(box("low1", 10, 0, priority = 0), box("top", 0, 0, priority = 300), box("low2", 20, 0, priority = 0)),
        )
        assertEquals(setOf("top"), kept)
    }

    @Test fun aLoserCanStillWinAgainstSomethingItDoesNotTouch() {
        // "far" overlaps nobody, so losing elsewhere must not remove it.
        val kept = kept(
            listOf(box("winner", 0, 0, priority = 200), box("loser", 10, 0, priority = 0), box("far", 0, 500)),
        )
        assertEquals(setOf("winner", "far"), kept)
    }

    @Test fun outcomeIsDeterministicForEqualPriorities() {
        val a = box("aaa", 0, 0, priority = 100)
        val b = box("bbb", 10, 0, priority = 100)
        val one = kept(listOf(a, b))
        val two = kept(listOf(b, a)) // input order must not matter
        assertEquals(one, two)
        assertEquals("ties break by id, so the survivor is stable frame to frame", setOf("aaa"), one)
    }

    @Test fun priorityOrderMirrorsTheDensityTiers() {
        val attention = MapDensity.labelPriority(true, ActivityNodeType.COMMAND)
        val anchor = MapDensity.labelPriority(false, ActivityNodeType.ERROR)
        val patch = MapDensity.labelPriority(false, ActivityNodeType.PATCH)
        val ordinary = MapDensity.labelPriority(false, ActivityNodeType.COMMAND)
        assertTrue(attention > anchor)
        assertTrue(anchor > patch)
        assertTrue(patch > ordinary)
    }

    @Test fun labelBiasSpreadsHorizontallyNotVertically() {
        assertTrue("labels hang off the right, so x needs the extra room", MapDensity.LABEL_X_BIAS > 1.0)
    }

    @Test fun everyLabelCanBeDroppedExceptOneInAPileUp() {
        // 20 labels stacked on the same spot: exactly one survives, and it is the highest priority.
        val pile = (1..20).map { box("n$it", 0, 0, priority = it) }
        val kept = kept(pile)
        assertEquals(setOf("n20"), kept)
    }

    @Test fun paddingIsRespected() {
        // 4px apart: overlapping with the default 3px padding, fine with no padding.
        val boxes = listOf(box("a", 0, 0, w = 10, h = 10), box("b", 14, 0, w = 10, h = 10))
        assertEquals(1, kept(boxes).size)
        assertEquals(2, kept(boxes, padding = 0).size)
    }

    // ---- the other side of the node ----

    @Test fun aClashingLabelFlipsToTheOtherSideRatherThanVanishing() {
        // "loser" would overprint "winner" on its preferred side, but its left-hand slot is clear — so it
        // keeps its text, just on the other side of its node.
        val winner = box("winner", 0, 0, w = 100, priority = 200)
        val loser = Candidate("loser", x = 50, y = 0, width = 100, height = 14, priority = 0, alternateX = -400)
        val placed = LabelPlacement.place(listOf(winner, loser))
        assertEquals("both keep their text", setOf("winner", "loser"), placed.keys)
        assertEquals("loser moved to its fallback slot", -400, placed["loser"])
        assertEquals("winner kept its preferred slot", 0, placed["winner"])
    }

    @Test fun theFallbackIsOnlyUsedWhenThePreferredSlotIsTaken() {
        val a = Candidate("a", x = 0, y = 0, width = 100, height = 14, priority = 0, alternateX = -400)
        assertEquals("preferred slot is free, so keep it", mapOf("a" to 0), LabelPlacement.place(listOf(a)))
    }

    @Test fun aLabelIsWithheldOnlyWhenBothSlotsAreTaken() {
        val blockRight = box("blockRight", 0, 0, w = 100, priority = 300)
        val blockLeft = box("blockLeft", -400, 0, w = 100, priority = 300)
        val squeezed = Candidate("squeezed", x = 50, y = 0, width = 100, height = 14, priority = 0, alternateX = -400)
        val placed = LabelPlacement.place(listOf(blockRight, blockLeft, squeezed))
        assertFalse("nowhere left to put it", "squeezed" in placed.keys)
        assertEquals(setOf("blockRight", "blockLeft"), placed.keys)
    }

    @Test fun aFlippedLabelStillBlocksLaterOnes() {
        // Once a label takes its fallback slot, that space is genuinely occupied.
        val winner = box("aaa", 0, 0, w = 100, priority = 300)
        val flipped = Candidate("bbb", x = 50, y = 0, width = 100, height = 14, priority = 200, alternateX = -400)
        val wantsSameSpot = box("ccc", -400, 0, w = 100, priority = 100)
        val placed = LabelPlacement.place(listOf(winner, flipped, wantsSameSpot))
        assertEquals(-400, placed["bbb"])
        assertFalse("ccc cannot share the slot bbb flipped into", "ccc" in placed.keys)
    }

    @Test fun nodesAreNeverRemovedOnlyTheirText() {
        // The contract that matters: place() returns ids to *draw text for*, and is always a subset of
        // what it was given — it can never invent or remove a node.
        val given = listOf(box("a", 0, 0), box("b", 5, 0), box("c", 500, 500))
        val kept = kept(given)
        assertTrue(kept.all { id -> given.any { it.id == id } })
        assertFalse("a genuine collision was resolved", kept.size == given.size)
    }
}
