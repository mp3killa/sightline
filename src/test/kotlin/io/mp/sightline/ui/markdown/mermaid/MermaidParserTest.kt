package io.mp.sightline.ui.markdown.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidParserTest {

    private fun rendered(src: String): MermaidDiagram {
        val r = MermaidParser.parse(src)
        assertTrue("expected Rendered, got $r", r is MermaidParse.Rendered)
        return (r as MermaidParse.Rendered).diagram
    }

    @Test fun parsesFlowchartNodesEdgesAndDirection() {
        val d = rendered(
            """
            graph TD
              A[Start] --> B{OK?}
              B -->|yes| C[Ship]
              B -->|no| D[Fix]
            """.trimIndent(),
        )
        assertEquals(MermaidDirection.TB, d.direction)
        assertEquals(setOf("A", "B", "C", "D"), d.nodes.map { it.id }.toSet())
        assertEquals("Start", d.node("A")!!.label)
        assertEquals(MermaidShape.RECT, d.node("A")!!.shape)
        assertEquals(MermaidShape.RHOMBUS, d.node("B")!!.shape)
        assertEquals(3, d.edges.size)
        val yes = d.edges.first { it.from == "B" && it.to == "C" }
        assertEquals("yes", yes.label)
        assertTrue(yes.arrow)
    }

    @Test fun flowchartDefaultsDirectionToTB() {
        assertEquals(MermaidDirection.TB, rendered("graph\n A-->B").direction)
        assertEquals(MermaidDirection.LR, rendered("flowchart LR\n A-->B").direction)
    }

    @Test fun chainedEdgesOnOneLineDoNotSwallowMiddleNodeAsALabel() {
        val d = rendered("graph LR\n A --> B --> C")
        assertEquals(listOf("A" to "B", "B" to "C"), d.edges.map { it.from to it.to })
        assertTrue(d.edges.all { it.label == null })
    }

    @Test fun edgesWithoutSpacesParse() {
        val d = rendered("graph LR\n A-->B---C")
        assertEquals(listOf("A" to "B", "B" to "C"), d.edges.map { it.from to it.to })
        assertEquals(MermaidEdgeStyle.SOLID, d.edges[0].style)
        assertTrue(d.edges[0].arrow)
        assertTrue("--- is an open line, no arrow", !d.edges[1].arrow)
    }

    @Test fun inlineAndDottedAndThickEdges() {
        val d = rendered(
            """
            flowchart LR
              A -- yes --> B
              B -.-> C
              C ==> D
            """.trimIndent(),
        )
        assertEquals("yes", d.edges.first { it.from == "A" }.label)
        assertEquals(MermaidEdgeStyle.DOTTED, d.edges.first { it.from == "B" }.style)
        assertEquals(MermaidEdgeStyle.THICK, d.edges.first { it.from == "C" }.style)
    }

    @Test fun recognisesNodeShapes() {
        val d = rendered(
            """
            graph TD
              a[rect] --> b(round)
              b --> c([stadium])
              c --> e{{hex}}
              e --> f[[sub]]
              f --> g((circle))
            """.trimIndent(),
        )
        assertEquals(MermaidShape.RECT, d.node("a")!!.shape)
        assertEquals(MermaidShape.ROUNDED, d.node("b")!!.shape)
        assertEquals(MermaidShape.STADIUM, d.node("c")!!.shape)
        assertEquals(MermaidShape.HEXAGON, d.node("e")!!.shape)
        assertEquals(MermaidShape.SUBROUTINE, d.node("f")!!.shape)
        assertEquals(MermaidShape.DOUBLE_CIRCLE, d.node("g")!!.shape)
    }

    @Test fun bareNodeLaterLabelledUpgradesTheLabel() {
        val d = rendered("graph TD\n A --> B\n A[Full label]")
        assertEquals("Full label", d.node("A")!!.label)
    }

    @Test fun parsesStateDiagramWithPseudostates() {
        val d = rendered(
            """
            stateDiagram-v2
              [*] --> Idle
              Idle --> Running : start
              Running --> [*]
            """.trimIndent(),
        )
        assertTrue(d.nodes.any { it.id == "__start__" })
        assertTrue(d.nodes.any { it.id == "__end__" })
        assertTrue(d.nodes.any { it.id == "Idle" })
        val start = d.edges.first { it.from == "Running" }
        assertEquals("__end__", start.to)
        assertEquals("start", d.edges.first { it.from == "Idle" }.label)
    }

    @Test fun unsupportedTypesReportTheirType() {
        val r = MermaidParser.parse("sequenceDiagram\n Alice->>Bob: Hi")
        assertTrue(r is MermaidParse.Unsupported)
        assertEquals("sequencediagram", (r as MermaidParse.Unsupported).type)
    }

    @Test fun emptyOrNodelessIsFailed() {
        assertTrue(MermaidParser.parse("") is MermaidParse.Failed)
        assertTrue(MermaidParser.parse("graph TD\n  %% just a comment") is MermaidParse.Failed)
    }

    @Test fun commentsAndSubgraphKeywordsAreIgnored() {
        val d = rendered(
            """
            graph TD
              %% a comment
              subgraph one
              A --> B
              end
            """.trimIndent(),
        )
        assertEquals(setOf("A", "B"), d.nodes.map { it.id }.toSet())
        assertNull(d.nodes.firstOrNull { it.id == "one" })
    }

    @Test fun brLabelsBecomeNewlines() {
        val d = rendered("graph TD\n A[Line1<br/>Line2] --> B")
        assertEquals("Line1\nLine2", d.node("A")!!.label)
    }
}
