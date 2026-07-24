package io.mp.sightline.ui.markdown.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidLayoutTest {

    // Fixed 100x40 nodes so geometry is deterministic and toolkit-free.
    private val fixed: (MermaidNode) -> MermaidLayout.Size = { MermaidLayout.Size(100.0, 40.0) }

    private fun layout(src: String) =
        MermaidLayout.layout((MermaidParser.parse(src) as MermaidParse.Rendered).diagram, fixed)

    @Test fun chainFlowsDownwardForTB() {
        val r = layout("graph TD\n A-->B-->C")
        val a = r.nodes.getValue("A"); val b = r.nodes.getValue("B"); val c = r.nodes.getValue("C")
        assertTrue("B below A", b.cy > a.cy)
        assertTrue("C below B", c.cy > b.cy)
        // Single column: same horizontal centre.
        assertEquals(a.cx, b.cx, 0.001)
        assertEquals(b.cx, c.cx, 0.001)
    }

    @Test fun chainFlowsRightwardForLR() {
        val r = layout("flowchart LR\n A-->B-->C")
        val a = r.nodes.getValue("A"); val b = r.nodes.getValue("B"); val c = r.nodes.getValue("C")
        assertTrue("B right of A", b.cx > a.cx)
        assertTrue("C right of B", c.cx > b.cx)
        assertEquals(a.cy, b.cy, 0.001)
    }

    @Test fun bottomToTopInvertsTheChain() {
        val r = layout("graph BT\n A-->B")
        assertTrue("with BT, B (rank 1) sits above A", r.nodes.getValue("B").cy < r.nodes.getValue("A").cy)
    }

    @Test fun branchesShareARankSideBySide() {
        val r = layout("graph TD\n A-->B\n A-->C")
        val b = r.nodes.getValue("B"); val c = r.nodes.getValue("C")
        assertEquals("B and C on the same row", b.cy, c.cy, 0.001)
        assertTrue("side by side", b.cx != c.cx)
        assertTrue("both below A", b.cy > r.nodes.getValue("A").cy)
    }

    @Test fun cyclicStateDiagramTerminatesAndPlacesEveryNode() {
        val r = layout(
            """
            stateDiagram-v2
              [*] --> A
              A --> B
              B --> A
              B --> [*]
            """.trimIndent(),
        )
        // The point: it returns (no divergence) with a rect for every node.
        for (id in listOf("__start__", "A", "B", "__end__")) assertTrue("$id placed", r.nodes.containsKey(id))
        assertTrue(r.width > 0 && r.height > 0)
    }

    @Test fun boundsEncloseEveryNode() {
        val r = layout("graph TD\n A-->B\n A-->C\n B-->D\n C-->D")
        for ((_, rect) in r.nodes) {
            assertTrue(rect.x >= -0.001 && rect.y >= -0.001)
            assertTrue(rect.x + rect.w <= r.width + 0.001)
            assertTrue(rect.y + rect.h <= r.height + 0.001)
        }
    }
}
