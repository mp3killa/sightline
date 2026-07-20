package io.mp.claudecodepanel.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.android.AndroidContext
import io.mp.claudecodepanel.android.DeviceContext
import io.mp.claudecodepanel.android.DeviceState
import io.mp.claudecodepanel.android.EditorContext
import io.mp.claudecodepanel.android.Fact
import io.mp.claudecodepanel.android.FactTier
import io.mp.claudecodepanel.android.ModuleContext
import io.mp.claudecodepanel.settings.ClaudeSettings
import io.mp.claudecodepanel.ui.components.ContextChip
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
     * A representative Android context — the sample-android-app shape: two flavour dimensions, a
     * connected emulator, an open Compose screen. Deliberately **mixed provenance**: the variant comes
     * from a build output and so must render its "(last build)" qualifier, which is the thing worth
     * looking at in the PNG.
     */
    private fun androidContext() = AndroidContext(
        modules = listOf(
            ModuleContext(
                name = "app",
                gradlePath = ":app",
                variant = Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT),
                flavors = Fact.known(listOf("demo", "staging"), FactTier.BUILD_OUTPUT),
                buildType = Fact.known("debug", FactTier.BUILD_OUTPUT),
                applicationId = Fact.known("com.example.driver.staging", FactTier.BUILD_OUTPUT),
                minSdk = Fact.known(24, FactTier.STATIC_PARSE),
                targetSdk = Fact.known(36, FactTier.STATIC_PARSE),
                compileSdk = Fact.known("36", FactTier.STATIC_PARSE),
                usesCompose = Fact.known(true, FactTier.STATIC_PARSE),
            ),
            ModuleContext(name = "core", gradlePath = ":core"),
        ),
        activeModuleName = "app",
        device = DeviceContext(
            serial = "emulator-5554",
            name = "Pixel 8",
            state = DeviceState.ONLINE,
            apiLevel = Fact.known(35, FactTier.DEVICE),
            androidRelease = Fact.known("15", FactTier.DEVICE),
            appRunning = Fact.known(true, FactTier.DEVICE),
        ),
        editor = EditorContext("app/src/main/java/com/example/ui/RouteDetailsScreen.kt"),
    )

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
            p.setAndroidContextForPreview(androidContext())
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

    // ---- Android context strip (docs/ANDROID.md M1) ----

    /**
     * The strip and one chip per enabled fact are in the tree at every width. Asserted on the component
     * tree rather than on pixels: the harness's own lesson is that an image plus a vague assertion
     * ("a JScrollPane exists somewhere") passes while the thing under test is broken.
     */
    fun testAndroidContextStripAndChipsRenderAtEveryWidth() {
        for ((name, w) in widths) {
            val p = panel()
            seedTranscript(p)
            p.setAndroidContextForPreview(androidContext())
            layoutTree(p.component, w, 900)

            val strip = findByAccessibleName(p.component, A11yNames.ANDROID_CONTEXT_STRIP)
            assertNotNull("[$name] the Android context strip should be in the tree", strip)
            assertTrue("[$name] the strip should be visible", strip!!.isVisible)

            val text = collectLabels(p.component)
            assertTrue("[$name] the strip should name the variant, got: $text", text.any { it.contains("demoStagingDebug") })
            assertTrue("[$name] the strip should name the device, got: $text", text.any { it.contains("Pixel 8") })

            // Four chips: module, variant, device, current file — the defaults. At narrow width they
            // wrap onto a second row, which only works because the row uses WrapLayout: plain
            // FlowLayout reports a one-row preferred height and the fourth chip is clipped away.
            val chips = mutableListOf<Component>()
            walk(p.component) { if (it is ContextChip) chips += it }
            assertEquals("[$name] one chip per enabled fact", 4, chips.size)
            val chipsRow = chips.first().parent
            assertTrue(
                "[$name] every chip must be inside the row's bounds, not clipped",
                chips.all { it.y + it.height <= chipsRow.height },
            )
        }
    }

    /**
     * At the narrowest width the strip sheds its lowest-priority segment rather than running off the
     * edge. Found by reading the PNG: the first version clipped `RouteDetailsScreen.kt` mid-word.
     */
    fun testTheStripShedsSegmentsRatherThanClippingWhenNarrow() {
        fun stripText(width: Int): String {
            val p = panel()
            seedTranscript(p)
            p.setAndroidContextForPreview(androidContext())
            layoutTree(p.component, width, 900)
            val strip = findByAccessibleName(p.component, A11yNames.ANDROID_CONTEXT_STRIP) as JComponent
            return collectLabels(strip).joinToString(" ")
        }

        val narrow = stripText(420)
        val medium = stripText(720)

        assertTrue("medium should show the file: $medium", medium.contains("RouteDetailsScreen.kt"))
        assertFalse("narrow should drop the file rather than clip it: $narrow", narrow.contains("RouteDetailsScreen"))
        // …but the facts that matter most survive, and nothing is half-rendered.
        assertTrue("narrow should keep the variant: $narrow", narrow.contains("demoStagingDebug"))
        assertTrue("narrow should keep the device: $narrow", narrow.contains("Pixel 8"))
    }

    /** A non-Android project must take no vertical space at all — not an empty frame. */
    fun testTheStripIsAbsentOutsideAnAndroidProject() {
        val p = panel()
        seedTranscript(p)
        p.setAndroidContextForPreview(AndroidContext.NOT_ANDROID)
        layoutTree(p.component, 720, 900)

        val strip = findByAccessibleName(p.component, A11yNames.ANDROID_CONTEXT_STRIP)
        assertNotNull(strip)
        assertFalse("the strip should hide itself entirely", strip!!.isVisible)
        assertEquals(0, strip.height)

        val chips = mutableListOf<Component>()
        walk(p.component) { if (it is ContextChip) chips += it }
        assertTrue("no context chips outside an Android project", chips.isEmpty())
    }

    /**
     * The end-to-end claim of M1: the facts on screen are the facts Claude receives, with their
     * provenance. Drives the real `ComposerModel.buildMessage`, so a regression in either the formatter
     * or the wiring fails here rather than silently sending a bare prompt.
     */
    fun testTheContextOnScreenIsWhatGetsSent() {
        val p = panel()
        p.setAndroidContextForPreview(androidContext())

        val message = p.buildMessageForPreview("why is the loading spinner stuck?")
        assertTrue(message.contains("<android-context>"))
        // The variant came from a build output, so it must arrive qualified — not as current truth.
        assertTrue("variant should be labelled stale: $message", message.contains("demoStagingDebug (last build)"))
        assertTrue(message.contains("Device: Pixel 8 (emulator-5554)"))
        assertTrue(message.contains("Application ID: com.example.driver.staging"))
        assertTrue(message.contains("Other modules: core"))
        assertTrue(message.endsWith("why is the loading spinner stuck?"))
    }

    private fun walk(c: Component, visit: (Component) -> Unit) {
        visit(c)
        if (c is Container) c.components.forEach { walk(it, visit) }
    }

    private fun findByAccessibleName(root: Component, name: String): Component? {
        var found: Component? = null
        walk(root) { c ->
            if (found == null && c is JComponent &&
                runCatching { c.getAccessibleContext()?.accessibleName }.getOrNull() == name
            ) {
                found = c
            }
        }
        return found
    }

    private fun collectLabels(root: Component): List<String> {
        val out = mutableListOf<String>()
        walk(root) { if (it is javax.swing.JLabel) it.text?.takeIf { t -> t.isNotBlank() }?.let(out::add) }
        return out
    }
}
