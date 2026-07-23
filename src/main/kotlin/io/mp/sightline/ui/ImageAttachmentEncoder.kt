package io.mp.sightline.ui

import io.mp.sightline.ui.state.EncodedImage
import io.mp.sightline.ui.state.ImageAttachmentPolicy
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.ImageIcon
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a raw clipboard [Image] into an [EncodedImage] ready to send: force-loads it, downscales to
 * [ImageAttachmentPolicy.MAX_EDGE], encodes PNG (lossless — right for the screenshot case), and
 * falls back to JPEG only when the PNG comes out photographically large. Plain AWT + ImageIO, no
 * IntelliJ platform types, so it runs headless under unit tests.
 *
 * Runs off the EDT (encoding a retina screenshot takes real milliseconds); the caller hops back to
 * Swing with the result. Returns null only when the clipboard handed over something unreadable —
 * never a best-guess partial image.
 */
object ImageAttachmentEncoder {

    /** Longest edge of the chip/bubble thumbnail. Small on purpose: it outlives the send. */
    private const val THUMB_MAX_EDGE = 96

    fun encode(source: Image): EncodedImage? {
        val loaded = toBufferedImage(source) ?: return null
        val srcW = loaded.width
        val srcH = loaded.height

        val factor = ImageAttachmentPolicy.scaleFactor(srcW, srcH)
        val scaled = if (factor >= 1.0) loaded else downscale(
            loaded,
            max(1, (srcW * factor).roundToInt()),
            max(1, (srcH * factor).roundToInt()),
        )

        val png = writePng(scaled) ?: return null
        val (mediaType, bytes) = if (png.size <= ImageAttachmentPolicy.PNG_BYTE_BUDGET) {
            ImageAttachmentPolicy.MEDIA_PNG to png
        } else {
            // Photographic content: JPEG at fixed quality. If the JPEG writer is unavailable the
            // oversized PNG is returned as-is — the policy's hard cap decides its fate, with a
            // stated reason, rather than this layer silently dropping the paste.
            writeJpeg(flattenToWhite(scaled))?.let { ImageAttachmentPolicy.MEDIA_JPEG to it }
                ?: (ImageAttachmentPolicy.MEDIA_PNG to png)
        }

        return EncodedImage(
            mediaType = mediaType,
            bytes = bytes,
            width = scaled.width,
            height = scaled.height,
            sourceWidth = srcW,
            sourceHeight = srcH,
            thumbnail = thumbnail(scaled),
        )
    }

    /**
     * Clipboard images may still be loading asynchronously; [ImageIcon]'s MediaTracker blocks until
     * they aren't. Everything is normalised into ARGB so exotic color models can't surprise the
     * scaler or the PNG writer.
     */
    private fun toBufferedImage(source: Image): BufferedImage? {
        if (source is BufferedImage && source.width > 0 && source.height > 0) return source
        val icon = ImageIcon(source)
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return null
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.drawImage(icon.image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /**
     * Repeated bilinear halving down to within 2× of the target, then one final pass. A single
     * bilinear jump across a large ratio shimmers on fine detail — and fine detail (code in a
     * screenshot) is exactly what these images carry.
     */
    private fun downscale(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
        var current = src
        while (current.width >= targetW * 2 && current.height >= targetH * 2) {
            current = scaleTo(current, max(targetW, current.width / 2), max(targetH, current.height / 2))
        }
        return if (current.width == targetW && current.height == targetH) current
        else scaleTo(current, targetW, targetH)
    }

    private fun scaleTo(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writePng(img: BufferedImage): ByteArray? {
        val baos = ByteArrayOutputStream()
        return try {
            if (!ImageIO.write(img, "png", baos)) null else baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeJpeg(rgb: BufferedImage): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val baos = ByteArrayOutputStream()
        return try {
            val param = writer.defaultWriteParam.apply {
                if (canWriteCompressed()) {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = ImageAttachmentPolicy.JPEG_QUALITY
                }
            }
            ImageIO.createImageOutputStream(baos).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(rgb, null, null), param)
            }
            baos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            writer.dispose()
        }
    }

    /** JPEG has no alpha; transparent regions are matted onto white rather than going black. */
    private fun flattenToWhite(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, src.width, src.height)
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun thumbnail(src: BufferedImage): BufferedImage {
        val edge = max(src.width, src.height)
        if (edge <= THUMB_MAX_EDGE) return src
        val f = THUMB_MAX_EDGE.toDouble() / edge
        return scaleTo(src, max(1, (src.width * f).roundToInt()), max(1, (src.height * f).roundToInt()))
    }
}
