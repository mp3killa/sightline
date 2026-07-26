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
     * interjected into it (or queued when nothing is listening — see the tests below) rather than
     * rejected. Under the old rule the input was still editable, so a user could type a whole message,
     * press Enter, and get no response and no explanation.
     */
    @Test fun sendDisabledOnlyWhenThereIsNoText() {
        val c = ComposerModel()
        assertFalse(c.sendEnabled(""))
        assertFalse(c.sendEnabled("   "))
        assertTrue(c.sendEnabled("hi"))
        c.running = true
        assertTrue("running no longer blocks submission — it interjects or queues", c.sendEnabled("hi"))
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

    // ---- mid-turn submit: interjection first, queueing as the fallback ----

    @Test fun idleSubmitSendsImmediately() {
        val m = ComposerModel()
        assertEquals(ComposerModel.Submit.SENT, m.submit("hello"))
        assertFalse(m.hasQueued)
    }

    /**
     * The point of a follow-up is that it lands *while* the agent is still on the task. With a live
     * session it is interjected — folded into the running turn by the CLI's streaming input — not parked
     * until the turn is over and the agent has moved on.
     */
    @Test fun submitWhileRunningInterjectsIntoTheLiveTurn() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { true }
        assertEquals(ComposerModel.Submit.INTERJECTED, m.submit("also update the tests"))
        assertFalse("an interjected message is in flight, not waiting", m.hasQueued)
        assertEquals("", m.queueLabel())
    }

    /**
     * An interjection leaves the pending images alone: the host reads and clears them as it sends. If the
     * model consumed them here too they would be attached twice — once to the wire, once to a queue entry
     * nobody drains.
     */
    @Test fun interjectionLeavesPendingImagesForTheHostToSend() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { true }
        m.addImage(EncodedImage(ImageAttachmentPolicy.MEDIA_PNG, ByteArray(100), 100, 50))
        assertEquals(ComposerModel.Submit.INTERJECTED, m.submit("look at this"))
        assertEquals(1, m.images.size)
    }

    /**
     * The fallback, and the reason [ComposerModel.canInterject] exists: during a Stop (or after an exit
     * the panel hasn't observed) there is no process reading stdin, so a write would lose the message
     * silently. It parks and goes out with the next turn instead.
     */
    @Test fun submitWhileRunningQueuesWhenNothingIsListening() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { false }
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("next thing"))
        assertTrue(m.hasQueued)
        assertEquals(listOf("next thing"), m.queued.map { it.text })
    }

    /** With no host wired, nothing is assumed to be listening — the conservative default. */
    @Test fun unwiredModelFallsBackToQueueing() {
        val m = ComposerModel()
        m.running = true
        assertEquals(ComposerModel.Submit.QUEUED, m.submit("next thing"))
    }

    @Test fun blankInputIsNeverInterjectedEither() {
        val m = ComposerModel()
        m.running = true
        m.canInterject = { true }
        assertEquals(ComposerModel.Submit.IGNORED_BLANK, m.submit("   "))
        assertFalse(m.hasQueued)
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

    /**
     * The placeholder must say what Enter will actually do right now — and the three outcomes are
     * genuinely different promises, so one wording cannot cover them.
     */
    @Test fun placeholderReflectsWhatEnterWillDo() {
        val m = ComposerModel()
        assertTrue(m.placeholder().contains("Ask Claude"))
        m.running = true
        m.canInterject = { true }
        assertTrue("mid-turn with a live session: it folds in", m.placeholder().contains("Add to what Claude is doing"))
        m.canInterject = { false }
        assertTrue("nothing listening: it waits for the next turn", m.placeholder().contains("Queue for the next turn"))
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

    @Test fun removeQueuedAtDropsTheRightMessage() {
        val m = ComposerModel()
        m.running = true
        m.submit("first"); m.submit("second"); m.submit("third")
        val removed = m.removeQueuedAt(1)
        assertEquals("second", removed?.text)
        assertEquals(listOf("first", "third"), m.queued.map { it.text })
    }

    @Test fun removeQueuedAtOutOfRangeIsNull() {
        val m = ComposerModel()
        m.running = true
        m.submit("only")
        assertNull(m.removeQueuedAt(5))
        assertEquals(1, m.queued.size)
    }

    @Test fun restoreImagesRefillsPendingUpToTheCap() {
        val m = ComposerModel()
        val imgs = (1..ImageAttachmentPolicy.MAX_IMAGES + 1).map {
            PendingImage("img-$it", it, EncodedImage(ImageAttachmentPolicy.MEDIA_PNG, byteArrayOf(it.toByte()), 1, 1))
        }
        m.restoreImages(imgs)
        assertEquals(ImageAttachmentPolicy.MAX_IMAGES, m.images.size)
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
