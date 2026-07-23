package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeGlyphTest {

    @Test fun sourceKindsAreDocuments() {
        for (t in listOf(
            ActivityNodeType.FILE, ActivityNodeType.CLASS, ActivityNodeType.COMPOSABLE,
            ActivityNodeType.VIEW_MODEL, ActivityNodeType.REPOSITORY, ActivityNodeType.USE_CASE,
            ActivityNodeType.API_ENDPOINT,
        )) {
            assertEquals("$t should be a document", NodeGlyph.DOCUMENT, glyphFor(t))
        }
    }

    @Test fun commandsAreTerminals() {
        assertEquals(NodeGlyph.TERMINAL, glyphFor(ActivityNodeType.COMMAND))
        assertEquals(NodeGlyph.TERMINAL, glyphFor(ActivityNodeType.GRADLE_TASK))
    }

    @Test fun testsWarningsAndErrorsEachHaveTheirOwnShape() {
        assertEquals(NodeGlyph.HEXAGON, glyphFor(ActivityNodeType.TEST))
        assertEquals(NodeGlyph.TRIANGLE, glyphFor(ActivityNodeType.WARNING))
        assertEquals(NodeGlyph.DIAMOND, glyphFor(ActivityNodeType.ERROR))
    }

    @Test fun errorAndWarningAreDistinctShapes() {
        // The whole point: a failure and a warning must not look the same when colour is stripped.
        assertTrue(glyphFor(ActivityNodeType.ERROR) != glyphFor(ActivityNodeType.WARNING))
    }

    @Test fun scaffoldingHubsStayCircles() {
        assertEquals(NodeGlyph.CIRCLE, glyphFor(ActivityNodeType.TASK))
        assertEquals(NodeGlyph.CIRCLE, glyphFor(ActivityNodeType.CATEGORY))
    }

    @Test fun everyTypeMapsToSomeGlyph() {
        for (t in ActivityNodeType.values()) assertNotNull(glyphFor(t))
    }

    @Test fun glyphShapeIsCentredAndSized() {
        // A glyph's footprint should sit around its centre at roughly the requested radius.
        for (g in NodeGlyph.values()) {
            val b = glyphShape(g, 100.0, 100.0, 10.0).bounds2D
            assertTrue("$g centred x", b.centerX in 90.0..110.0)
            assertTrue("$g centred y", b.centerY in 90.0..110.0)
            assertTrue("$g has size", b.width > 5.0 && b.height > 5.0)
        }
    }

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
