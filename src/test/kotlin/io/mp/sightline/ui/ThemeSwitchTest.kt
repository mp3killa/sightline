package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.mp.sightline.settings.ClaudeSettings
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JComponent

/**
 * The activity map must paint as **one surface**.
 *
 * Its toolbar and its log bar are transparent, so whatever the map's root paints is what shows through
 * in those two strips. The root used to have no background of its own — it inherited the LaF's
 * `Panel.background`, while the canvas computed its own `canvasBg()` on every paint. Two different
 * colours that only agreed by luck: in a light IDE the strips rendered dark around a correctly light
 * graph, and a colour resolved once at construction could not follow a theme change afterwards either.
 *
 * Asserting the two agree catches both, and needs no screenshot and no particular LaF — which matters,
 * because the test harness runs under Aqua and cannot reproduce the IDE's own themes at all.
 */
class ThemeSwitchTest : BasePlatformTestCase() {

    fun testMapChromeAndCanvasPaintTheSameSurface() {
        val settings = ClaudeSettings.getInstance().state
        settings.showActivityMap = true
        settings.activityViewMode = "map"

        val p = ClaudePanel(project, testRootDisposable)
        p.addUserMessageForPreview("hello")
        p.component.preferredSize = Dimension(900, 600)
        layoutTree(p.component, 900, 600)

        // What actually paints behind the transparent toolbar: its first opaque ancestor.
        val title = descendants(p.component).filterIsInstance<javax.swing.JLabel>()
            .firstOrNull { it.text == "Activity" } ?: error("could not find the map toolbar")
        val behindToolbar = firstOpaqueAncestor(title)
        val canvas = descendants(p.component).filterIsInstance<JComponent>()
            .firstOrNull { it.javaClass.simpleName.contains("GraphCanvas") }
            ?: error("could not find the graph canvas")

        assertEquals(
            "the map's chrome and its canvas must paint the same colour — the toolbar and the log bar " +
                "are transparent, so a mismatch is exactly the dark strip a light IDE showed",
            canvas.background,
            behindToolbar.background,
        )
    }

    /** The nearest ancestor that actually paints — what a transparent strip shows through to. */
    private fun firstOpaqueAncestor(c: Component): JComponent {
        var up: Component? = c.parent
        while (up != null) {
            if (up is JComponent && up.isOpaque) return up
            up = up.parent
        }
        error("nothing opaque behind the toolbar")
    }

    private fun descendants(root: Component): List<Component> {
        val out = ArrayList<Component>()
        fun walk(c: Component) { out.add(c); if (c is Container) c.components.forEach { walk(it) } }
        walk(root)
        return out
    }

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) { if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } } }
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
    }
}
