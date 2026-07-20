package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionModesTest {

    @Test fun conciseNamesMapToCliValues() {
        assertEquals("Ask", PermissionModes.byValue("default").shortName)
        assertEquals("Auto-edit", PermissionModes.byValue("acceptEdits").shortName)
        assertEquals("Plan", PermissionModes.byValue("plan").shortName)
        assertEquals("Auto", PermissionModes.byValue("auto").shortName)
        assertEquals("Unrestricted", PermissionModes.byValue("bypassPermissions").shortName)
    }

    @Test fun onlyUnrestrictedIsDangerous() {
        assertTrue(PermissionModes.byValue("bypassPermissions").dangerous)
        assertFalse(PermissionModes.byValue("auto").dangerous)
        assertFalse(PermissionModes.byValue("default").dangerous)
    }

    @Test fun defaultIsAutoAndUnknownFallsBack() {
        assertEquals("auto", PermissionModes.default.value)
        assertEquals("auto", PermissionModes.byValue(null).value)
        assertEquals("auto", PermissionModes.byValue("nonsense").value)
    }
}
