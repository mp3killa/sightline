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
}
