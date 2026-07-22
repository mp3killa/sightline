package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.UIUtil
import io.mp.sightline.settings.ClaudeSettings
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JTextPane

/**
 * Scratch reproduction for the "empty box under a heading" report: a docs-status table streamed in
 * small deltas through the live Markdown path, then finalized. Renders build/stream-table-repro.png
 * and asserts the table cells actually carry text after the stream completes.
 */
class StreamedTableReproTest : BasePlatformTestCase() {

    private val message = """
        Everything checks out. All five recommended docs exist and the git work also uses conventional fix(flux): style).

        ## Docs: found vs created

        | Doc | Found | Notes |
        |---|:-:|---|
        | `AGENTS.md` | ✓ | present |
        | `ARCHITECTURE.md` | ✓ | present |
        | `CONVENTIONS.md` | ✓ | present |
        | `CONTRIBUTING.md` | ✓ | present |
        | `docs/compose-migration.md` | ✗ | referenced but missing |

        **Created: none** — everything was already in place.
    """.trimIndent()

    private fun descendants(root: Component): List<Component> {
        val out = ArrayList<Component>()
        fun walk(c: Component) { out.add(c); if (c is Container) c.components.forEach { walk(it) } }
        walk(root)
        return out
    }

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) { if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } } }
        fun invalidateAll(x: Component) { x.invalidate(); if (x is Container) x.components.forEach { invalidateAll(it) } }
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        c.setSize(w, h); invalidateAll(c); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        invalidateAll(c); walk(c)
    }

    fun testStreamedTableRendersItsCells() {
        val mgr = EditorColorsManager.getInstance()
        mgr.allSchemes.firstOrNull { it.name.contains("Dark", true) || it.name.contains("Darcula", true) }?.let {
            mgr.setGlobalScheme(it); JBColor.setDark(true)
        }
        try {
            val settings = ClaudeSettings.getInstance().state
            settings.showDetails = false
            settings.showActivityMap = false
            settings.activityViewMode = "chat"

            // The real session's project *contains* these files, so the finalize pass linkifies the
            // table cells — reproduce that, not just the plain-text table.
            for (f in listOf("AGENTS.md", "ARCHITECTURE.md", "CONVENTIONS.md", "CONTRIBUTING.md")) {
                myFixture.addFileToProject(f, "# $f")
            }
            myFixture.addFileToProject("docs/compose-migration.md", "# migration")

            val p = ClaudePanel(project, testRootDisposable)
            p.addUserMessageForPreview("catch up on this project and check the docs")
            fun feed(line: String) { p.renderProtocolLineForPreview(line); UIUtil.dispatchAllInvocationEvents() }
            fun stream(event: String) = feed("""{"type":"stream_event","event":$event}""")

            stream("""{"type":"message_start"}""")
            stream("""{"type":"content_block_start","content_block":{"type":"text"}}""")
            // Stream in awkward small chunks that split rows and the separator mid-token.
            message.chunked(17).forEach { chunk ->
                stream("""{"type":"content_block_delta","delta":{"type":"text_delta","text":${com.google.gson.JsonPrimitive(chunk)}}}""")
                p.flushStreamingRenderForTest()
                UIUtil.dispatchAllInvocationEvents()
            }
            stream("""{"type":"content_block_stop"}""")
            feed("""{"type":"result","result":"done","duration_ms":100000,"num_turns":15,"total_cost_usd":0.84,"is_error":false}""")

            val w = 720; val h = 1000
            p.component.preferredSize = Dimension(w, h)
            layoutTree(p.component, w, h)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = p.component.background ?: java.awt.Color.BLACK
            g.fillRect(0, 0, w, h)
            p.component.printAll(g)
            g.dispose()
            val out = File("build").apply { mkdirs() }.resolve("stream-table-repro.png")
            ImageIO.write(img, "png", out)
            println("[repro] wrote ${out.absolutePath}")

            val texts = descendants(p.component).filterIsInstance<JTextPane>()
                .map { it.document.getText(0, it.document.length) }
            assertTrue("table cell AGENTS.md should render, got panes: $texts", texts.any { it.contains("AGENTS.md") })
            assertTrue("heading should render", texts.any { it.contains("Docs: found vs created") })
        } finally {
            JBColor.setDark(false)
        }
    }
}
