package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextPane

/**
 * Live Markdown streaming: formatting must appear **while tokens arrive**, not at
 * `content_block_stop`. Drives the production event path with partial-message stream events and
 * asserts — *before* the block stop is delivered — that the transcript already shows rendered
 * Markdown components (a heading in its own block, a fenced code block with its Copy affordance),
 * not one plain pane holding raw markdown. Deltas after the first coalesce behind a wall-clock
 * timer, so tests flush the pending tick via the panel's deterministic seam instead of sleeping.
 */
class StreamingMarkdownRenderTest : BasePlatformTestCase() {

    private fun panel(): ClaudePanel = ClaudePanel(project, testRootDisposable)

    private fun feed(p: ClaudePanel, line: String) {
        p.renderProtocolLineForPreview(line)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun stream(p: ClaudePanel, event: String) = feed(p, """{"type":"stream_event","event":$event}""")

    private fun delta(p: ClaudePanel, text: String) =
        stream(p, """{"type":"content_block_delta","delta":{"type":"text_delta","text":${com.google.gson.JsonPrimitive(text)}}}""")

    private fun descendants(root: Component): List<Component> {
        val out = ArrayList<Component>()
        fun walk(c: Component) {
            out.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    private fun paneTexts(root: Component): List<String> =
        descendants(root).filterIsInstance<JTextPane>().map { it.document.getText(0, it.document.length) }

    fun testMarkdownRendersWhileStreamingNotOnlyAtBlockStop() {
        val p = panel()
        // A fresh panel shows the empty state; a user turn swaps the transcript in, as a real send does.
        p.addUserMessageForPreview("stream me some markdown")
        stream(p, """{"type":"message_start"}""")
        stream(p, """{"type":"content_block_start","content_block":{"type":"text"}}""")

        // The very first delta must render synchronously — formatted from the first tokens, no tick.
        delta(p, "## What I changed\n\nThe fix lives in ")
        assertTrue(
            "the heading should be a rendered block after the first delta, got: ${paneTexts(p.component)}",
            paneTexts(p.component).any { it.trim() == "What I changed" },
        )

        // Later deltas coalesce; flush the pending tick and the open fence must already be a code block.
        delta(p, "`installCenter`.\n\n```kotlin\nval a = 1\n")
        delta(p, "val b = 2\n")
        p.flushStreamingRenderForTest()
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(
            "the still-open code fence should already render as a code block (with Copy) mid-stream",
            descendants(p.component).filterIsInstance<JButton>().any { it.text == "Copy" },
        )
        assertFalse(
            "no pane should be showing the raw un-rendered markdown mid-stream",
            paneTexts(p.component).any { it.contains("## What I changed") },
        )

        // The stop lands the final full render (the links pass) without disturbing the content.
        delta(p, "```\n\nDone.")
        stream(p, """{"type":"content_block_stop"}""")
        val finalTexts = paneTexts(p.component)
        assertTrue("the finalized message keeps the heading", finalTexts.any { it.trim() == "What I changed" })
        assertTrue("the finalized message renders the closing prose", finalTexts.any { it.contains("Done.") })
    }

    /**
     * Rendered prose must be selectable with the mouse — simulated as real press/drag events on the
     * pane, not `select()`, because the claim under test is the *mouse* path a user actually takes.
     */
    fun testRenderedProseIsMouseSelectable() {
        val p = panel()
        p.addUserMessageForPreview("say something selectable")
        stream(p, """{"type":"message_start"}""")
        stream(p, """{"type":"content_block_start","content_block":{"type":"text"}}""")
        delta(p, "A plain sentence the user should be able to select with the cursor.")
        stream(p, """{"type":"content_block_stop"}""")

        p.component.setSize(700, 500)
        fun layAll(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach { layAll(it) } } }
        layAll(p.component)
        UIUtil.dispatchAllInvocationEvents()
        layAll(p.component)

        val pane = descendants(p.component).filterIsInstance<JTextPane>()
            .first { it.document.getText(0, it.document.length).contains("plain sentence") }
        assertTrue("the pane must have a real size to click in, got ${pane.size}", pane.width > 40 && pane.height > 5)
        assertEquals(
            "prose should advertise selectability with the text cursor",
            java.awt.Cursor.TEXT_CURSOR,
            pane.cursor.type,
        )

        val y = pane.height / 2
        fun mouse(id: Int, x: Int) = java.awt.event.MouseEvent(
            pane, id, System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
            x, y, 1, false, java.awt.event.MouseEvent.BUTTON1,
        )
        pane.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_PRESSED, 3))
        pane.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, pane.width - 5))
        pane.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_RELEASED, pane.width - 5))
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(
            "a mouse drag across the pane must produce a selection, got [${pane.selectionStart}, ${pane.selectionEnd}]",
            pane.selectionEnd > pane.selectionStart,
        )
    }

    /** Finished blocks keep their components across ticks — only the growing tail is rebuilt. */
    fun testFinishedBlocksAreNotRebuiltByLaterDeltas() {
        val p = panel()
        p.addUserMessageForPreview("stream me two paragraphs")
        stream(p, """{"type":"message_start"}""")
        stream(p, """{"type":"content_block_start","content_block":{"type":"text"}}""")
        delta(p, "First paragraph.\n\n")
        val firstPane = descendants(p.component).filterIsInstance<JTextPane>()
            .first { it.document.getText(0, it.document.length).contains("First paragraph.") }

        delta(p, "Second paragraph grows")
        p.flushStreamingRenderForTest()
        delta(p, " and grows.")
        p.flushStreamingRenderForTest()
        UIUtil.dispatchAllInvocationEvents()

        val after = descendants(p.component).filterIsInstance<JTextPane>()
            .first { it.document.getText(0, it.document.length).contains("First paragraph.") }
        assertSame(
            "the finished first paragraph must keep its component instance across later ticks",
            firstPane, after,
        )
        assertTrue(
            "the growing tail paragraph should have rendered too",
            paneTexts(p.component).any { it.contains("Second paragraph grows and grows.") },
        )
    }
}
