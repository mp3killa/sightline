package io.mp.claudecodepanel.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerModelTest {

    @Test fun sendDisabledWhenEmptyOrRunning() {
        val c = ComposerModel()
        assertFalse(c.sendEnabled(""))
        assertFalse(c.sendEnabled("   "))
        assertTrue(c.sendEnabled("hi"))
        c.running = true
        assertFalse(c.sendEnabled("hi"))
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
}
