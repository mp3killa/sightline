package io.mp.sightline.ui.state

import io.mp.sightline.ui.state.PasteRouting.Route
import org.junit.Assert.assertEquals
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
}
