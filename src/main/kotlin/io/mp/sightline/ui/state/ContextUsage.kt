package io.mp.sightline.ui.state

/**
 * How much of the model's context window the conversation is using — the thing a long session gives
 * you no way to see, and the single most-requested missing control in the equivalent VS Code panel.
 *
 * **Where the numbers come from, and what they are not.** Everything here is the CLI's own structured
 * `usage`, relayed: `stream_event`/`message_start` carries `message.usage` at the start of each
 * request, and `result` carries the turn's final `usage` plus `modelUsage[<model>].contextWindow`
 * (verified against 2.1.235 — `contextWindow: 200000` for Haiku 4.5). Occupancy is
 * `input + cache_creation + cache_read + output`, which is *what the API was actually sent*.
 *
 * That is deliberately **not** the same number the CLI's own `/context` prints: on a fresh session
 * `/context` estimated 30.5k where `usage` summed to 34.0k, because `/context` is the CLI's own
 * category estimate (system prompt, tools, memory, deferred definitions) and this is the billed
 * request. Both are true; they measure different things. So the label says *"in the last request"* and
 * the tooltip points at `/context` for the breakdown — claiming to reproduce a number we do not
 * compute is exactly the kind of confident wrongness this codebase avoids.
 *
 * **A percentage is only ever shown when the CLI has told us the window.** Before the first `result`
 * of a session there is no `modelUsage`, so the tokens are stated alone rather than against a guessed
 * 200k — the same rule as `AndroidFacts`' ladder and `HealthStatus.UNKNOWN`. Inferring the window
 * from the model name would be a guess, and a wrong one the day a model ships with a different one.
 */
object ContextUsage {

    /**
     * [window] is null until the CLI states it. [tokens] is the occupancy of the most recent request,
     * so it moves with the conversation and **drops after a compaction** — which is the moment the
     * number is most worth watching.
     */
    data class Snapshot(val tokens: Long, val window: Long?) {
        /** 0.0–1.0, or null when the window is unknown. Never clamped above 1: over-window is a fact. */
        val fraction: Double? get() = window?.takeIf { it > 0 }?.let { tokens.toDouble() / it }
    }

    /** What the composer footer shows. [level] drives the colour; [detail] is the tooltip. */
    data class View(val text: String, val detail: String, val level: Level)

    /**
     * NORMAL / HIGH / CRITICAL, by how close the conversation is to the window.
     *
     * The thresholds exist to give warning *before* an auto-compaction, not to predict one: the CLI
     * decides when to compact and does not tell us its trigger, so nothing here says "about to
     * compact". It says how full the context is, which is a fact, and lets the reader draw the
     * conclusion. When a compaction does happen, `SessionNotices` reports it in the transcript.
     */
    enum class Level { NORMAL, HIGH, CRITICAL }

    const val HIGH_FRACTION = 0.70
    const val CRITICAL_FRACTION = 0.90

    /**
     * Verified against 2.1.235 over three turns of one session: occupancy went 34,065 → 35,297 →
     * 35,380 while `input_tokens` stayed at **10** every turn. The history rides in
     * `cache_read_input_tokens`, which is why the sum is cumulative context and why reporting
     * `input_tokens` alone — the obvious-looking "just show what the CLI states" option — would have
     * pinned a meter to a constant.
     *
     * Occupancy of one `usage` object: every input class the request carried, plus what came back.
     */
    fun occupancy(
        inputTokens: Long,
        cacheCreationTokens: Long,
        cacheReadTokens: Long,
        outputTokens: Long,
    ): Long = inputTokens + cacheCreationTokens + cacheReadTokens + outputTokens

    /**
     * The footer text, or null when there is nothing honest to say yet (no usage seen this session —
     * an empty conversation has no context to report, and "0 tokens" reads as a broken meter).
     */
    fun view(snapshot: Snapshot?): View? {
        if (snapshot == null || snapshot.tokens <= 0) return null
        val f = snapshot.fraction
        val window = snapshot.window
        val text = if (f != null && window != null) {
            "${compact(snapshot.tokens)} / ${compact(window)} · ${percent(f)}"
        } else {
            compact(snapshot.tokens)
        }
        return View(text, detail(snapshot), level(f))
    }

    fun level(fraction: Double?): Level = when {
        fraction == null -> Level.NORMAL
        fraction >= CRITICAL_FRACTION -> Level.CRITICAL
        fraction >= HIGH_FRACTION -> Level.HIGH
        else -> Level.NORMAL
    }

    /**
     * The tooltip. States what the number is, what it is not, and — only when the window is unknown —
     * why there is no percentage, rather than leaving a bare token count looking like a truncation.
     */
    fun detail(snapshot: Snapshot): String {
        val head = "${snapshot.tokens} tokens in the last request to the model " +
            "(input, cached input and output combined)."
        val tail = if (snapshot.window == null) {
            " The model's context window has not been reported yet, so there is no percentage to show."
        } else {
            " Run /context for the CLI's own breakdown by category — it estimates a little differently."
        }
        return head + tail
    }

    /** `34024` → `34.0k`; `200000` → `200k`. Two significant-ish figures, never a false precision. */
    fun compact(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${round1(tokens / 1_000_000.0)}M"
        tokens >= 100_000 -> "${(tokens / 1000)}k"
        tokens >= 1_000 -> "${round1(tokens / 1000.0)}k"
        else -> tokens.toString()
    }

    private fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

    private fun round1(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }
}
