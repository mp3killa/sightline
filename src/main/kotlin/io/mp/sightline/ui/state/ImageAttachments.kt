package io.mp.sightline.ui.state

/**
 * A clipboard image already encoded for sending: the bytes that will travel to the CLI as a base64
 * `image` content block, plus enough metadata to describe them honestly in the UI. Produced by
 * `ui/ImageAttachmentEncoder` (the AWT half); this type itself is data only, so the policy and the
 * composer model stay unit-testable with fabricated bytes.
 *
 * [thumbnail] is a small pre-scaled render for chips and the transcript bubble. It is optional —
 * pure tests pass null — and deliberately tiny, because it outlives the send: the full [bytes] are
 * released with the pending attachment, but the thumbnail lives on in the transcript row.
 */
class EncodedImage(
    /** [ImageAttachmentPolicy.MEDIA_PNG] or [ImageAttachmentPolicy.MEDIA_JPEG]. */
    val mediaType: String,
    val bytes: ByteArray,
    /** Encoded dimensions — after any downscale. */
    val width: Int,
    val height: Int,
    /** What was actually on the clipboard, so the tooltip can say "scaled from 5120×2880". */
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    val thumbnail: java.awt.image.BufferedImage? = null,
)

/**
 * An [EncodedImage] parked in the composer, waiting to ride the next message. [ordinal] is a
 * monotonic per-conversation counter ("Image 3"), never renumbered when an earlier image is removed
 * — a label that silently renames itself under the user reads as a different attachment.
 */
class PendingImage(
    val id: String,
    val ordinal: Int,
    val image: EncodedImage,
)

/**
 * The rules for accepting a pasted image, in one place: how large an image may be, when it is
 * downscaled, and the exact wording of every label and refusal. Platform-free and unit-tested —
 * the encoder and the Swing chip both read their numbers from here rather than embedding copies.
 */
object ImageAttachmentPolicy {

    const val MEDIA_PNG = "image/png"
    const val MEDIA_JPEG = "image/jpeg"

    /**
     * Per-message cap. The API allows far more, but each image is megabytes of base64 on one stdin
     * line and a screenshot conversation rarely needs more than a handful; past this the honest
     * advice is to send them across messages.
     */
    const val MAX_IMAGES = 8

    /**
     * Longest-edge ceiling, matching what current Claude models natively accept (2576px) — anything
     * larger is downscaled server-side anyway, so sending it would spend stdin payload and tokens on
     * pixels the model never sees. Screenshots of code are exactly the case where resolution carries
     * information, which is why this is the model ceiling and not something smaller.
     */
    const val MAX_EDGE = 2576

    /**
     * Above this, the encoder abandons PNG and re-encodes as JPEG. PNG is lossless and right for
     * screenshots (sharp text), but photographic content can balloon under it; JPEG at
     * [JPEG_QUALITY] keeps such an image well inside the API's per-image limit.
     */
    const val PNG_BYTE_BUDGET = 3_500_000

    /** JPEG quality for the fallback encode. */
    const val JPEG_QUALITY = 0.85f

    /**
     * Absolute per-image cap, with headroom under the API's ~5 MB limit. At [MAX_EDGE] even a JPEG
     * fallback cannot plausibly reach this; the check exists so that if it ever does, the paste is
     * refused with a stated reason instead of dying later inside the CLI.
     */
    const val HARD_MAX_BYTES = 4_500_000

    /** What became of an attempted paste. The composer renders each outcome differently. */
    enum class AddImageResult { ADDED, REJECTED_LIMIT, REJECTED_TOO_LARGE }

    /** Factor to multiply both dimensions by; 1.0 when the image already fits [MAX_EDGE]. */
    fun scaleFactor(width: Int, height: Int): Double {
        val edge = maxOf(width, height)
        return if (edge <= MAX_EDGE) 1.0 else MAX_EDGE.toDouble() / edge
    }

    /** Chip text: `Image 2 · 214 KB` — short, because the tooltip carries the detail. */
    fun chipLabel(image: PendingImage): String =
        "Image ${image.ordinal} · ${formatBytes(image.image.bytes.size)}"

    /**
     * Tooltip: dimensions, provenance of any downscale, and the format actually sent. Stating the
     * downscale matters — the user should know the model sees 2576px, not their 5120px original.
     */
    fun tooltip(image: PendingImage): String {
        val e = image.image
        val scaled = if (e.sourceWidth != e.width || e.sourceHeight != e.height) {
            " (scaled from ${e.sourceWidth}×${e.sourceHeight})"
        } else ""
        val format = if (e.mediaType == MEDIA_JPEG) "JPEG" else "PNG"
        return "Pasted image · ${e.width}×${e.height}$scaled · $format · ${formatBytes(e.bytes.size)}"
    }

    fun limitMessage(): String =
        "Image not attached: a message can carry at most $MAX_IMAGES images. Send these, then paste the next one."

    fun tooLargeMessage(byteCount: Int): String =
        "Image not attached: ${formatBytes(byteCount)} after encoding, above the ${formatBytes(HARD_MAX_BYTES)} per-image limit."

    /** `980 B`, `214 KB`, `1.3 MB` — one decimal only where it carries information. */
    fun formatBytes(n: Int): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${(n + 512) / 1024} KB"
        else -> {
            val mb = n / (1024.0 * 1024.0)
            if (mb >= 10) "${mb.toInt()} MB" else String.format("%.1f MB", mb)
        }
    }
}

/**
 * Decides what a paste into the composer *is*, from which clipboard flavors are present. Kept as a
 * pure function because the precedence is the subtle part and deserves tests, not the Swing plumbing.
 *
 * Precedence, and why:
 *  1. **Files win.** A copied file (Finder, the Project view) also exposes its path as text; pasting
 *     that path as characters is almost never what attaching a file meant.
 *  2. **Non-blank text beats an image.** Some apps put a picture of the text on the clipboard
 *     alongside the text itself (Excel cells are the classic case). A text box's first job is text,
 *     and attaching a surprise picture while discarding the characters the user copied is the worse
 *     failure. The sources that *mean* image — macOS screenshots, "Copy Image" in a browser or image
 *     editor — put no plain text on the clipboard, so they fall through to IMAGE.
 *  3. **Image**, when present with no usable text.
 *  4. Otherwise the paste is none of ours — DELEGATE to the ordinary text handler.
 */
object PasteRouting {

    enum class Route { FILES, TEXT, IMAGE, DELEGATE }

    fun route(hasFiles: Boolean, hasText: Boolean, textIsBlank: Boolean, hasImage: Boolean): Route = when {
        hasFiles -> Route.FILES
        hasText && !textIsBlank -> Route.TEXT
        hasImage -> Route.IMAGE
        else -> Route.DELEGATE
    }
}
