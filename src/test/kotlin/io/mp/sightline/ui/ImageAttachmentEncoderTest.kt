package io.mp.sightline.ui

import io.mp.sightline.ui.state.ImageAttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/** Plain AWT + ImageIO — runs headless, no IntelliJ platform. */
class ImageAttachmentEncoderTest {

    private fun solid(w: Int, h: Int, argb: Int = 0xFF3366CC.toInt()): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
        return img
    }

    private fun isPng(bytes: ByteArray) =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private fun isJpeg(bytes: ByteArray) =
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    @Test fun smallImagePassesThroughUnscaledAsPng() {
        val e = ImageAttachmentEncoder.encode(solid(320, 200))!!
        assertEquals(ImageAttachmentPolicy.MEDIA_PNG, e.mediaType)
        assertEquals(320, e.width); assertEquals(200, e.height)
        assertEquals(320, e.sourceWidth); assertEquals(200, e.sourceHeight)
        assertTrue("PNG magic bytes", isPng(e.bytes))
    }

    @Test fun oversizedImageIsDownscaledToTheEdgeLimitPreservingAspect() {
        val e = ImageAttachmentEncoder.encode(solid(ImageAttachmentPolicy.MAX_EDGE * 2, 1000))!!
        assertEquals(ImageAttachmentPolicy.MAX_EDGE, e.width)
        assertEquals(500, e.height)
        // The tooltip's "scaled from" clause depends on the source dimensions surviving intact.
        assertEquals(ImageAttachmentPolicy.MAX_EDGE * 2, e.sourceWidth)
        assertEquals(1000, e.sourceHeight)
    }

    /** PNG is chosen precisely because it is lossless — a screenshot's pixels must survive exactly. */
    @Test fun pngRoundTripsPixelsExactly() {
        val src = solid(64, 64)
        src.setRGB(10, 10, 0x80FF0000.toInt()) // include semi-transparency
        val e = ImageAttachmentEncoder.encode(src)!!
        val decoded = ImageIO.read(ByteArrayInputStream(e.bytes))
        assertEquals(src.getRGB(0, 0), decoded.getRGB(0, 0))
        assertEquals(src.getRGB(10, 10), decoded.getRGB(10, 10))
    }

    @Test fun thumbnailIsSmallAndPresent() {
        val e = ImageAttachmentEncoder.encode(solid(3000, 1500))!!
        val t = e.thumbnail
        assertNotNull(t)
        assertTrue(maxOf(t!!.width, t.height) <= 96)
        // Aspect survives the shrink.
        assertEquals(2.0, t.width.toDouble() / t.height, 0.1)
    }

    /**
     * Incompressible (noise) content blows past the PNG budget and must fall back to JPEG — with
     * transparency matted onto white, because JPEG has no alpha and undefined regions decode black.
     */
    @Test fun photographicContentFallsBackToJpegWithWhiteMatte() {
        val rnd = Random(42)
        val img = BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 1200) for (x in 0 until 1200) {
            img.setRGB(x, y, 0xFF000000.toInt() or rnd.nextInt(0x1000000))
        }
        // A fully transparent corner: after the matte it must decode near-white, not black.
        for (y in 0 until 8) for (x in 0 until 8) img.setRGB(x, y, 0)

        val e = ImageAttachmentEncoder.encode(img)!!
        assertEquals(ImageAttachmentPolicy.MEDIA_JPEG, e.mediaType)
        assertTrue("JPEG magic bytes", isJpeg(e.bytes))
        assertTrue("fallback exists to shrink the payload", e.bytes.size <= ImageAttachmentPolicy.PNG_BYTE_BUDGET)

        val decoded = ImageIO.read(ByteArrayInputStream(e.bytes))
        val corner = Color(decoded.getRGB(2, 2))
        assertTrue("transparent regions matte to white, got $corner", corner.red > 200 && corner.green > 200 && corner.blue > 200)
    }
}
