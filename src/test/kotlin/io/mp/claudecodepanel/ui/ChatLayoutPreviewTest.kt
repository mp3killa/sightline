package io.mp.claudecodepanel.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.settings.ClaudeSettings
import io.mp.claudecodepanel.ui.state.ResponsiveLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Dev preview + regression guard for the **chat layout**.
 *
 * Builds a real [ClaudePanel] under the platform, lays it out at each [ResponsiveLayout] width class,
 * and writes a PNG per width to `build/` — so the layout can be eyeballed (and diffed across commits)
 * without launching the IDE. This is the same trick `ActivityMapPreviewTest` uses for the graph, and
 * it is the only automated *visual* channel available: the JetBrains `studio` MCP can drive the IDE's
 * editors and terminal but has no screenshot tool and cannot see this plugin's tool window.
 *
 * Alongside the images it asserts the layout invariants Tier 1 fixed, so a regression fails the build
 * rather than waiting to be noticed in a picture:
 *  - SPLIT is demoted to CHAT below the WIDE breakpoint (never to the graph-only view)
 *  - the demotion does not rewrite the persisted preference
 *  - the transcript is centred within a readable column once there is room
 *
 * BasePlatformTestCase runs on the EDT, so the Swing tree is exercised on the correct thread.
 */
class ChatLayoutPreviewTest : BasePlatformTestCase() {

    private val widths = listOf(
        "narrow" to 420,   // below ResponsiveLayout.NARROW_MAX
        "medium" to 720,   // between the breakpoints
        "wide" to 1400,    // above MEDIUM_MAX — the only width that may split
    )

    private fun panel(): ClaudePanel = ClaudePanel(project, testRootDisposable)

    /**
     * A representative turn: prose with Markdown, a routine read, a command, and an edit with a diff.
     * Fed through the production event path so the preview shows exactly what a session would.
     */
    private fun seedTranscript(p: ClaudePanel) {
        fun feed(line: String) = p.renderProtocolLineForPreview(line)
        fun assistantText(text: String) = feed(
            """{"type":"assistant","message":{"content":[{"type":"text","text":${quote(text)}}]}}"""
        )
        fun toolUse(id: String, name: String, input: String) = feed(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""
        )
        fun toolResult(id: String, content: String, isError: Boolean = false) = feed(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":${quote(content)},"is_error":$isError}]}}"""
        )

        p.addUserMessageForPreview("Make the activity map stop eating half the chat view.")
        assistantText(
            """
            I found the cause. `ResponsiveLayout.allowSplitDefault()` was **never called** —
            the guard existed but was dead code.

            ## What I changed

            - Wired the guard so `SPLIT` only survives on a wide panel
            - Demoted narrow layouts to `CHAT`, not the graph

            | Width | Before | After |
            |---|---|---|
            | Narrow | Graph only | Conversation |
            | Wide | Split | Split |
            """.trimIndent()
        )
        toolUse("t1", "Read", """{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/ClaudePanel.kt"}""")
        toolResult("t1", "1767 lines read")
        toolUse("t2", "Bash", """{"command":"./gradlew test","description":"Run the test suite"}""")
        toolResult("t2", "BUILD SUCCESSFUL in 8s\n368 tests, 0 failures")
        toolUse(
            "t3", "Edit",
            """{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/state/WorkspaceModes.kt","old_string":"fun toViewMode(mode: WorkspaceMode): String","new_string":"fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode"}"""
        )
        toolResult("t3", "Applied")
        assistantText("The split now only appears when there is genuinely room for it.")
        feed("""{"type":"result","result":"done","duration_ms":51600,"num_turns":13,"total_cost_usd":0.404,"is_error":false}""")
    }

    private fun quote(s: String): String = com.google.gson.JsonPrimitive(s).toString()

    /**
     * Lay a detached tree out headlessly. Two things this must do that a plain `setSize` does not:
     * the tree has no peers, so `doLayout()` is driven down manually; and the panel's responsive pass
     * runs from a queued `invokeLater`/`componentResized`, so the EDT queue is pumped in between —
     * otherwise the width-driven layout never applies and the preview shows a stale layout.
     */
    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) {
            if (x is Container) {
                x.doLayout()
                x.components.forEach { walk(it) }
            }
        }
        // doLayout() alone does not clear BoxLayout's cached child sizes, so a wrapping JTextPane keeps
        // the single-line preferred height it reported before it had a width — text then renders
        // clipped in the image while being perfectly fine in a real, validated hierarchy. Invalidating
        // between passes is what makes the preview representative.
        fun invalidateAll(x: Component) {
            x.invalidate()
            if (x is Container) x.components.forEach { invalidateAll(it) }
        }
        c.setSize(w, h)
        walk(c)
        UIUtil.dispatchAllInvocationEvents() // let applyProfile() see the real width
        c.setSize(w, h)
        invalidateAll(c)
        walk(c)
        UIUtil.dispatchAllInvocationEvents() // and let any re-install of the center settle
        invalidateAll(c)
        walk(c)
    }

    /** The splitter is present only when the two-pane layout is actually installed. */
    private fun isSplit(c: Component): Boolean = descendants(c).any { it is JBSplitter && it.isShowing || it is JBSplitter && it.parent != null }

    private fun render(c: JComponent, w: Int, h: Int, out: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = c.background ?: java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            c.printAll(g)
        } finally {
            g.dispose()
        }
        ImageIO.write(img, "png", out)
    }

    fun testWritesChatLayoutPreviewsAtEveryWidth() {
        val dir = File("build").apply { mkdirs() }
        val settings = ClaudeSettings.getInstance().state
        settings.showActivityMap = true
        settings.activityViewMode = "split" // the shipped default — the case Tier 1 changes

        // Tool cards are hidden unless details are on; the preview wants them visible, since their
        // visual weight is exactly what Tier 2 is about.
        settings.showDetails = true

        for ((name, w) in widths) {
            val p = panel()
            val comp = p.component
            val h = 900
            comp.preferredSize = Dimension(w, h)
            seedTranscript(p)
            layoutTree(comp, w, h)

            val out = File(dir, "chat-layout-$name.png")
            render(comp, w, h, out)
            println("[chat-layout-preview] wrote ${out.absolutePath} (${w}x$h)")
            assertTrue("preview not written for $name", out.length() > 2000)
        }
    }

    /**
     * The Tier 1 invariant: at a width that cannot carry two panes, the conversation survives.
     * Before the fix a narrow panel fell back to the *graph*, leaving no transcript at all.
     */
    fun testNarrowAndMediumPanelsKeepTheConversation() {
        val settings = ClaudeSettings.getInstance().state
        for (w in listOf(420, 720)) {
            settings.showActivityMap = true
            settings.activityViewMode = "split"
            val p = panel()
            layoutTree(p.component, w, 900)

            assertFalse(
                "at ${w}px the two-pane split must be demoted so the conversation gets the width",
                isSplit(p.component),
            )
            assertEquals(
                "a width-driven demotion must not rewrite the user's preference",
                "split",
                ClaudeSettings.getInstance().state.activityViewMode,
            )
        }
    }

    /** A wide panel is the one case where the split is a sensible default. */
    fun testWidePanelHonoursTheSplitPreference() {
        val settings = ClaudeSettings.getInstance().state
        settings.showActivityMap = true
        settings.activityViewMode = "split"
        val p = panel()
        layoutTree(p.component, 1400, 900)
        assertEquals("split", ClaudeSettings.getInstance().state.activityViewMode)
        assertTrue("a wide panel should still honour the split preference", isSplit(p.component))
    }

    private fun descendants(root: Component): List<Component> {
        val out = ArrayList<Component>()
        fun walk(c: Component) {
            out.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /**
     * Regression: on first open — no resizing — the conversation used only a fraction of its column.
     *
     * Padding was computed from `component.width` whenever the viewport hadn't been laid out yet, which
     * on first open is always. In SPLIT the panel is far wider than the chat column, so the transcript
     * got padding sized for a column roughly twice its real width, and because the panel width then
     * never changed nothing recomputed it. The text stayed crushed until the window was resized.
     */
    fun testFirstOpenUsesTheFullChatColumnWithoutResizing() {
        val settings = ClaudeSettings.getInstance().state
        settings.showActivityMap = true
        settings.activityViewMode = "split"

        val p = panel()
        // A fresh panel shows the empty state, so the transcript isn't in the tree yet — seed a turn
        // first, exactly as the first message does, then lay out once. No resize, no second chance.
        seedTranscript(p)
        layoutTree(p.component, 1400, 900)

        val column = p.transcriptColumnWidthForTest()
        val pad = p.transcriptPaddingForTest()
        assertTrue("the chat column should have a real width, got $column", column > 200)
        val content = column - pad * 2
        assertTrue(
            "the conversation should fill its column: column=$column pad=$pad content=$content",
            content > column * 0.8,
        )
    }
}
