package io.mp.sightline.ui

import com.google.gson.JsonPrimitive
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import io.mp.sightline.settings.ClaudeSettings
import io.mp.sightline.ui.state.TranscriptRetention
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Headless **component gallery**: renders one long transcript containing every block type the chat can
 * show — rich Markdown, routine/failed tool cards, an edit diff, an approval card and both
 * AskUserQuestion variants — to `build/chat-gallery-{light,dark}.png`.
 *
 * Everything is driven through the **production event path** (`renderProtocolLineForPreview` →
 * `handleEvent`), including `control_request`, so the approval and question blocks are the real ones a
 * live session builds — not hand-constructed lookalikes.
 *
 * This is what retires the "needs eyes" backlog items that are purely about *static rendering*
 * (does a table/callout/fence/approval card look right, in both themes). It does **not** cover
 * anything needing a click, hover, focus traversal, drag or a live CLI session — see BACKLOG.md.
 */
class ChatGalleryPreviewTest : BasePlatformTestCase() {

    private val richMarkdown = """
        I traced the layout problem to a guard that was never wired up.

        ## What changed

        - Wired `ResponsiveLayout.allowSplitDefault()` into the panel
        - Demoted a cramped `SPLIT` to **CHAT**, never to the graph
        - [x] Reading width measured from the chat column
        - [ ] Composer alignment inside SPLIT (Tier 2)

        | Width | Before | After |
        |---|---|:-:|
        | Narrow | Graph only | Conversation |
        | Medium | Split | Conversation |
        | Wide | Split | Split |

        ```kotlin
        fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =
            if (preferred == WorkspaceMode.SPLIT && !allowSplitDefault(profile)) {
                WorkspaceMode.CHAT
            } else {
                preferred
            }
        ```

        > The guard existed, was documented, and was tested — but nothing called it.

        > [!WARNING]
        > A transient narrow layout must never rewrite the persisted preference.

        See `ui/state/WorkspaceModes.kt` for the pure logic.
    """.trimIndent()

    private fun quote(s: String): String = JsonPrimitive(s).toString()

    private fun seed(p: ClaudePanel) {
        fun feed(line: String) = p.renderProtocolLineForPreview(line)

        p.addUserMessageForPreview("Stop the activity map from eating half the chat view.")

        feed("""{"type":"assistant","message":{"content":[{"type":"text","text":${quote(richMarkdown)}}]}}""")

        // A routine success — the case Tier 2 wants demoted to a compact row.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/ClaudePanel.kt"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"1767 lines","is_error":false}]}}""")

        // A failure — must stay visually louder than the routine rows above.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"./gradlew verifyPlugin","description":"Verify plugin"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"FAILURE: Could not resolve idea:ideaIC:2025.3\nBUILD FAILED in 4s","is_error":true}]}}""")

        // An edit with a diff.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/state/WorkspaceModes.kt","old_string":"fun toViewMode(mode: WorkspaceMode): String = when (mode) {","new_string":"fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =\n    if (preferred == WorkspaceMode.SPLIT) WorkspaceMode.CHAT else preferred"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t3","content":"Applied","is_error":false}]}}""")

        // A permission prompt — the real ApprovalBlock, via the control channel.
        feed(
            """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool",
            "tool_name":"Bash","tool_use_id":"t4","title":"Allow Bash?",
            "input":{"command":"git push origin main"},
            "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"git push *"}],"behavior":"allow","destination":"localSettings"}]}}"""
        )

        // Structured input: single-select with descriptions, then a multi-select.
        feed(
            """{"type":"control_request","request_id":"req-2","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"t5","input":{"questions":[
              {"question":"Which layout should be the default on a wide panel?","header":"Layout",
               "options":[
                 {"label":"Split","description":"Conversation and activity graph side by side"},
                 {"label":"Chat only","description":"Graph stays available on its own tab"}]}]}}}"""
        )
        feed(
            """{"type":"control_request","request_id":"req-3","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"t6","input":{"questions":[
              {"question":"Which milestones should ship next?","header":"Milestones","multiSelect":true,
               "options":[
                 {"label":"Compact tool rows","description":"M2"},
                 {"label":"File edit blocks","description":"M3"},
                 {"label":"Hover actions","description":"M4"}]}]}}}"""
        )
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

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) {
            if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } }
        }
        // Invalidate between passes: doLayout() alone leaves BoxLayout's cached child sizes in place,
        // so a wrapping JTextPane keeps the one-line height it reported before it had a width and the
        // image shows clipped text that is perfectly fine in a real hierarchy.
        fun invalidateAll(x: Component) {
            x.invalidate()
            if (x is Container) x.components.forEach { invalidateAll(it) }
        }
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        c.setSize(w, h); invalidateAll(c); walk(c)
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

    /**
     * Switch the platform's colour scheme. Both halves matter: `JBColor.setDark` picks the light/dark
     * side of every `JBColor` pair, while the editor scheme drives `ClaudeUiTokens.surface()` and the
     * code-fence highlighting. Setting only the former leaves a dark surface under light text.
     *
     * Returns false when no matching scheme is registered in the test platform, so the caller can skip
     * rather than silently render the wrong theme and call it verified.
     */
    private fun applyTheme(dark: Boolean): Boolean {
        val mgr = EditorColorsManager.getInstance()
        val wanted = if (dark) {
            mgr.allSchemes.firstOrNull { it.name.contains("Darcula", true) || it.name.contains("Dark", true) }
        } else {
            mgr.allSchemes.firstOrNull {
                !it.name.contains("Darcula", true) && !it.name.contains("Dark", true) &&
                    !it.name.contains("High contrast", true)
            }
        } ?: return false
        mgr.setGlobalScheme(wanted)
        JBColor.setDark(dark)
        // Guard against "I set the flag but the surface is still the other theme" — the exact trap
        // that made the first light render come out dark.
        val bg = io.mp.sightline.theme.ClaudeUiTokens.surface()
        val luminance = (bg.red * 0.299 + bg.green * 0.587 + bg.blue * 0.114) / 255.0
        return if (dark) luminance < 0.5 else luminance > 0.5
    }

    private fun renderGallery(name: String, details: Boolean = true) {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = details       // tool cards visible — their weight is the point
        settings.showActivityMap = false     // gallery is about the conversation column
        settings.activityViewMode = "chat"

        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        val w = 900
        val h = 1750
        p.component.preferredSize = Dimension(w, h)
        layoutTree(p.component, w, h)

        val out = File("build").apply { mkdirs() }.resolve("chat-gallery-$name.png")
        render(p.component, w, h, out)
        println("[chat-gallery] wrote ${out.absolutePath}")
        assertTrue("gallery not written for $name", out.length() > 5000)
    }

    fun testRendersTheGalleryInLightTheme() {
        if (!applyTheme(dark = false)) {
            println("[chat-gallery] no light scheme resolved in the test platform — skipping light render")
            return
        }
        try { renderGallery("light") } finally { JBColor.setDark(false) }
    }

    fun testRendersTheGalleryInDarkTheme() {
        if (!applyTheme(dark = true)) {
            println("[chat-gallery] no dark scheme resolved in the test platform — skipping dark render")
            return
        }
        try { renderGallery("dark") } finally { JBColor.setDark(false) }
    }

    /**
     * Details **off** — the shipped default. Every tool card is hidden, so this is the view most users
     * actually see, and the one where the [ProcessingSummary] row has to carry what happened.
     */
    fun testRendersTheCompactGalleryWithDetailsOff() {
        if (!applyTheme(dark = false)) return
        try { renderGallery("compact", details = false) } finally { JBColor.setDark(false) }
    }

    /**
     * M5: selecting a node in the activity map must reveal the transcript row that produced it.
     * Drives the real callback rather than a stub, so a broken wiring fails here.
     */
    fun testMapSelectionRevealsTheOriginatingTranscriptRow() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = false // deliberately off: revealing must turn details on itself
        settings.showActivityMap = true
        settings.activityViewMode = "split"
        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        layoutTree(p.component, 1400, 900)

        val edited = "src/main/kotlin/io/mp/claudecodepanel/ui/state/WorkspaceModes.kt"
        assertTrue(
            "the edited file should have a node to select",
            p.selectActivityNodeByPathForTest(edited),
        )
        assertTrue(
            "selecting a node must reveal the hidden transcript row, not silently do nothing",
            ClaudeSettings.getInstance().state.showDetails,
        )
    }

    /**
     * M6: a marathon session must not grow an unbounded component tree. Drives more turns than the
     * cap through the real event path and checks the oldest are actually released.
     */
    fun testLongSessionEvictsOldestTurns() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = false
        settings.showActivityMap = false
        settings.activityViewMode = "chat"
        val p = ClaudePanel(project, testRootDisposable)

        val overshoot = 12
        repeat(TranscriptRetention.MAX_TURNS + overshoot) { i ->
            p.addUserMessageForPreview("turn $i")
            p.renderProtocolLineForPreview(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"reply $i"}]}}"""
            )
            p.renderProtocolLineForPreview("""{"type":"result","result":"ok","is_error":false}""")
        }
        layoutTree(p.component, 900, 900)

        assertEquals(
            "the transcript must stay capped",
            TranscriptRetention.MAX_TURNS,
            p.liveTurnCountForTest(),
        )
        // And the user is told, in wording that doesn't promise the turns can come back.
        val notice = descendants(p.component).filterIsInstance<javax.swing.JLabel>()
            .map { it.text.orEmpty() }
            .firstOrNull { it.contains("earlier turn") }
        assertNotNull("eviction must be disclosed, not silent", notice)
        assertFalse("must not imply the turns are recoverable", notice!!.contains("load", ignoreCase = true))
    }

    /**
     * M7: with a turn in flight the composer must say Enter will *queue*, and show what is waiting.
     * Renders `chat-gallery-queued.png` so that state can actually be looked at.
     */
    fun testRendersTheQueuedComposerState() {
        if (!applyTheme(dark = false)) return
        try {
            val settings = ClaudeSettings.getInstance().state
            settings.showDetails = true
            settings.showActivityMap = false
            settings.activityViewMode = "chat"
            val p = ClaudePanel(project, testRootDisposable)
            seed(p)
            p.queueMessageForPreview("And then run the tests")
            val w = 900; val h = 1750
            p.component.preferredSize = Dimension(w, h)
            layoutTree(p.component, w, h)

            val labels = descendants(p.component).filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertTrue("the queue must be disclosed, got: $labels", labels.any { it.contains("queued") })

            val out = File("build").apply { mkdirs() }.resolve("chat-gallery-queued.png")
            render(p.component, w, h, out)
            println("[chat-gallery] wrote ${'$'}{out.absolutePath}")
            assertTrue(out.length() > 5000)
        } finally { JBColor.setDark(false) }
    }

    /** Hover actions must exist but stay hidden until hover/focus, or the default view gets cluttered. */
    fun testHoverActionsExistButStartHidden() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
        settings.showActivityMap = false
        settings.activityViewMode = "chat"
        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        layoutTree(p.component, 900, 1750)

        // A button's own isVisible stays true inside a hidden row, so check the whole ancestor chain.
        fun effectivelyVisible(c: Component): Boolean {
            var cur: Component? = c
            while (cur != null && cur !== p.component) {
                if (!cur.isVisible) return false
                cur = cur.parent
            }
            return true
        }
        fun buttons(text: String) = descendants(p.component)
            .filterIsInstance<javax.swing.JButton>().filter { it.text == text }

        // "Copy command"/"Copy output" are unambiguous — they only exist as hover actions.
        for (label in listOf("Copy command", "Copy output")) {
            val bs = buttons(label)
            assertTrue("expected a '$label' hover action to be constructed", bs.isNotEmpty())
            bs.forEach { assertFalse("'$label' must stay hidden until hover or focus", effectivelyVisible(it)) }
        }
        // The code fence's own Copy is deliberately always visible — it is not a hover action, and
        // this is the distinction the first version of this test got wrong.
        assertTrue(
            "a code fence keeps its always-visible Copy",
            buttons("Copy").any { effectivelyVisible(it) },
        )
        // An edit's Open file / Copy diff are likewise deliberate, always-on affordances.
        assertTrue("the edit block keeps its actions visible", buttons("Copy diff").any { effectivelyVisible(it) })
    }

    /**
     * Regression: streaming content must not silently cancel auto-follow.
     *
     * `scrollToBottomSoon` used to jump to `bar.maximum` right after `revalidate()`, but revalidate only
     * *schedules* layout — the scrollbar still held the pre-growth maximum, so the jump landed short of
     * the real bottom and the adjustment listener read that gap as the user scrolling up. Follow died
     * and "Jump to latest" appeared mid-stream without anyone touching the scrollbar.
     */
    fun testStreamingKeepsAutoFollowWithoutUserScrolling() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
        settings.showActivityMap = false
        settings.activityViewMode = "chat"
        val p = ClaudePanel(project, testRootDisposable)
        layoutTree(p.component, 900, 600)
        assertTrue("follow starts armed", p.isFollowingForTest())

        // Enough content to overflow the viewport several times over.
        repeat(30) { i ->
            p.addUserMessageForPreview("turn $i")
            p.renderProtocolLineForPreview(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"a fairly long reply line number $i that will wrap and grow the transcript"}]}}"""
            )
            p.scrollToBottomForTest()
            UIUtil.dispatchAllInvocationEvents()
            layoutTree(p.component, 900, 600)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(
            "nobody scrolled — auto-follow must still be armed after streaming",
            p.isFollowingForTest(),
        )
    }

    /**
     * Regression: inline actions must render their text.
     *
     * They were JButtons carrying `JButton.buttonType = "square"`, which on the real macOS IDE LaF
     * forces a fixed square with no room for a label — they showed as two empty boxes. The headless
     * preview's LaF ignores that property, so the image looked fine; only the live IDE revealed it.
     * Guard the invariant directly: no transcript control may carry that property, and every action
     * control must have a label.
     */
    fun testInlineActionsCarryNoFixedSizeButtonTypeAndHaveLabels() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
        settings.showActivityMap = false
        settings.activityViewMode = "chat"
        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        layoutTree(p.component, 900, 1750)

        val actionLabels = setOf("Open file", "Copy diff", "Copy", "Copy command", "Copy output", "Show in map")
        val controls = descendants(p.component).filterIsInstance<javax.swing.AbstractButton>()
        val actions = controls.filter { it.text in actionLabels }
        assertTrue("expected inline actions to exist", actions.isNotEmpty())
        actions.forEach {
            assertTrue("'${it.text}' must keep a visible label", it.text.isNotBlank())
            assertNull(
                "'${it.text}' must not force a fixed-size button type — that is what blanked them in the IDE",
                (it as javax.swing.JComponent).getClientProperty("JButton.buttonType"),
            )
        }
    }

    /**
     * The other half of the follow rule: a real user scroll must still pause following, **even while
     * content is arriving**. The re-pin fix above keys on "the maximum moved but the value didn't"
     * precisely so that a user scrolling mid-stream isn't dragged back to the bottom.
     */
    fun testUserScrollingUpStillPausesFollowEvenWhileStreaming() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
        settings.showActivityMap = false
        settings.activityViewMode = "chat"
        val p = ClaudePanel(project, testRootDisposable)
        repeat(20) { i ->
            p.addUserMessageForPreview("turn $i")
            p.renderProtocolLineForPreview(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"reply $i with enough text to grow the transcript past the viewport"}]}}"""
            )
        }
        layoutTree(p.component, 900, 400)
        UIUtil.dispatchAllInvocationEvents()

        p.scrollUpForTest()
        UIUtil.dispatchAllInvocationEvents()
        assertFalse("scrolling up must pause follow", p.isFollowingForTest())

        // More content arrives while the user is reading up-thread — they must not be yanked back.
        p.addUserMessageForPreview("another turn")
        p.renderProtocolLineForPreview(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"more content arriving"}]}}"""
        )
        layoutTree(p.component, 900, 400)
        UIUtil.dispatchAllInvocationEvents()
        assertFalse("content arriving must not re-arm follow behind the user's back", p.isFollowingForTest())
    }
}
