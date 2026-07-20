package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerModelTest {

    /**
     * Send depends on there being text, and **not** on being idle.
     *
     * This deliberately replaces the old `!running && …` rule: while a turn is in flight the message is
     * queued (see the queueing tests below) rather than rejected. Under the old rule the input was
     * still editable, so a user could type a whole message, press Enter, and get no response and no
     * explanation.
     */
    @Test fun sendDisabledOnlyWhenThereIsNoText() {
        val c = ComposerModel()
        assertFalse(c.sendEnabled(""))
        assertFalse(c.sendEnabled("   "))
        assertTrue(c.sendEnabled("hi"))
        c.running = true
        assertTrue("running no longer blocks submission — it queues", c.sendEnabled("hi"))
        assertFalse(c.sendEnabled(""))
    }

    @Test fun attachmentsAreDedupedAndOrdered() {
        val c = ComposerModel()
        assertTrue(c.addAttachment("a/B.kt"))
        assertFalse(c.addAttachment("a/B.kt"))
        c.addAttachment("c/D.kt")
        assertEquals(listOf("a/B.kt", "c/D.kt"), c.attachments)
    }

    @Test fun leadingAtIsStrippedFromAttachments() {
        val c = ComposerModel()
        c.addAttachment("@x/Y.kt")
        assertEquals(listOf("x/Y.kt"), c.attachments)
    }

    @Test fun buildMessagePrependsMentionsPreservingFormat() {
        val c = ComposerModel()
        c.addAttachment("a/B.kt")
        c.addAttachment("c/D.kt")
        assertEquals("@a/B.kt @c/D.kt\n\nhello", c.buildMessage("hello"))
    }

    @Test fun buildMessageWithoutAttachmentsIsPlain() {
        val c = ComposerModel()
        assertEquals("hi", c.buildMessage("  hi  "))
    }

    @Test fun removingAndClearingAttachments() {
        val c = ComposerModel()
        c.addAttachment("a/B.kt"); c.addAttachment("c/D.kt")
        assertTrue(c.removeAttachment("a/B.kt"))
        assertEquals("@c/D.kt\n\nhi", c.buildMessage("hi"))
        c.clearAttachments()
        assertEquals("hi", c.buildMessage("hi"))
    }

    // ---- queueing while a turn is running (M7) ----

    @Test fun idleSubmitSendsImmediately() {
        val m = ComposerModel()
        assertEquals(ComposerModel.Submit.SENT, m.submit("hello"))
        assertFalse(m.hasQueued)
    }

    /** The old behaviour: type a message, press Enter, nothing happens, no feedback. */
    @Test fun submitWhileRunningQueuesInsteadOfSilentlyDoingNothing() {
        val m = ComposerModel()
        m.running = true
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("next thing"))
        assertTrue(m.hasQueued)
        assertEquals(listOf("next thing"), m.queued)
    }

    @Test fun blankInputIsNeverQueued() {
        val m = ComposerModel()
        m.running = true
        assertEquals(ComposerModel.Submit.IGNORED_BLANK, m.submit("   "))
        assertFalse("an accidental Enter must not schedule an empty turn", m.hasQueued)
    }

    @Test fun queueDrainsInOrder() {
        val m = ComposerModel()
        m.running = true
        m.submit("first"); m.submit("second")
        assertEquals("first", m.takeQueued())
        assertEquals("second", m.takeQueued())
        assertNull(m.takeQueued())
    }

    @Test fun sendStaysEnabledWhileRunningSoTheMessageCanBeQueued() {
        val m = ComposerModel()
        m.running = true
        assertTrue(m.sendEnabled("something"))
        assertFalse(m.sendEnabled("  "))
    }

    /** The placeholder must say what Enter will actually do right now. */
    @Test fun placeholderReflectsQueueingState() {
        val m = ComposerModel()
        assertTrue(m.placeholder().contains("Ask Claude"))
        m.running = true
        assertTrue(m.placeholder().contains("Queue another"))
    }

    @Test fun queueLabelIsPluralisedAndEmptyWhenIdle() {
        val m = ComposerModel()
        assertEquals("", m.queueLabel())
        m.running = true
        m.submit("a")
        assertEquals("1 message queued", m.queueLabel())
        m.submit("b")
        assertEquals("2 messages queued", m.queueLabel())
    }
}
