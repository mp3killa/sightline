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
        I traced the failure UX problem to what the panel does with an error it can't fix.

        ## What changed

        - A failed turn renders as a card, not a red line of the CLI's own text
        - `UNKNOWN` invents nothing: the CLI's wording, and no explanation
        - [x] Retry withheld once the turn has run tools
        - [ ] Sign-in check relays `claude auth status` verbatim (Tier 2)

        | Failure | Explains | Offers |
        |---|---|:-:|
        | Not signed in | Yes | Copy command, check, retry |
        | Rate limited | Yes | Retry |
        | Unrecognised | No | Health check, copy |

        ```kotlin
        private fun retryableMessage(): String? {
            if (running || toolsRanThisTurn) return null
            return lastSentText?.takeIf { it.isNotBlank() }
        }
        ```

        > The error was already on screen. What was missing was anywhere to go from it.

        > [!WARNING]
        > A turn that already edited files must never offer a one-click replay.

        See `ui/state/SessionFailure.kt` for the pure logic.
    """.trimIndent()

    private fun quote(s: String): String = JsonPrimitive(s).toString()

    private fun seed(p: ClaudePanel) {
        fun feed(line: String) = p.renderProtocolLineForPreview(line)

        p.addUserMessageForPreview("A failed turn just shows a red line — give the user something to do.")

        feed("""{"type":"assistant","message":{"content":[{"type":"text","text":${quote(richMarkdown)}}]}}""")

        // A routine success — the case Tier 2 wants demoted to a compact row.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"src/main/kotlin/io/mp/sightline/ui/ClaudePanel.kt"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"1767 lines","is_error":false}]}}""")

        // A failure — must stay visually louder than the routine rows above.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"./gradlew verifyPlugin","description":"Verify plugin"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"FAILURE: Could not resolve idea:ideaIC:2025.3\nBUILD FAILED in 4s","is_error":true}]}}""")

        // An edit with a diff.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"src/main/kotlin/io/mp/sightline/ui/state/SessionFailure.kt","old_string":"fun classify(raw: String): Advice = when {","new_string":"fun classify(raw: String, exitCode: Int? = null, canRetry: Boolean = false): Advice {\n    val detail = raw.trim()"}}]}}""")
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
              {"question":"What should Retry do after a turn that already edited files?","header":"Retry",
               "options":[
                 {"label":"Withhold it","description":"Re-sending would replay those edits and commands"},
                 {"label":"Offer it with a warning","description":"The user decides, having been told"}]}]}}}"""
        )
        feed(
            """{"type":"control_request","request_id":"req-3","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"t6","input":{"questions":[
              {"question":"Which milestones should ship next?","header":"Milestones","multiSelect":true,
               "options":[
                 {"label":"Auth failures","description":"Sign in, check, retry"},
                 {"label":"Rate limits","description":"Retry once the limit resets"},
                 {"label":"Unrecognised errors","description":"The CLI's own words, verbatim"}]}]}}}"""
        )

        // A plan review — the real ExitPlanMode payload shape, so the plan card is the production one.
        feed(
            """{"type":"control_request","request_id":"req-plan","request":{"subtype":"can_use_tool",
            "tool_name":"ExitPlanMode","tool_use_id":"tp",
            "input":{"plan":${quote("## Add a --dry-run flag\n\n1. **Parse it** — add the flag to the argument parser, defaulting to off.\n2. **Thread it through** — pass it to every side-effecting call rather than reading a global.\n3. **Test the refusal** — assert the destructive path is not taken when it is set.")},"planFilePath":"/tmp/plan-dry-run.md"}}}"""
        )

        // Usage as the CLI really reports it (captured from 2.1.235), so the composer's context chip
        // renders from the production parse path rather than a stub.
        feed(
            """{"type":"result","result":"done","is_error":false,"num_turns":3,"duration_ms":51600,"total_cost_usd":0.404,
            "usage":{"input_tokens":10,"cache_creation_input_tokens":150,"cache_read_input_tokens":35181,"output_tokens":39},
            "modelUsage":{"claude-sonnet-5":{"inputTokens":10,"outputTokens":39,"contextWindow":200000}}}"""
        )

        // A failed turn — the actionable failure card, built by the production result path so the
        // buttons are the ones a real failure offers.
        feed("""{"type":"result","is_error":true,"result":"Failed to authenticate: OAuth session expired and could not be refreshed","duration_ms":540,"num_turns":1,"total_cost_usd":0.0}""")
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

        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        val w = 900
        val h = 2400
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
     * M6: a marathon session must not grow an unbounded component tree. Drives more turns than the
     * cap through the real event path and checks the oldest are actually released.
     */
    fun testLongSessionEvictsOldestTurns() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = false
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
            val p = ClaudePanel(project, testRootDisposable)
            seed(p)
            p.queueMessageForPreview("And then run the tests")
            val w = 900; val h = 1750
            p.component.preferredSize = Dimension(w, h)
            layoutTree(p.component, w, h)

            val labels = descendants(p.component).filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertTrue("the queue must be disclosed, got: $labels", labels.any { it.contains("queued", ignoreCase = true) })

            val out = File("build").apply { mkdirs() }.resolve("chat-gallery-queued.png")
            render(p.component, w, h, out)
            println("[chat-gallery] wrote ${'$'}{out.absolutePath}")
            assertTrue(out.length() > 5000)
        } finally { JBColor.setDark(false) }
    }

    /**
     * A follow-up submitted mid-turn is **folded into the running turn**, not parked until it ends: the
     * bubble joins the transcript captioned as having gone out mid-run, and the turn keeps running — no
     * new task, no queue card, nothing waiting. Renders `chat-gallery-interjected.png`.
     *
     * The disclosure is asserted as well as drawn. A message that reaches the agent mid-run reads oddly
     * in a transcript without it (an answer that suddenly changes direction, out of nowhere), and the
     * caption is the only thing that explains the order.
     */
    fun testInterjectsAMidTurnMessageIntoTheRunningTurn() {
        if (!applyTheme(dark = false)) return
        try {
            val settings = ClaudeSettings.getInstance().state
            settings.showDetails = true
            // The send path gates on the first-run disclosure; acknowledging it keeps this headless.
            // Restored below — it is a safety gate, and leaking an acknowledgement into another test
            // could hide a regression in the very thing that gate exists for.
            settings.firstRunAcknowledged = true
            val p = ClaudePanel(project, testRootDisposable)
            seed(p)
            p.interjectMessageForPreview("Actually — check the tests before you go further.")
            // What the agent says next must land in a new turn *below* the interjection, not appended to
            // the turn above it — the transcript's only claim is the order things happened in.
            p.renderProtocolLineForPreview(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Understood — running the tests first."}]}}"""
            )
            val w = 900; val h = 1750
            p.component.preferredSize = Dimension(w, h)
            layoutTree(p.component, w, h)

            val text = descendants(p.component).filterIsInstance<javax.swing.text.JTextComponent>()
                .map { it.text.orEmpty() }
            assertTrue(
                "the interjected message must appear in the transcript",
                text.any { it.contains("check the tests before you go further") },
            )
            assertTrue(
                "and be captioned as having gone out mid-run, got: $text",
                text.any { it.contains("Sent while Claude was working") },
            )
            val labels = descendants(p.component).filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertFalse(
                "nothing may be left queued — the message is already in flight, got: $labels",
                labels.any { it.contains("Queued:", ignoreCase = true) },
            )
            assertTrue("the turn must still be running", p.isRunningForTest())

            val bubbleAt = text.indexOfFirst { it.contains("check the tests before you go further") }
            val replyAt = text.indexOfFirst { it.contains("running the tests first") }
            assertTrue("the continuation must render, got: $text", replyAt >= 0)
            assertTrue(
                "output after an interjection belongs below it, not folded into the turn above",
                replyAt > bubbleAt,
            )

            val out = File("build").apply { mkdirs() }.resolve("chat-gallery-interjected.png")
            render(p.component, w, h, out)
            println("[chat-gallery] wrote ${'$'}{out.absolutePath}")
            assertTrue(out.length() > 5000)
        } finally {
            JBColor.setDark(false)
            ClaudeSettings.getInstance().state.firstRunAcknowledged = false
        }
    }

    /** Hover actions must exist but stay hidden until hover/focus, or the default view gets cluttered. */
    fun testHoverActionsExistButStartHidden() {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true
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
