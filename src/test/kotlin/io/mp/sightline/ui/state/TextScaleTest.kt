package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScaleTest {

    @Test fun `the scale is bounded at both ends`() {
        assertEquals(TextScale.MIN, TextScale.clamp(10))
        assertEquals(TextScale.MAX, TextScale.clamp(1000))
        assertEquals(125, TextScale.clamp(125))
    }

    @Test fun `sizes scale and never collapse to nothing`() {
        assertEquals(12f, TextScale.size(12f, 100), 0.01f)
        assertEquals(15f, TextScale.size(12f, 125), 0.01f)
        // Even an absurd combination stays rendered rather than vanishing.
        assertTrue(TextScale.size(1f, TextScale.MIN) >= 6f)
    }

    @Test fun `the neutral value is labelled as neutral`() {
        assertEquals("100% (IDE default)", TextScale.label(100))
        assertEquals("150%", TextScale.label(150))
        assertFalse(TextScale.isCustom(100))
        assertTrue(TextScale.isCustom(90))
    }

    @Test fun `the offered steps are in range and include the default`() {
        assertTrue(TextScale.STEPS.contains(TextScale.DEFAULT))
        assertTrue(TextScale.STEPS.all { it in TextScale.MIN..TextScale.MAX })
        assertEquals(TextScale.STEPS.sorted(), TextScale.STEPS)
    }
}
