package io.mp.claudecodepanel.android

/** What a redaction pass changed, so the UI can say so rather than silently altering evidence. */
data class RedactionReport(
    val text: String,
    /** Count per category, for "12 values redacted (4 tokens, 6 emails, 2 coordinates)". */
    val counts: Map<String, Int> = emptyMap(),
    /** Lines dropped whole because they could not be safely redacted. */
    val droppedLines: Int = 0,
) {
    val totalRedactions: Int get() = counts.values.sum()
    val changedAnything: Boolean get() = totalRedactions > 0 || droppedLines > 0

    /** "8 values redacted · 1 line dropped", or "" when nothing changed. */
    fun summary(): String {
        if (!changedAnything) return ""
        val parts = mutableListOf<String>()
        if (totalRedactions > 0) {
            val detail = counts.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.value} ${it.key}" }
            parts += "$totalRedactions value${if (totalRedactions == 1) "" else "s"} redacted ($detail)"
        }
        if (droppedLines > 0) parts += "$droppedLines line${if (droppedLines == 1) "" else "s"} dropped"
        return parts.joinToString(" · ")
    }
}

/**
 * Scrubs logcat before it goes anywhere near a prompt.
 *
 * This is the hardest privacy surface in the whole Android roadmap (docs/ANDROID.md §1.4). A device log
 * is a running record of whatever the app is doing with real data: auth tokens on every request, the
 * user's email at sign-in, their coordinates on every location update, device identifiers, and whatever
 * the developer left in a debug `Log.d`. Attaching it to a conversation sends all of that to a
 * third-party API. So this runs **on by default** and is not presented as an option.
 *
 * It follows `health/HealthSanitizer`'s posture exactly — allow-nothing-sensitive rather than
 * deny-a-blocklist, over-redact happily, order the rules so a specific pattern runs before a general one
 * — and adds one rule that sanitiser doesn't need: **it fails closed.** A line long enough to be a data
 * dump, or one carrying a base64 blob we can't reason about, is *dropped whole* rather than passed
 * through partly scrubbed. A truncated log is an inconvenience; a leaked bearer token is an incident.
 *
 * Redactions are counted and reported, because silently altering evidence a developer is trying to debug
 * from is its own kind of harm — they need to know a value was removed rather than absent.
 */
object LogcatRedactor {

    private const val REDACTED = "[redacted]"

    /** Beyond this a single log line is a payload dump, not a message. Dropped rather than scrubbed. */
    private const val MAX_LINE_LENGTH = 2_000

    fun redact(text: String): RedactionReport {
        if (text.isEmpty()) return RedactionReport(text)
        val counts = mutableMapOf<String, Int>()
        var dropped = 0

        val out = text.lineSequence().mapNotNull { line ->
            if (line.length > MAX_LINE_LENGTH) {
                dropped++
                return@mapNotNull "[line dropped: ${line.length} characters, too long to redact safely]"
            }
            redactLine(line, counts)
        }.joinToString("\n")

        return RedactionReport(out, counts.toMap(), dropped)
    }

    /** One line. Exposed for testing; [redact] applies it across a capture. */
    fun redactLine(line: String, counts: MutableMap<String, Int> = mutableMapOf()): String {
        var s = line

        // 1. Auth material first, and the credential forms before the generic key=value rule: for
        //    `Authorization: Bearer eyJ…` the header rule's value stops at the first space, so it would
        //    eat "Bearer" and leave the token behind. Same ordering bug HealthSanitizer had to fix.
        s = count(s, BEARER, "tokens", counts) { m -> m.groupValues[1] + " " + REDACTED }
        s = count(s, AUTH_HEADER, "tokens", counts) { m -> m.groupValues[1] + REDACTED }
        s = count(s, JWT, "tokens", counts) { REDACTED }
        s = count(s, KEY_VALUE_SECRET, "secrets", counts) { m -> m.groupValues[1] + REDACTED }

        // 2. Identifiers, before free-form numbers can eat their digits.
        s = count(s, EMAIL, "emails", counts) { REDACTED }
        s = count(s, ANDROID_ID, "device ids", counts) { m -> m.groupValues[1] + REDACTED }
        s = count(s, IMEI, "device ids", counts) { REDACTED }
        s = count(s, IPV4, "addresses", counts) { REDACTED }

        // 3. Location. A lat/long pair is among the most sensitive things in a driver app's log, and it
        //    is also the easiest to miss because it looks like ordinary numbers.
        s = count(s, LAT_LONG, "coordinates", counts) { REDACTED }
        s = count(s, LOCATION_FIELD, "coordinates", counts) { m -> m.groupValues[1] + REDACTED }

        // 4. Phone numbers — after IPs and coordinates so those don't get partly consumed first.
        s = count(s, PHONE, "phone numbers", counts) { REDACTED }

        // 5. Home paths, which carry the username.
        s = count(s, HOME_PATH, "paths", counts) { m -> m.groupValues[1] + "…" }

        // 6. Long opaque blobs last: by now anything structured has been handled, so what remains that
        //    looks like encoded data is treated as data.
        s = count(s, LONG_OPAQUE, "blobs", counts) { REDACTED }

        return s
    }

    private fun count(
        input: String,
        pattern: Regex,
        category: String,
        counts: MutableMap<String, Int>,
        replace: (MatchResult) -> String,
    ): String {
        var hits = 0
        val out = pattern.replace(input) { m -> hits++; replace(m) }
        if (hits > 0) counts[category] = (counts[category] ?: 0) + hits
        return out
    }

    // ---- patterns ----

    private val BEARER = Regex("""\b(Bearer|Basic|Token)\s+[A-Za-z0-9\-._~+/=]{8,}""", RegexOption.IGNORE_CASE)

    private val AUTH_HEADER = Regex(
        """\b((?:Authorization|Proxy-Authorization|X-Api-Key|X-Auth-Token|Cookie|Set-Cookie)\s*[:=]\s*)\S+""",
        RegexOption.IGNORE_CASE,
    )

    /** Three dot-separated base64url segments — a JWT, wherever it appears. */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b""")

    /**
     * The key may be quoted on both sides — `{"access_token":"ya29…"}` is how these actually appear in
     * a logged HTTP body, and a pattern that only allows a bare key misses the single most common shape.
     */
    private val KEY_VALUE_SECRET = Regex(
        """(["']?\b(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|secret|password|passwd|pwd|client[_-]?secret|session[_-]?id|auth)\b["']?\s*[:=]\s*["']?)[^\s,;"'&}]{4,}""",
        RegexOption.IGNORE_CASE,
    )

    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    private val ANDROID_ID = Regex(
        """(["']?\b(?:android[_-]?id|device[_-]?id|advertising[_-]?id|serial|imei|fcm[_-]?token)\b["']?\s*[:=]?\s*["']?)[A-Za-z0-9\-]{6,}""",
        RegexOption.IGNORE_CASE,
    )

    /** A bare 15-digit IMEI. Bounded on both sides so a long ordinary number isn't caught. */
    private val IMEI = Regex("""(?<![\d.])\d{15}(?![\d.])""")

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    /**
     * A decimal pair that looks like a coordinate: both in range, and both with real precision. The
     * precision requirement is what stops `1.0, 2.0` — version numbers, ratios, durations — from being
     * mistaken for a location.
     */
    private val LAT_LONG = Regex(
        """(?<![\d.])[-+]?(?:[0-8]?\d|90)\.\d{4,}\s*,\s*[-+]?(?:1[0-7]\d|[0-9]?\d|180)\.\d{4,}(?![\d.])""",
    )

    private val LOCATION_FIELD = Regex(
        """\b((?:lat|latitude|lng|lon|longitude)\s*[:=]\s*)[-+]?\d+\.\d+""",
        RegexOption.IGNORE_CASE,
    )

    /** International or long national form. Deliberately not matching bare 7-digit runs. */
    private val PHONE = Regex("""(?<![\w.])\+\d{1,3}[\s-]?\(?\d{1,4}\)?[\s-]?\d{3,4}[\s-]?\d{3,4}(?![\w.])""")

    private val HOME_PATH = Regex("""((?:/Users|/home|/data/user/\d+)/)[^\s/:]+""")

    /**
     * A long unbroken run of base64-ish characters with no word structure. Requires mixed case *and*
     * digits so ordinary long identifiers — `SomeVeryLongClassNameHere`, a package name — survive.
     */
    private val LONG_OPAQUE = Regex("""(?<![\w/.-])(?=[A-Za-z0-9+/=_-]{40,})(?=[^\s]*[a-z])(?=[^\s]*[A-Z])(?=[^\s]*\d)[A-Za-z0-9+/=_-]{40,}(?![\w/.-])""")

    /**
     * Would this text still be unsafe after redaction? A last check before anything is attached.
     *
     * Used as an assertion rather than a filter — if this ever returns true on redacted output, a
     * pattern above is wrong, and the caller should drop the capture rather than send it.
     */
    fun looksUnsafe(text: String): Boolean =
        JWT.containsMatchIn(text) || BEARER.containsMatchIn(text) || EMAIL.containsMatchIn(text)
}
