package io.mp.sightline.ui.markdown.mermaid

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edge arrowhead, kept under test after it moved out of the (removed) activity map. */
class MermaidGeometryTest {

    @Test fun arrowheadPointsFromSourceToTarget() {
        val head = arrowhead(0.0, 0.0, 100.0, 0.0, targetRadius = 10.0, size = 8.0)
        assertNotNull(head)
        // Tip is backed off the target by targetRadius; the whole head sits before x=90.
        assertTrue(head!!.bounds2D.maxX <= 90.5)
    }

    @Test fun arrowheadIsNullForZeroLengthEdge() {
        assertNull(arrowhead(5.0, 5.0, 5.0, 5.0, targetRadius = 10.0, size = 8.0))
    }
}
