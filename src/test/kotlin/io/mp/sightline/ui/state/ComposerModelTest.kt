package io.mp.sightline.ui.state

import io.mp.sightline.android.ContextChipKind
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
        assertEquals(listOf("next thing"), m.queued.map { it.text })
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
        assertEquals("first", m.takeQueued()?.text)
        assertEquals("second", m.takeQueued()?.text)
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

    // ---- Android context injection (docs/ANDROID.md M1) ----

    @Test fun noAndroidContextByDefaultSoNothingChangesForANonAndroidProject() {
        val m = ComposerModel()
        assertEquals("hello", m.buildMessage("hello"))
    }

    @Test fun contextLeadsTheMessage() {
        val m = ComposerModel()
        m.androidContextBlock = { "<android-context>\nVariant: debug\n</android-context>" }
        assertEquals(
            "<android-context>\nVariant: debug\n</android-context>\n\nwhy is this failing?",
            m.buildMessage("why is this failing?"),
        )
    }

    @Test fun contextThenAttachmentsThenBody() {
        val m = ComposerModel()
        m.androidContextBlock = { "CTX" }
        m.addAttachment("app/Main.kt")
        assertEquals("CTX\n\n@app/Main.kt\n\nlook at this", m.buildMessage("look at this"))
    }

    /** Unchecking a chip must genuinely drop the fact, not merely hide a label describing it. */
    @Test fun disabledChipsAreNotPassedToTheSupplier() {
        val m = ComposerModel()
        var sawChips: Set<ContextChipKind>? = null
        m.androidContextBlock = { chips -> sawChips = chips; "CTX" }
        m.setChipEnabled(ContextChipKind.DEVICE, false)
        m.buildMessage("hi")
        assertFalse(ContextChipKind.DEVICE in sawChips!!)
        assertTrue(ContextChipKind.VARIANT in sawChips!!)
    }

    @Test fun removingEveryChipSuppressesTheBlockEntirely() {
        val m = ComposerModel()
        var called = false
        m.androidContextBlock = { called = true; "CTX" }
        ContextChipKind.entries.forEach { m.removeContextChip(it) }
        assertEquals("hi", m.buildMessage("hi"))
        assertFalse("the supplier should not even be consulted", called)
    }

    @Test fun chipsCanBeTurnedBackOn() {
        val m = ComposerModel()
        m.removeContextChip(ContextChipKind.DEVICE)
        assertFalse(m.isChipEnabled(ContextChipKind.DEVICE))
        m.setChipEnabled(ContextChipKind.DEVICE, true)
        assertTrue(m.isChipEnabled(ContextChipKind.DEVICE))
    }

    /**
     * The reason the block is a lambda rather than a stored string: a message typed before an emulator
     * booted is *sent* after it did, and must describe the device that exists then.
     */
    @Test fun contextIsGatheredAtSendTimeNotAtQueueTime() {
        val m = ComposerModel()
        var device = "none"
        m.androidContextBlock = { "Device: $device" }

        m.running = true
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("run the app"))

        device = "Pixel 8" // the emulator finished booting while the message waited
        m.running = false
        val drained = m.takeQueued()!!
        assertEquals("Device: Pixel 8\n\nrun the app", m.buildMessage(drained.text))
    }

    @Test fun aBlankBodyStillSendsItsContextAndAttachments() {
        val m = ComposerModel()
        m.androidContextBlock = { "CTX" }
        m.addAttachment("app/Main.kt")
        assertEquals("CTX\n\n@app/Main.kt", m.buildMessage("   "))
    }

    // ---- pasted images ----

    private fun encoded(bytes: Int = 100) =
        EncodedImage(ImageAttachmentPolicy.MEDIA_PNG, ByteArray(bytes), 100, 50)

    /** "Look at this" needs no prose: an image (or an attached file) alone is a sendable message. */
    @Test fun imageOrAttachmentAloneEnablesSend() {
        val m = ComposerModel()
        assertFalse(m.sendEnabled(""))
        m.addImage(encoded())
        assertTrue(m.sendEnabled(""))

        val n = ComposerModel()
        n.addAttachment("a/B.kt")
        assertTrue("the documented blank-body-still-sends contract, now enforced", n.sendEnabled(""))
    }

    @Test fun blankSubmitWithAnImageSendsRatherThanBeingIgnored() {
        val m = ComposerModel()
        m.addImage(encoded())
        assertEquals(ComposerModel.Submit.SENT, m.submit("   "))
    }

    /**
     * Images are content, frozen at Enter-time — unlike the Android context, which is framing and is
     * re-gathered at send time. A paste made while the entry waits belongs to the *next* message.
     */
    @Test fun queueCapturesImagesAtEnterTimeAndClearsThePendingSet() {
        val m = ComposerModel()
        m.running = true
        m.addImage(encoded())
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("first"))
        assertFalse("captured into the entry, no longer pending", m.hasImages)

        m.addImage(encoded()) // pasted while "first" waits
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("second"))

        assertEquals(1, m.takeQueued()!!.images.size)
        val second = m.takeQueued()!!
        assertEquals(1, second.images.size)
        assertEquals("the later paste rode the later message", 2, second.images[0].ordinal)
    }

    @Test fun imageLimitIsEnforcedWithATypedRefusal() {
        val m = ComposerModel()
        repeat(ImageAttachmentPolicy.MAX_IMAGES) {
            assertEquals(ImageAttachmentPolicy.AddImageResult.ADDED, m.addImage(encoded()))
        }
        assertEquals(ImageAttachmentPolicy.AddImageResult.REJECTED_LIMIT, m.addImage(encoded()))
        assertEquals(ImageAttachmentPolicy.MAX_IMAGES, m.images.size)
    }

    @Test fun oversizedImagesAreRefusedNotSilentlyDropped() {
        val m = ComposerModel()
        assertEquals(
            ImageAttachmentPolicy.AddImageResult.REJECTED_TOO_LARGE,
            m.addImage(encoded(bytes = ImageAttachmentPolicy.HARD_MAX_BYTES + 1)),
        )
        assertFalse(m.hasImages)
    }

    /** Removing "Image 1" must never rename "Image 2" — a chip that renames itself reads as a different attachment. */
    @Test fun ordinalsAreStableAcrossRemoval() {
        val m = ComposerModel()
        m.addImage(encoded()); m.addImage(encoded())
        assertTrue(m.removeImage("img-1"))
        m.addImage(encoded())
        assertEquals(listOf(2, 3), m.images.map { it.ordinal })
    }

    @Test fun takeImagesReadsThenClears() {
        val m = ComposerModel()
        m.addImage(encoded())
        assertEquals(1, m.takeImages().size)
        assertFalse(m.hasImages)
        assertTrue(m.takeImages().isEmpty())
    }
}
