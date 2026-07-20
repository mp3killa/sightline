package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceModesTest {

    @Test fun fromSettingsMapsShowMapAndViewMode() {
        assertEquals(WorkspaceMode.CHAT, WorkspaceModes.fromSettings(false, "map"))
        assertEquals(WorkspaceMode.CHAT, WorkspaceModes.fromSettings(true, "chat"))
        assertEquals(WorkspaceMode.SPLIT, WorkspaceModes.fromSettings(true, "split"))
        assertEquals(WorkspaceMode.ACTIVITY, WorkspaceModes.fromSettings(true, "map"))
        assertEquals(WorkspaceMode.ACTIVITY, WorkspaceModes.fromSettings(true, "legacy-value"))
    }

    @Test fun toSettingsIsConsistent() {
        assertFalse(WorkspaceModes.toShowActivityMap(WorkspaceMode.CHAT))
        assertTrue(WorkspaceModes.toShowActivityMap(WorkspaceMode.SPLIT))
        assertTrue(WorkspaceModes.toShowActivityMap(WorkspaceMode.ACTIVITY))
        assertEquals("map", WorkspaceModes.toViewMode(WorkspaceMode.ACTIVITY))
    }

    @Test fun roundTripsThroughSettings() {
        for (mode in WorkspaceMode.values()) {
            val restored = WorkspaceModes.fromSettings(
                WorkspaceModes.toShowActivityMap(mode),
                WorkspaceModes.toViewMode(mode),
            )
            assertEquals(mode, restored)
        }
    }

    // ---- effectiveMode: SPLIT is a wide-panel-only layout ----

    @Test fun splitSurvivesOnlyOnAWidePanel() {
        assertEquals(WorkspaceMode.SPLIT, WorkspaceModes.effectiveMode(WorkspaceMode.SPLIT, LayoutProfile.WIDE))
        assertEquals(WorkspaceMode.CHAT, WorkspaceModes.effectiveMode(WorkspaceMode.SPLIT, LayoutProfile.MEDIUM))
        assertEquals(WorkspaceMode.CHAT, WorkspaceModes.effectiveMode(WorkspaceMode.SPLIT, LayoutProfile.NARROW))
    }

    /** Regression: a cramped panel used to fall back to the *graph*, leaving no conversation at all. */
    @Test fun tooNarrowSplitFallsBackToChatNeverToActivity() {
        for (p in listOf(LayoutProfile.NARROW, LayoutProfile.MEDIUM)) {
            assertEquals(
                "a demoted SPLIT must keep the conversation, not the graph",
                WorkspaceMode.CHAT,
                WorkspaceModes.effectiveMode(WorkspaceMode.SPLIT, p),
            )
        }
    }

    @Test fun explicitChatOrActivityChoiceIsNeverOverridden() {
        for (p in LayoutProfile.values()) {
            assertEquals(WorkspaceMode.CHAT, WorkspaceModes.effectiveMode(WorkspaceMode.CHAT, p))
            assertEquals(WorkspaceMode.ACTIVITY, WorkspaceModes.effectiveMode(WorkspaceMode.ACTIVITY, p))
        }
    }

    /** The preference is a pure read — widening the panel must bring SPLIT back. */
    @Test fun demotionIsNotDestructive() {
        val preferred = WorkspaceMode.SPLIT
        assertEquals(WorkspaceMode.CHAT, WorkspaceModes.effectiveMode(preferred, LayoutProfile.NARROW))
        assertEquals(WorkspaceMode.SPLIT, WorkspaceModes.effectiveMode(preferred, LayoutProfile.WIDE))
    }
}
