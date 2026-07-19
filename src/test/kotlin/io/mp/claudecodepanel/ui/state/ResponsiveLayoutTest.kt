package io.mp.claudecodepanel.ui.state

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
}
