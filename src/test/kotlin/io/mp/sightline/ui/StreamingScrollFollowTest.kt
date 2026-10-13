package io.mp.sightline.ui

import com.google.gson.JsonPrimitive
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container

/**
 * Scrolling back to read **while a reply is streaming**.
 *
 * [ChatGalleryPreviewTest.testUserScrollingUpStillPausesFollowEvenWhileStreaming] already covers the
 * case where whole messages arrive, and it passes. This covers the one that does not: *token deltas*,
 * which take a different path — a live render tick that lays the transcript out and re-pins the
 * scrollbar synchronously, several times a second, rather than the deferred scroll whole messages use.
 *
 * The distinction matters because the re-pin brackets its own scrollbar write with a
 * "this is us, not the user" flag. Every adjustment event raised while that flag is up is discarded,
 * and a wheel gesture mid-stream lands inside that window often enough to be the normal case rather
 * than the unlucky one — so the user's scroll was dropped and the next tick dragged them back down.
 */
class StreamingScrollFollowTest : BasePlatformTestCase() {

    private fun feed(p: ClaudePanel, line: String) {
        p.renderProtocolLineForPreview(line)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun stream(p: ClaudePanel, event: String) = feed(p, """{"type":"stream_event","event":$event}""")

    private fun delta(p: ClaudePanel, text: String) =
        stream(p, """{"type":"content_block_delta","delta":{"type":"text_delta","text":${JsonPrimitive(text)}}}""")

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) { if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } } }
        fun invalidateAll(x: Component) { x.invalidate(); if (x is Container) x.components.forEach { invalidateAll(it) } }
        c.preferredSize = java.awt.Dimension(w, h)
        repeat(3) { c.setSize(w, h); invalidateAll(c); walk(c) }
    }

    /** A paragraph long enough that a handful of them overflow a 400px viewport. */
    private fun paragraph(i: Int) =
        "Paragraph $i. " + "Enough prose to wrap across several lines and grow the transcript well " +
            "past the height of the viewport so there is something to scroll back through. ".repeat(3) + "\n\n"

    private fun seedStreamingTurn(p: ClaudePanel) {
        p.addUserMessageForPreview("explain this at length")
        stream(p, """{"type":"content_block_start","content_block":{"type":"text","text":""}}""")
        repeat(12) { i ->
            delta(p, paragraph(i))
            p.flushStreamingRenderForTest()
            layoutTree(p.component, 900, 400)
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    /**
     * The reported bug: scroll up mid-stream and the next token drags you back to the bottom.
     */
    fun testScrollingUpMidStreamPausesFollow() {
        val p = ClaudePanel(project, testRootDisposable)
        layoutTree(p.component, 900, 400)
        seedStreamingTurn(p)
        assertTrue("follow should still be armed before the user touches anything", p.isFollowingForTest())

        p.scrollUpForTest()
        UIUtil.dispatchAllInvocationEvents()
        assertFalse("scrolling up mid-stream must pause follow", p.isFollowingForTest())

        // The stream continues. The reader must stay where they were.
        val whereTheUserWasReading = p.scrollValueForTest()
        repeat(6) { i ->
            delta(p, paragraph(100 + i))
            p.flushStreamingRenderForTest()
            layoutTree(p.component, 900, 400)
            UIUtil.dispatchAllInvocationEvents()
        }
        assertFalse("streaming must not re-arm follow behind the user's back", p.isFollowingForTest())
        assertEquals(
            "the viewport must not move while the user is reading up-thread",
            whereTheUserWasReading,
            p.scrollValueForTest(),
        )
    }

    /**
     * The gesture has to survive landing *during* a render tick. This is the actual failure mode:
     * the scroll arrives while the panel is between re-pinning the scrollbar and clearing the flag
     * that says the move was its own.
     */
    fun testAScrollThatLandsDuringARenderTickIsNotDiscarded() {
        val p = ClaudePanel(project, testRootDisposable)
        layoutTree(p.component, 900, 400)
        seedStreamingTurn(p)

        // Deliver the delta but do NOT drain the event queue: the re-pin has run and the runnable
        // that clears the flag is still sitting in the queue, which is exactly where a real wheel
        // event lands during a fast stream.
        delta(p, paragraph(200))
        p.flushStreamingRenderForTest()
        p.scrollUpDuringRepinForTest()
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(
            "a scroll landing inside the re-pin window is still the user scrolling",
            p.isFollowingForTest(),
        )
        assertTrue("and it must offer the way back", p.isJumpToLatestVisibleForTest())

        // And the stream that continues afterwards must leave them where they are.
        val whereTheUserWasReading = p.scrollValueForTest()
        repeat(4) { i ->
            delta(p, paragraph(300 + i))
            p.flushStreamingRenderForTest()
            layoutTree(p.component, 900, 400)
            UIUtil.dispatchAllInvocationEvents()
        }
        assertFalse(p.isFollowingForTest())
        assertEquals(whereTheUserWasReading, p.scrollValueForTest())
    }

    /** Having paused, the affordance back to the live end must be offered. */
    fun testJumpToLatestIsOfferedAndRestoresFollow() {
        val p = ClaudePanel(project, testRootDisposable)
        layoutTree(p.component, 900, 400)
        seedStreamingTurn(p)

        p.scrollUpForTest()
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(p.isFollowingForTest())
        assertTrue("paused follow must offer the way back", p.isJumpToLatestVisibleForTest())

        p.jumpToLatestForTest()
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("Jump to latest must re-arm follow", p.isFollowingForTest())
        assertFalse("and then stop offering itself", p.isJumpToLatestVisibleForTest())
    }
}
