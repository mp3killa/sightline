package io.mp.sightline.activity

import io.mp.sightline.activity.MapDensity.LabelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDensityTest {

    // ---- tiers ----

    @Test fun quietGraphLabelsEverything() {
        assertEquals(LabelTier.ALL, MapDensity.labelTier(visibleNodeCount = 12, scale = 1.0))
    }

    @Test fun tiersStepDownAsTheGraphGrows() {
        assertEquals(LabelTier.IMPORTANT, MapDensity.labelTier(80, 1.0))
        assertEquals(LabelTier.MINIMAL, MapDensity.labelTier(300, 1.0))
    }

    @Test fun tierBoundariesAreInclusiveOfTheLowerTier() {
        assertEquals(LabelTier.ALL, MapDensity.labelTier(MapDensity.IMPORTANT_ABOVE, 1.0))
        assertEquals(LabelTier.IMPORTANT, MapDensity.labelTier(MapDensity.IMPORTANT_ABOVE + 1, 1.0))
        assertEquals(LabelTier.IMPORTANT, MapDensity.labelTier(MapDensity.MINIMAL_ABOVE, 1.0))
        assertEquals(LabelTier.MINIMAL, MapDensity.labelTier(MapDensity.MINIMAL_ABOVE + 1, 1.0))
    }

    @Test fun zoomingInEarnsBackOneTier() {
        val zoomed = MapDensity.ZOOM_RELAX_SCALE
        assertEquals(LabelTier.IMPORTANT, MapDensity.labelTier(300, zoomed))
        assertEquals(LabelTier.ALL, MapDensity.labelTier(80, zoomed))
        // ...but only one step: a huge graph zoomed in is still not fully labelled.
        assertFalse(MapDensity.labelTier(300, zoomed) == LabelTier.ALL)
    }

    // ---- label decisions ----

    @Test fun attentionNodesAreAlwaysLabelled() {
        // Whatever the density, the node the user is pointing at must say what it is.
        for (tier in LabelTier.entries) {
            assertTrue(
                "labelled at $tier",
                MapDensity.showsLabel(tier, ActivityNodeType.COMMAND, attention = true, radius = 1.0, scale = 0.1),
            )
        }
    }

    @Test fun errorsAndAnchorsSurviveEveryTier() {
        for (tier in LabelTier.entries) {
            for (type in listOf(ActivityNodeType.ERROR, ActivityNodeType.TASK, ActivityNodeType.CATEGORY)) {
                assertTrue(
                    "$type labelled at $tier",
                    MapDensity.showsLabel(tier, type, attention = false, radius = 8.0, scale = 1.0),
                )
            }
        }
    }

    @Test fun allTierPreservesTheZoomAndSizeRuleForOrdinaryNodes() {
        val t = LabelTier.ALL
        assertTrue(MapDensity.showsLabel(t, ActivityNodeType.COMMAND, false, radius = 8.0, scale = 1.0))
        assertFalse("zoomed out", MapDensity.showsLabel(t, ActivityNodeType.COMMAND, false, radius = 8.0, scale = 0.4))
        assertFalse("too small", MapDensity.showsLabel(t, ActivityNodeType.COMMAND, false, radius = 3.0, scale = 1.0))
    }

    @Test fun importantTierDropsOrdinaryLabelsEvenWhenZoomedIn() {
        assertFalse(MapDensity.showsLabel(LabelTier.IMPORTANT, ActivityNodeType.COMMAND, false, 12.0, 2.0))
        assertTrue("edits stay named", MapDensity.showsLabel(LabelTier.IMPORTANT, ActivityNodeType.PATCH, false, 8.0, 1.0))
    }

    @Test fun minimalTierDropsEvenPatchLabels() {
        assertFalse(MapDensity.showsLabel(LabelTier.MINIMAL, ActivityNodeType.PATCH, false, 8.0, 1.0))
        assertTrue(MapDensity.showsLabel(LabelTier.MINIMAL, ActivityNodeType.ERROR, false, 8.0, 1.0))
    }

    // ---- "Show more" ----

    @Test fun countTextNamesTheTruncation() {
        assertEquals("12 of 80 · Show more", MapDensity.countText(shown = 12, total = 80))
    }

    @Test fun countTextIsPlainWhenNothingIsHidden() {
        assertEquals("80 nodes", MapDensity.countText(shown = 80, total = 80))
        assertEquals("1 node", MapDensity.countText(shown = 1, total = 1))
    }

    @Test fun hiddenCountNeverGoesNegative() {
        assertEquals(0, MapDensity.hiddenCount(shown = 90, total = 80))
    }

    @Test fun showMoreDoublesTheCapUpToWhatExists() {
        assertEquals(400, MapDensity.nextVisibleCap(currentCap = 200, total = 900))
        assertEquals("stops at the real total", 250, MapDensity.nextVisibleCap(currentCap = 200, total = 250))
    }

    @Test fun capNeverExceedsTheSettingsCeiling() {
        assertEquals(MapDensity.MAX_VISIBLE_CAP, MapDensity.nextVisibleCap(currentCap = 1500, total = 9999))
    }

    @Test fun capDoesNotShrinkWhenEverythingAlreadyFits() {
        assertEquals(200, MapDensity.nextVisibleCap(currentCap = 200, total = 40))
    }

    // ---- fit ----

    @Test fun fitPaddingGrowsWithDensity() {
        assertTrue(MapDensity.fitPadding(300) > MapDensity.fitPadding(80))
        assertTrue(MapDensity.fitPadding(80) > MapDensity.fitPadding(10))
    }

    @Test fun emptyGraphHasNoFitBounds() {
        assertNull(MapDensity.fitBounds(emptyList(), emptyList()))
    }

    @Test fun smallGraphIsFramedExactly() {
        val xs = listOf(0.0, 10.0, -5.0)
        val ys = listOf(3.0, 7.0, 1.0)
        val b = MapDensity.fitBounds(xs, ys)!!
        assertEquals(-5.0, b.minX, 0.0001)
        assertEquals(10.0, b.maxX, 0.0001)
        assertEquals(1.0, b.minY, 0.0001)
        assertEquals(7.0, b.maxY, 0.0001)
    }

    @Test fun bigGraphIgnoresFarFlungOutliers() {
        // 100 nodes clustered in [0,100] plus one at 100_000: framing the outlier would shrink the
        // other 100 to a speck, so it is allowed to fall outside the view.
        val xs = (0..99).map { it.toDouble() } + 100_000.0
        val ys = (0..99).map { it.toDouble() } + 100_000.0
        val b = MapDensity.fitBounds(xs, ys)!!
        assertTrue("outlier excluded", b.maxX < 1_000.0)
        assertTrue("bulk still framed", b.maxX >= 90.0)
    }

    @Test fun trimmingNeverInvertsTheSpan() {
        // Degenerate input (every node stacked) must still yield a usable, non-inverted rectangle.
        val xs = List(200) { 5.0 }
        val b = MapDensity.fitBounds(xs, xs)!!
        assertTrue(b.maxX >= b.minX)
        assertTrue(b.maxY >= b.minY)
    }
}
