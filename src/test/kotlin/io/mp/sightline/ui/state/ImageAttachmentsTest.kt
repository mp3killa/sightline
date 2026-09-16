package io.mp.sightline.ui.state

import io.mp.sightline.ui.state.PasteRouting.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAttachmentsTest {

    private fun pending(
        bytes: Int = 1000,
        w: Int = 100,
        h: Int = 50,
        srcW: Int = w,
        srcH: Int = h,
        mediaType: String = ImageAttachmentPolicy.MEDIA_PNG,
        ordinal: Int = 1,
    ) = PendingImage("img-$ordinal", ordinal, EncodedImage(mediaType, ByteArray(bytes), w, h, srcW, srcH))

    // ---- scaling ----

    @Test fun imagesWithinTheEdgeLimitAreNeverUpscaled() {
        assertEquals(1.0, ImageAttachmentPolicy.scaleFactor(100, 50), 0.0)
        assertEquals(1.0, ImageAttachmentPolicy.scaleFactor(ImageAttachmentPolicy.MAX_EDGE, 10), 0.0)
    }

    @Test fun oversizedImagesScaleByTheirLongestEdge() {
        val f = ImageAttachmentPolicy.scaleFactor(ImageAttachmentPolicy.MAX_EDGE * 2, 100)
        assertEquals(0.5, f, 1e-9)
        // Portrait orientation uses the same rule on the other axis.
        assertEquals(0.5, ImageAttachmentPolicy.scaleFactor(100, ImageAttachmentPolicy.MAX_EDGE * 2), 1e-9)
    }

    // ---- labels ----

    @Test fun byteFormattingPicksTheReadableUnit() {
        assertEquals("980 B", ImageAttachmentPolicy.formatBytes(980))
        assertEquals("214 KB", ImageAttachmentPolicy.formatBytes(214 * 1024))
        assertEquals("1.3 MB", ImageAttachmentPolicy.formatBytes((1.3 * 1024 * 1024).toInt()))
    }

    @Test fun chipLabelCarriesOrdinalAndSize() {
        assertEquals("Image 3 · 2 KB", ImageAttachmentPolicy.chipLabel(pending(bytes = 2048, ordinal = 3)))
    }

    /** The user should know the model sees 2576px, not their 5120px original. */
    @Test fun tooltipStatesADownscaleAndOnlyADownscale() {
        val scaled = ImageAttachmentPolicy.tooltip(pending(w = 2576, h = 1440, srcW = 5152, srcH = 2880))
        assertTrue(scaled, scaled.contains("2576×1440"))
        assertTrue(scaled, scaled.contains("scaled from 5152×2880"))

        val unscaled = ImageAttachmentPolicy.tooltip(pending(w = 800, h = 600))
        assertTrue(unscaled, !unscaled.contains("scaled"))
    }

    @Test fun tooltipNamesTheFormatActuallySent() {
        assertTrue(ImageAttachmentPolicy.tooltip(pending()).contains("PNG"))
        assertTrue(ImageAttachmentPolicy.tooltip(pending(mediaType = ImageAttachmentPolicy.MEDIA_JPEG)).contains("JPEG"))
    }

    @Test fun refusalMessagesStateTheLimitTheyEnforce() {
        assertTrue(ImageAttachmentPolicy.limitMessage().contains("${ImageAttachmentPolicy.MAX_IMAGES}"))
        val tooLarge = ImageAttachmentPolicy.tooLargeMessage(6 * 1024 * 1024)
        assertTrue(tooLarge, tooLarge.contains("6 MB") || tooLarge.contains("6.0 MB"))
    }

    // ---- paste routing ----

    /** A copied file also exposes its path as text; pasting that path as characters is never the intent. */
    @Test fun filesWinOverEverything() {
        assertEquals(Route.FILES, PasteRouting.route(hasFiles = true, hasText = true, textIsBlank = false, hasImage = true))
    }

    /**
     * The Excel case: cells arrive as text *and* a picture of the text. A text box's first job is
     * text — attaching a surprise screenshot while discarding the copied characters is the worse
     * failure.
     */
    @Test fun nonBlankTextBeatsAnAccompanyingImage() {
        assertEquals(Route.TEXT, PasteRouting.route(hasFiles = false, hasText = true, textIsBlank = false, hasImage = true))
    }

    /** The screenshot case: macOS screenshots and "Copy Image" put no plain text on the clipboard. */
    @Test fun imageWinsWhenThereIsNoUsableText() {
        assertEquals(Route.IMAGE, PasteRouting.route(hasFiles = false, hasText = false, textIsBlank = true, hasImage = true))
        assertEquals(Route.IMAGE, PasteRouting.route(hasFiles = false, hasText = true, textIsBlank = true, hasImage = true))
    }

    @Test fun plainTextDelegatesAndSoDoesAnEmptyClipboard() {
        assertEquals(Route.TEXT, PasteRouting.route(hasFiles = false, hasText = true, textIsBlank = false, hasImage = false))
        assertEquals(Route.DELEGATE, PasteRouting.route(hasFiles = false, hasText = false, textIsBlank = true, hasImage = false))
    }

    // ---- reaching an image the clipboard is also carrying ----

    /**
     * The browser case. "Copy image" in Chrome or Safari puts the picture *and* its URL on the
     * clipboard; text leads by design, so an ordinary paste inserts the link and the image is
     * unreachable. Precedence stays as it is — reversing it would make a spreadsheet's copied cells
     * paste as a surprise screenshot — but the panel must say the image is there.
     */
    @Test
    fun `a text paste over an image is reported, not silent`() {
        val route = PasteRouting.route(hasFiles = false, hasText = true, textIsBlank = false, hasImage = true)
        assertEquals(PasteRouting.Route.TEXT, route)
        assertTrue(PasteRouting.imageWasPassedOver(route, hasImage = true))
    }

    @Test
    fun `nothing is reported when no image was passed over`() {
        val textOnly = PasteRouting.route(hasFiles = false, hasText = true, textIsBlank = false, hasImage = false)
        assertFalse(PasteRouting.imageWasPassedOver(textOnly, hasImage = false))

        // A screenshot carries no text flavour, so it attaches and there is nothing to mention.
        val imageOnly = PasteRouting.route(hasFiles = false, hasText = false, textIsBlank = true, hasImage = true)
        assertEquals(PasteRouting.Route.IMAGE, imageOnly)
        assertFalse(PasteRouting.imageWasPassedOver(imageOnly, hasImage = true))

        // Files win outright; the notice would be noise on a paste that did what was asked.
        val files = PasteRouting.route(hasFiles = true, hasText = true, textIsBlank = false, hasImage = true)
        assertFalse(PasteRouting.imageWasPassedOver(files, hasImage = true))
    }

    /** A capability nobody can find is not one: the notice has to name the gesture. */
    @Test
    fun `the notice names how to get the image`() {
        assertTrue(PasteRouting.IMAGE_PASSED_OVER, PasteRouting.IMAGE_PASSED_OVER.contains("Shift+Ctrl+V"))
        assertTrue(PasteRouting.IMAGE_PASSED_OVER, PasteRouting.IMAGE_PASSED_OVER.contains("Attach image from clipboard"))
        assertTrue(PasteRouting.NO_IMAGE, PasteRouting.NO_IMAGE.contains("no image"))
    }
}
