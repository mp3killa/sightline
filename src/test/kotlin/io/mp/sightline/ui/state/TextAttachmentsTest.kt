package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAttachmentsTest {

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    // ---- threshold ----

    @Test
    fun `ordinary paste stays in the input box`() {
        assertFalse(TextAttachmentPolicy.shouldAttach("fix the null check in the parser"))
        assertFalse(TextAttachmentPolicy.shouldAttach(lines(TextAttachmentPolicy.MAX_INLINE_LINES)))
    }

    @Test
    fun `a tall paste attaches`() {
        assertTrue(TextAttachmentPolicy.shouldAttach(lines(TextAttachmentPolicy.MAX_INLINE_LINES + 1)))
    }

    @Test
    fun `a single enormous line attaches even with no line count to show for it`() {
        val minified = "x".repeat(TextAttachmentPolicy.MAX_INLINE_CHARS + 1)
        assertTrue(TextAttachmentPolicy.shouldAttach(minified))
        assertEquals(1, TextAttachmentPolicy.lineCount(minified))
    }

    @Test
    fun `blank text is never attached — there is nothing to hold`() {
        assertFalse(TextAttachmentPolicy.shouldAttach("\n".repeat(200)))
        assertFalse(TextAttachmentPolicy.shouldAttach(" ".repeat(9000)))
    }

    @Test
    fun `a trailing newline does not invent a last line`() {
        assertEquals(3, TextAttachmentPolicy.lineCount("a\nb\nc"))
        assertEquals(3, TextAttachmentPolicy.lineCount("a\nb\nc\n"))
        assertEquals(0, TextAttachmentPolicy.lineCount(""))
    }

    // ---- labels ----

    @Test
    fun `chip names the block and states its size separately`() {
        val t = PendingText("txt-2", 2, lines(412))
        assertEquals("Pasted text 2", TextAttachmentPolicy.chipLabel(t))
        assertEquals("412 lines", TextAttachmentPolicy.chipDetail(t))
    }

    @Test
    fun `a one-line block is measured in characters, where its size actually is`() {
        val t = PendingText("txt-1", 1, "y".repeat(4120))
        assertEquals("4,120 chars", TextAttachmentPolicy.chipDetail(t))
    }

    @Test
    fun `tooltip previews the opening without reproducing the block`() {
        val t = PendingText("txt-1", 1, lines(400))
        val tip = TextAttachmentPolicy.tooltip(t)
        assertTrue(tip.contains("line 1"))
        assertTrue(tip.contains("more lines"))
        assertFalse(tip.contains("line 400"))
    }

    @Test
    fun `the notice says what became of the paste and that it will still be sent`() {
        val notice = TextAttachmentPolicy.attachedNotice(PendingText("txt-1", 1, lines(50)))
        assertTrue(notice.contains("Pasted text 1"))
        assertTrue(notice.contains("50 lines"))
        assertTrue(notice.contains("next message"))
    }

    @Test
    fun `the too-large refusal names the size, the limit and what to do instead`() {
        val msg = TextAttachmentPolicy.tooLargeMessage(900_000)
        assertTrue(msg.contains("900,000"))
        assertTrue(msg.contains(TextAttachmentPolicy.formatCount(TextAttachmentPolicy.HARD_MAX_CHARS)))
        assertTrue(msg.contains("attach the file"))
    }

    @Test
    fun `the command notice says the blocks are kept, not lost`() {
        assertTrue(TextAttachmentPolicy.droppedByCommandMessage(1).contains("still attached"))
        assertTrue(TextAttachmentPolicy.droppedByCommandMessage(3).contains("All 3"))
    }

    // ---- the fence ----

    @Test
    fun `plain text gets the ordinary three-backtick fence`() {
        assertEquals("```", TextAttachmentPolicy.fenceFor("hello\nworld"))
    }

    @Test
    fun `a pasted markdown document cannot close its own fence`() {
        val doc = "# Notes\n\n```kotlin\nval x = 1\n```\n\nmore prose"
        val fence = TextAttachmentPolicy.fenceFor(doc)
        assertEquals("````", fence)
        val block = TextAttachmentPolicy.promptBlock(PendingText("txt-1", 1, doc))
        assertTrue(block.startsWith("Pasted text 1:\n````\n"))
        assertTrue(block.endsWith("\n````"))
        // The inner fence survives as content rather than terminating the outer one.
        assertTrue(block.contains("```kotlin"))
    }

    @Test
    fun `a paste already containing a four-backtick run is outgrown too`() {
        assertEquals("`````", TextAttachmentPolicy.fenceFor("a\n````\nb"))
    }

    @Test
    fun `promptBlock does not double the trailing newline`() {
        val block = TextAttachmentPolicy.promptBlock(PendingText("txt-1", 1, "a\nb\n"))
        assertEquals("Pasted text 1:\n```\na\nb\n```", block)
    }

    @Test
    fun `counts are grouped so a large number is not misread`() {
        assertEquals("412", TextAttachmentPolicy.formatCount(412))
        assertEquals("4,120", TextAttachmentPolicy.formatCount(4120))
        assertEquals("400,000", TextAttachmentPolicy.formatCount(400_000))
    }

    // ---- the composer model ----

    @Test
    fun `an attached block rides after the prompt, not before it`() {
        val m = ComposerModel()
        m.addText(lines(40))
        val msg = m.buildMessage("why does this fail?")
        assertTrue(msg.startsWith("why does this fail?"))
        assertTrue(msg.contains("Pasted text 1:"))
    }

    @Test
    fun `ordinals never renumber when an earlier block is removed`() {
        val m = ComposerModel()
        m.addText(lines(40)); m.addText(lines(41))
        m.removeText("txt-1")
        assertEquals(listOf(2), m.texts.map { it.ordinal })
        m.addText(lines(42))
        assertEquals(listOf(2, 3), m.texts.map { it.ordinal })
    }

    @Test
    fun `the per-message cap is refused rather than silently ignored`() {
        val m = ComposerModel()
        repeat(TextAttachmentPolicy.MAX_TEXTS) {
            assertEquals(TextAttachmentPolicy.AddTextResult.ADDED, m.addText(lines(40)))
        }
        assertEquals(TextAttachmentPolicy.AddTextResult.REJECTED_LIMIT, m.addText(lines(40)))
    }

    @Test
    fun `a block past the hard cap is refused, never truncated`() {
        val m = ComposerModel()
        val huge = "z".repeat(TextAttachmentPolicy.HARD_MAX_CHARS + 1)
        assertEquals(TextAttachmentPolicy.AddTextResult.REJECTED_TOO_LARGE, m.addText(huge))
        assertTrue(m.texts.isEmpty())
    }

    @Test
    fun `a pasted block alone is a sendable message`() {
        val m = ComposerModel()
        m.addText(lines(40))
        assertTrue(m.sendEnabled(""))
        assertEquals(ComposerModel.Submit.SENT, m.submit(""))
    }

    @Test
    fun `a slash command would drop its blocks, and the model says so`() {
        val m = ComposerModel()
        m.addText(lines(40))
        assertTrue(m.commandWouldDropTexts("/context"))
        assertFalse(m.commandWouldDropTexts("look at /usr/local/bin"))
        // The command still goes out alone — that is what makes it execute as a command.
        assertEquals("/context", m.buildMessage("/context"))
    }

    @Test
    fun `queuing freezes the blocks, and Edit restores them as attachments`() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { false }
        m.addText(lines(40))
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("check this"))
        assertTrue("the queued entry owns them now", m.texts.isEmpty())
        val queued = m.queued.single()
        assertEquals(1, queued.texts.size)

        val pulled = m.removeQueuedAt(0)!!
        m.restoreTexts(pulled.texts)
        assertEquals(listOf(1), m.texts.map { it.ordinal })
    }

    @Test
    fun `a block pasted while a message waits belongs to the next message`() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { false }
        m.addText(lines(40))
        m.submit("first")
        m.addText(lines(41))
        assertEquals(listOf(2), m.texts.map { it.ordinal })
        assertEquals(listOf(1), m.queued.single().texts.map { it.ordinal })
    }

    @Test
    fun `an explicit block list overrides the pending set — the queue-drain path`() {
        val m = ComposerModel()
        val carried = listOf(PendingText("txt-9", 9, lines(40)))
        val msg = m.buildMessage("drained", carried)
        assertTrue(msg.contains("Pasted text 9:"))
    }
}
