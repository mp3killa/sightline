package io.mp.claudecodepanel.activity

import io.mp.claudecodepanel.activity.LabelPlacement.Candidate
import io.mp.claudecodepanel.activity.LabelPlacement.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacementTest {

    /** A label with exactly one acceptable position — the collision algorithm with no escape routes. */
    private fun box(id: String, x: Int, y: Int, w: Int = 100, h: Int = 14, priority: Int = 0) =
        Candidate(id, w, h, priority, listOf(Slot(x, y)))

    /** A label that will accept [x] or, failing that, [alt] — the old two-sided arrangement. */
    private fun twoSided(id: String, x: Int, alt: Int, y: Int, w: Int = 100, h: Int = 14, priority: Int = 0) =
        Candidate(id, w, h, priority, listOf(Slot(x, y), Slot(alt, y)))

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
        // 20 labels stacked on the same spot with nowhere else to go: exactly one survives, the highest.
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

    // ---- falling back to another slot ----

    @Test fun aClashingLabelMovesRatherThanVanishing() {
        // "loser" would overprint "winner" in its first slot, but its second is clear — so it keeps its
        // text, just somewhere else.
        val winner = box("winner", 0, 0, w = 100, priority = 200)
        val loser = twoSided("loser", x = 50, alt = -400, y = 0)
        val placed = LabelPlacement.place(listOf(winner, loser))
        assertEquals("both keep their text", setOf("winner", "loser"), placed.keys)
        assertEquals("loser moved to its fallback slot", Slot(-400, 0), placed["loser"])
        assertEquals("winner kept its preferred slot", Slot(0, 0), placed["winner"])
    }

    @Test fun theFallbackIsOnlyUsedWhenThePreferredSlotIsTaken() {
        val a = twoSided("a", x = 0, alt = -400, y = 0)
        assertEquals("preferred slot is free, so keep it", mapOf("a" to Slot(0, 0)), LabelPlacement.place(listOf(a)))
    }

    @Test fun aLabelIsWithheldOnlyWhenEverySlotIsTaken() {
        val blockRight = box("blockRight", 0, 0, w = 100, priority = 300)
        val blockLeft = box("blockLeft", -400, 0, w = 100, priority = 300)
        val squeezed = twoSided("squeezed", x = 50, alt = -400, y = 0)
        val placed = LabelPlacement.place(listOf(blockRight, blockLeft, squeezed))
        assertFalse("nowhere left to put it", "squeezed" in placed.keys)
        assertEquals(setOf("blockRight", "blockLeft"), placed.keys)
    }

    @Test fun aMovedLabelStillBlocksLaterOnes() {
        // Once a label takes its fallback slot, that space is genuinely occupied.
        val winner = box("aaa", 0, 0, w = 100, priority = 300)
        val moved = twoSided("bbb", x = 50, alt = -400, y = 0, priority = 200)
        val wantsSameSpot = box("ccc", -400, 0, w = 100, priority = 100)
        val placed = LabelPlacement.place(listOf(winner, moved, wantsSameSpot))
        assertEquals(Slot(-400, 0), placed["bbb"])
        assertFalse("ccc cannot share the slot bbb moved into", "ccc" in placed.keys)
    }

    @Test fun nodesAreNeverRemovedOnlyTheirText() {
        // The contract that matters: place() returns ids to *draw text for*, and is always a subset of
        // what it was given — it can never invent or remove a node.
        val given = listOf(box("a", 0, 0), box("b", 5, 0), box("c", 500, 500))
        val kept = kept(given)
        assertTrue(kept.all { id -> given.any { it.id == id } })
        assertFalse("a genuine collision was resolved", kept.size == given.size)
    }

    // ---- the slots a node offers ----

    @Test fun slotsAreOfferedRightLeftAboveThenBelow() {
        val slots = LabelPlacement.slotsAround(nodeX = 100, nodeY = 100, radius = 10, width = 60, height = 14, gap = 2)
        assertEquals(4, slots.size)
        assertEquals("right of the node, vertically centred on it", Slot(112, 93), slots[0])
        assertEquals("then its left", Slot(28, 93), slots[1])
        // 27px clear of the centre line, not the 12px the node's own radius would suggest: a vertical
        // slot has to clear the band a *side* label occupies, since it sits directly over both of them.
        assertEquals("then above", Slot(70, 59), slots[2])
        assertEquals("then below", Slot(70, 127), slots[3])
    }

    @Test fun aVerticalSlotClearsTheSideSlotsItIsEscaping() {
        // The bug this test caught: clearing only the node circle left 3px between the vertical box and
        // the side boxes, where PADDING wants 6 — so the escape slot was never actually free.
        val h = 14
        val slots = LabelPlacement.slotsAround(nodeX = 200, nodeY = 200, radius = 8, width = 90, height = h)
        val sideTop = slots[0].y
        val sideBottom = slots[0].y + h
        assertTrue(
            "the above slot ends clear of the side band: ${slots[2].y + h} vs $sideTop",
            slots[2].y + h + LabelPlacement.PADDING <= sideTop - LabelPlacement.PADDING,
        )
        assertTrue(
            "the below slot starts clear of it: ${slots[3].y} vs $sideBottom",
            slots[3].y - LabelPlacement.PADDING >= sideBottom + LabelPlacement.PADDING,
        )
    }

    @Test fun horizontalSlotsComeBeforeVerticalOnes() {
        // A label reads as belonging to the node it sits level with, so a side is always preferred; the
        // vertical slots are an escape hatch, not an equal option.
        val slots = LabelPlacement.slotsAround(nodeX = 0, nodeY = 0, radius = 8, width = 50, height = 12)
        assertEquals("first two are level with the node", listOf(slots[0].y, slots[1].y), listOf(slots[0].y, slots[0].y))
        assertTrue("the third sits above it", slots[2].y < slots[0].y)
        assertTrue("the fourth sits below it", slots[3].y > slots[0].y)
    }

    @Test fun aNodeCrowdedOnBothFlanksKeepsItsLabelAboveOrBelow() {
        // The screenshot defect: at 13 nodes, with the tier at ALL and most of the canvas empty, three
        // labels were withheld because both flanks were taken. Nothing was above or below them.
        val crowded = LabelPlacement.slotsAround(nodeX = 200, nodeY = 200, radius = 8, width = 90, height = 14)
        val blockers = listOf(
            box("right", crowded[0].x, crowded[0].y, w = 90, priority = 500),
            box("left", crowded[1].x, crowded[1].y, w = 90, priority = 500),
        )
        val squeezed = Candidate("squeezed", width = 90, height = 14, priority = 0, slots = crowded)

        val placed = LabelPlacement.place(blockers + squeezed)
        assertEquals("all three keep their text", 3, placed.size)
        assertNotNull(placed["squeezed"])
        assertTrue(
            "it went above or below rather than being withheld",
            placed["squeezed"] in setOf(crowded[2], crowded[3]),
        )
    }

    @Test fun theOldTwoSlotRuleWouldHaveWithheldThatLabel() {
        // Guards the fix itself: with only the two horizontal slots, the same arrangement loses a label.
        val crowded = LabelPlacement.slotsAround(nodeX = 200, nodeY = 200, radius = 8, width = 90, height = 14)
        val blockers = listOf(
            box("right", crowded[0].x, crowded[0].y, w = 90, priority = 500),
            box("left", crowded[1].x, crowded[1].y, w = 90, priority = 500),
        )
        val twoSlotOnly = Candidate("squeezed", width = 90, height = 14, priority = 0, slots = crowded.take(2))
        assertFalse("squeezed" in LabelPlacement.place(blockers + twoSlotOnly).keys)
    }

    @Test fun aVerticalSlotOccupiesSpaceLikeAnyOther() {
        // A label pushed above its node must still block a third label that wants that space.
        val crowded = LabelPlacement.slotsAround(nodeX = 200, nodeY = 200, radius = 8, width = 90, height = 14)
        val blockers = listOf(
            box("right", crowded[0].x, crowded[0].y, w = 90, priority = 500),
            box("left", crowded[1].x, crowded[1].y, w = 90, priority = 500),
        )
        val pushedUp = Candidate("pushedUp", width = 90, height = 14, priority = 400, slots = crowded)
        val wantsAbove = box("wantsAbove", crowded[2].x, crowded[2].y, w = 90, priority = 100)

        val placed = LabelPlacement.place(blockers + pushedUp + wantsAbove)
        assertEquals(crowded[2], placed["pushedUp"])
        assertFalse("the slot pushedUp took is genuinely occupied", "wantsAbove" in placed.keys)
    }
}
