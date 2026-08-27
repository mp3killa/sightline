package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.settings.ClaudeSettings

/**
 * The checkpoint queue's one invariant: **it mirrors what was written to the CLI's stdin, in order.**
 *
 * Everything that can go wrong with "Revert Claude's file changes to here" goes wrong by breaking that.
 * The queue is matched head-first against replayed user messages, so an entry that is missing, extra,
 * or holding text the CLI will never echo back does not fail locally — it pops the *wrong* entry, and
 * an unrelated message silently loses its revert action with nothing on screen to say so.
 *
 * Two ways it was broken before this test existed:
 *  - the composer text was queued while the *built* message went on the wire, so the first message sent
 *    with an Android context chip or an `@path` attachment never matched and jammed everything after it;
 *  - interjections were not queued at all, even though they go out as an identical `user` line and are
 *    replayed like any other message.
 */
class CheckpointQueueTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        ClaudeSettings.getInstance().state.fileCheckpointing = true
        // The send path is gated on the first-run disclosure, which is a modal dialog and cannot open
        // headlessly. Every send here is about the queue, not about consent.
        ClaudeSettings.getInstance().state.firstRunAcknowledged = true
    }

    override fun tearDown() {
        try {
            ClaudeSettings.getInstance().state.fileCheckpointing = false
        } finally {
            super.tearDown()
        }
    }

    private fun panel(): ClaudePanel = ClaudePanel(project, testRootDisposable)

    fun `test an interjection is queued too, because the CLI replays it like any other message`() {
        val p = panel()
        p.interjectMessageForPreview("also check the tests")
        // Left unqueued, its replay arrives with nothing to match and pops another message's entry.
        assertEquals(1, p.awaitingCheckpointCountForTest())
    }

    fun `test the queue holds the built wire message, not the composer text`() {
        val p = panel()
        // buildMessage is what actually goes to stdin; if the queue held the raw text they would differ
        // the moment any context or attachment is in play, and nothing would ever match again.
        val typed = "run the tests"
        p.interjectMessageForPreview(typed)
        val onTheWire = p.buildMessageForPreview(typed)

        p.renderProtocolLineForPreview(
            """{"type":"user","uuid":"cp-1","message":{"role":"user","content":[{"type":"text","text":${jsonString(onTheWire)}}]}}""",
        )
        assertEquals("the replay of what we actually sent did not match the queued entry", 0, p.awaitingCheckpointCountForTest())
    }

    fun `test the CLI's own interrupt marker never consumes a queued checkpoint`() {
        val p = panel()
        p.interjectMessageForPreview("run the tests")
        assertEquals(1, p.awaitingCheckpointCountForTest())

        // Shaped exactly like a replay — same type, same uuid, text content — and emitted after a Stop.
        p.renderProtocolLineForPreview(
            """{"type":"user","uuid":"synthetic","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
        )
        assertEquals("the interrupt marker ate a real message's checkpoint", 1, p.awaitingCheckpointCountForTest())
    }

    fun `test a turn's tool output never consumes a queued checkpoint`() {
        val p = panel()
        p.interjectMessageForPreview("run the tests")
        // These arrive many times per turn on the same `user` stream.
        repeat(3) { i ->
            p.renderProtocolLineForPreview(
                """{"type":"user","uuid":"tr-$i","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_$i","content":"ok"}]}}""",
            )
        }
        assertEquals(1, p.awaitingCheckpointCountForTest())
    }

    fun `test the queue is bounded so a broken replay stream cannot pin the transcript in memory`() {
        val p = panel()
        repeat(60) { p.interjectMessageForPreview("message $it") }
        assertTrue(
            "queue grew past its bound: ${p.awaitingCheckpointCountForTest()}",
            p.awaitingCheckpointCountForTest() <= 32,
        )
    }

    fun `test nothing is queued when the feature is off`() {
        ClaudeSettings.getInstance().state.fileCheckpointing = false
        val p = panel()
        p.interjectMessageForPreview("run the tests")
        assertEquals(0, p.awaitingCheckpointCountForTest())
    }

    private fun jsonString(s: String): String =
        com.google.gson.JsonPrimitive(s).toString()
}
