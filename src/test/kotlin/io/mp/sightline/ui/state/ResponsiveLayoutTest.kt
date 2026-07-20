package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {

    @Test fun widthMapsToProfile() {
        assertEquals(LayoutProfile.NARROW, ResponsiveLayout.profile(400))
        assertEquals(LayoutProfile.NARROW, ResponsiveLayout.profile(519))
        assertEquals(LayoutProfile.MEDIUM, ResponsiveLayout.profile(520))
        assertEquals(LayoutProfile.MEDIUM, ResponsiveLayout.profile(899))
        assertEquals(LayoutProfile.WIDE, ResponsiveLayout.profile(900))
    }

    @Test fun profileUsesLogicalWidthUnderScaling() {
        // 1000 physical px at 2x scale = 500 logical px -> narrow.
        assertEquals(LayoutProfile.NARROW, ResponsiveLayout.profile(1000, 2f))
    }

    @Test fun affordancesFollowProfile() {
        assertFalse(ResponsiveLayout.showHeaderLabels(LayoutProfile.NARROW))
        assertTrue(ResponsiveLayout.showHeaderLabels(LayoutProfile.WIDE))
        assertFalse(ResponsiveLayout.allowSplit(LayoutProfile.NARROW))
        assertTrue(ResponsiveLayout.allowSplit(LayoutProfile.MEDIUM))
        assertFalse(ResponsiveLayout.allowSplitDefault(LayoutProfile.MEDIUM))
        assertTrue(ResponsiveLayout.allowSplitDefault(LayoutProfile.WIDE))
        assertTrue(ResponsiveLayout.inspectorAsOverlay(LayoutProfile.NARROW))
        assertFalse(ResponsiveLayout.inspectorAsOverlay(LayoutProfile.WIDE))
    }

    // ---- readablePadding: the column, not the panel ----

    @Test fun narrowAndMediumColumnsGetOnlyBreathingRoom() {
        assertEquals(ResponsiveLayout.BASE_PADDING, ResponsiveLayout.readablePadding(420))
        assertEquals(ResponsiveLayout.BASE_PADDING, ResponsiveLayout.readablePadding(660))
        assertEquals(ResponsiveLayout.BASE_PADDING, ResponsiveLayout.readablePadding(899))
    }

    @Test fun aWideColumnIsCentredOnTheReadingWidth() {
        val available = 1400
        val pad = ResponsiveLayout.readablePadding(available)
        val content = available - pad * 2
        assertEquals(ResponsiveLayout.maxReadableWidth(LayoutProfile.WIDE), content)
    }

    /**
     * Regression: padding was computed from the whole panel and applied to the much narrower chat
     * column, crushing the conversation into a sliver on first open. Whatever the arithmetic, the
     * column must never fall below the floor.
     */
    @Test fun paddingNeverSqueezesContentBelowTheFloor() {
        for (available in listOf(0, 100, 361, 500, 760, 900, 901, 1000, 1400, 4000)) {
            val pad = ResponsiveLayout.readablePadding(available)
            val content = available - pad * 2
            assertTrue("padding must not exceed the space ($available -> pad $pad)", pad >= 0)
            // On a genuinely tiny column, edge breathing room legitimately wins over the floor —
            // there simply isn't room for both. Everywhere else the floor holds.
            val achievable = minOf(available - 2 * ResponsiveLayout.BASE_PADDING, ResponsiveLayout.MIN_CONTENT_WIDTH)
            assertTrue(
                "content squeezed to $content at available=$available (achievable $achievable)",
                content >= achievable,
            )
        }
    }

    /** The concrete first-open case: a ~660px chat column inside a ~1370px panel must not be capped. */
    @Test fun aSplitChatColumnUsesItsFullWidth() {
        val chatColumn = 660
        val pad = ResponsiveLayout.readablePadding(chatColumn)
        assertEquals("a medium column takes no reading cap", ResponsiveLayout.BASE_PADDING, pad)
        assertTrue("the conversation should use nearly all of its column", chatColumn - pad * 2 > 600)
    }

    @Test fun zeroWidthFallsBackToBreathingRoomNotAGuess() {
        assertEquals(ResponsiveLayout.BASE_PADDING, ResponsiveLayout.readablePadding(0))
        assertEquals(ResponsiveLayout.BASE_PADDING, ResponsiveLayout.readablePadding(-50))
    }
}
