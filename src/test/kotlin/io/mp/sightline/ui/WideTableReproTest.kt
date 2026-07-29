package io.mp.sightline.ui

import com.google.gson.JsonPrimitive
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.mp.sightline.settings.ClaudeSettings
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/**
 * A GFM table whose cells are wider than the panel must still **render its cells**.
 *
 * This is a regression test for a table that appeared in the transcript as an empty bordered box. The
 * markdown parsed correctly and every cell pane existed with the right text and preferred size — they
 * were simply laid out at negative width and height (`[width=-17,height=-9]`), so they painted nothing.
 *
 * Two causes, both fixed: the scroll decision compared the available width against the *column floor*
 * (3 × 96px) rather than what the cells actually asked for (888px in a 754px column), so it declared "it
 * fits" and squeezed; and each cell carried `minimumSize.height = 0`, which let `GridBagLayout` collapse
 * the row until the cell's own padding drove the pane negative.
 *
 * The assertion is deliberately about **geometry, not pixels**: a cell with a negative size is invisible
 * no matter what colour it would have been, and no screenshot diff is needed to catch it.
 */
class WideTableReproTest : BasePlatformTestCase() {

    /** The real message from the session that surfaced this — 3 columns of prose, far wider than a panel. */
    private val md = """
        Compiles clean. Here's what the sheet now shows for each kind:

        | Entry button tapped | Sheet title | Confirm button |
        |---|---|---|
        | Activate Returns | **End route to activate returns?** | **Activate Returns** |
        | Activate Re-Collections/Re-Deliveries | **End route to activate re-collections/re-deliveries?** | **Activate Re-Collections/Re-Deliveries** |

        So the kind is now stated in both the title and the confirm button.
    """.trimIndent()

    fun testWideTableCellsAreLaidOutVisiblyRatherThanCollapsed() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
        settings.showActivityMap = false
        settings.activityViewMode = "chat"

        val p = ClaudePanel(project, testRootDisposable)
        p.addUserMessageForPreview("show me the sheet labels")
        p.renderProtocolLineForPreview(
            """{"type":"assistant","message":{"content":[{"type":"text","text":${JsonPrimitive(md)}}]}}"""
        )
        val w = 1400; val h = 900
        p.component.preferredSize = Dimension(w, h)
        layoutTree(p.component, w, h)

        // Every cell of the table, named explicitly: a count would pass while a cell was silently missing.
        val expected = listOf(
            "Entry button tapped", "Sheet title", "Confirm button",
            "Activate Returns", "End route to activate returns?", "Activate Returns",
            "Activate Re-Collections/Re-Deliveries",
            "End route to activate re-collections/re-deliveries?",
            "Activate Re-Collections/Re-Deliveries",
        )
        val panes = descendants(p.component).filterIsInstance<JTextComponent>()
        val cells = panes.filter { it.text.orEmpty().trim() in expected }

        for (text in expected.distinct()) {
            assertTrue("table cell '$text' is missing entirely", cells.any { it.text.orEmpty().trim() == text })
        }
        assertEquals("every cell of the 3x3 table must be present", expected.size, cells.size)

        for (cell in cells) {
            val b = cell.bounds
            assertTrue(
                "cell '${cell.text.take(40)}' laid out at $b — a non-positive size paints nothing, " +
                    "which is what made this table render as an empty box",
                b.width > 0 && b.height > 0,
            )
        }

        // The fixture has to actually overflow, or this passes for the wrong reason. The table's host is
        // wider than the viewport showing it — i.e. it took the scroll path instead of being squeezed.
        var viewport: javax.swing.JViewport? = null
        var up: Component? = cells.first()
        while (up != null && viewport == null) {
            up = up.parent
            viewport = up as? javax.swing.JViewport
        }
        assertNotNull("the table must sit in a scroller", viewport)
        val host = viewport!!.view
        assertTrue(
            "fixture must overflow to exercise the fix (host=${host.width}, viewport=${viewport.width})",
            host.width > viewport.width,
        )

        val out = File("build").apply { mkdirs() }.resolve("wide-table.png")
        render(p.component, w, h, out)
        println("[wide-table] wrote ${out.absolutePath}")
    }

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
        invalidateAll(c); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        invalidateAll(c); walk(c)
    }

    private fun render(c: JComponent, w: Int, h: Int, out: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = c.background ?: java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            c.printAll(g)
        } finally { g.dispose() }
        ImageIO.write(img, "png", out)
    }
}
