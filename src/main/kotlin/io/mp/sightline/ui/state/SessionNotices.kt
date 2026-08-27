package io.mp.sightline.ui.state

import com.google.gson.JsonObject

/**
 * Two things the CLI reports that the panel used to drop on the floor: **context compaction** and
 * **rate limits**.
 *
 * Both matter because they change what the user should expect and neither is visible any other way.
 * A compaction silently replaces earlier conversation with a summary — so Claude "forgetting"
 * something it was told an hour ago is not a bug, and a user who was never told compaction happened
 * has no way to know that. A rate limit stops work outright.
 *
 * Payload shapes are the CLI's own, read out of the 2.1.235 binary's schema declarations
 * (docs/PROTOCOL.md §6):
 *
 * ```
 * {"type":"system","subtype":"compact_boundary",
 *  "compact_metadata":{"trigger":"manual"|"auto","pre_tokens":N,"post_tokens":N?}}
 *
 * {"type":"rate_limit_event",
 *  "rate_limit_info":{"status":"allowed"|"allowed_warning"|"rejected",
 *                     "resetsAt":<epoch>?,"rateLimitType":"five_hour"|"seven_day"|…,"utilization":N?}}
 * ```
 *
 * Platform-free and unit-tested; the Swing half only shows the text.
 */
object SessionNotices {

    data class Notice(val text: String, val isError: Boolean)

    // ---------- compaction ----------

    /**
     * What to say when the conversation was compacted, or null when the event carries nothing usable.
     *
     * The token counts are stated only when the CLI gave them, and `cumulative_dropped_tokens` is
     * deliberately ignored — the schema marks it `@internal`, and a number the CLI reserves the right
     * to redefine is not one to put in front of a user.
     */
    fun compactNotice(event: JsonObject): Notice? {
        val meta = event.get("compact_metadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val auto = meta?.string("trigger") != "manual"
        val pre = meta?.int("pre_tokens")
        val post = meta?.int("post_tokens")
        val how = if (auto) "ran out of room and compacted this conversation" else "compacted this conversation"
        val sizes = if (pre != null && post != null) " (${tokens(pre)} → ${tokens(post)})" else ""
        return Notice(
            "Claude $how$sizes. Earlier messages are now a summary, so details from before this point " +
                "may need repeating.",
            isError = false,
        )
    }

    private fun tokens(n: Int): String = when {
        n >= 1_000_000 -> "${n / 100_000 / 10.0}M tokens"
        n >= 1_000 -> "${n / 1_000}k tokens"
        else -> "$n tokens"
    }

    // ---------- rate limits ----------

    /**
     * Turns the CLI's rate-limit stream into the few sentences worth saying.
     *
     * The event fires whenever the *information* changes, which includes utilisation ticking up while
     * everything is fine. Speaking every time would bury the transcript, so this only speaks when the
     * **status** changes, and never says "allowed" at all — being within your limits is the state the
     * user is already assuming.
     */
    class RateLimits {
        private var lastStatus: String? = null

        fun onEvent(event: JsonObject, nowMillis: Long): Notice? {
            val info = event.get("rate_limit_info")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val status = info.string("status") ?: return null
            if (status == lastStatus) return null
            val wasRestrictive = lastStatus == "rejected" || lastStatus == "allowed_warning"
            lastStatus = status

            val scope = scopeName(info.string("rateLimitType"))
            val resets = resetPhrase(info.long("resetsAt"), nowMillis)
            return when (status) {
                "rejected" -> Notice("Your $scope limit is used up$resets. Claude cannot run until it resets.", true)
                "allowed_warning" -> {
                    val used = info.int("utilization")?.let { " (about $it% used)" } ?: ""
                    Notice("You are close to your $scope limit$used$resets.", false)
                }
                // Coming *back* from a limit is worth one line; starting there is not, since being
                // within your limits is the state a user already assumes.
                "allowed" -> if (wasRestrictive) Notice("Your $scope limit has reset — Claude can run again.", false) else null
                // Anything the CLI adds later. Saying nothing beats guessing at what it means.
                else -> null
            }
        }
    }

    /** The CLI's own limit buckets, said the way a person would. An unknown one is called "usage". */
    private fun scopeName(type: String?): String = when (type) {
        "five_hour" -> "5-hour usage"
        "seven_day" -> "weekly usage"
        "seven_day_opus" -> "weekly Opus"
        "seven_day_sonnet" -> "weekly Sonnet"
        "seven_day_overage_included", "overage" -> "overage"
        else -> "usage"
    }

    /**
     * ", resets in about 3 hours" — or nothing at all.
     *
     * The schema types `resetsAt` only as an integer, and does not say whether it is seconds or
     * milliseconds. It is not ambiguous in practice — the two are a thousandfold apart, and no
     * plausible reset time lands in the gap — so the magnitude decides, and a value that fits neither
     * reading yields **no phrase** rather than a made-up time. A wrong "resets in 40 years" is worse
     * than saying nothing.
     */
    internal fun resetPhrase(resetsAt: Long?, nowMillis: Long): String {
        val millis = normaliseEpoch(resetsAt) ?: return ""
        val delta = millis - nowMillis
        if (delta <= 0) return ""
        val minutes = delta / 60_000
        return when {
            minutes < 2 -> ", resetting in a minute"
            minutes < 90 -> ", resetting in about $minutes minutes"
            minutes < 60 * 36 -> ", resetting in about ${Math.round(minutes / 60.0)} hours"
            else -> ", resetting in about ${Math.round(minutes / 1440.0)} days"
        }
    }

    /**
     * Epoch seconds or milliseconds → milliseconds; null when it is neither.
     *
     * Read as a Long even though the schema says `int()`, because trusting that and truncating would
     * turn a millisecond timestamp into a nonsense one rather than into a rejection.
     */
    internal fun normaliseEpoch(value: Long?): Long? {
        if (value == null || value <= 0) return null
        return when (value) {
            in 1_000_000_000L..4_000_000_000L -> value * 1000            // seconds, 2001–2096
            in 1_000_000_000_000L..4_000_000_000_000L -> value           // already milliseconds
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
