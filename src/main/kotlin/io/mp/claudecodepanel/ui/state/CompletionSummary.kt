package io.mp.claudecodepanel.ui.state

import java.util.Locale

/**
 * Formats the subtle assistant-turn completion footer — e.g. `Completed · 51.6s · 13 turns · $0.404`.
 * Platform-free and deterministic (fixed [Locale]) so it is unit-tested. The completion label leads;
 * duration/turns/cost follow only when present. This footer is where run metadata belongs — never the
 * status strip primary (which shows session state) and never a copy of the response text.
 */
object CompletionSummary {

    fun footer(costUsd: Double?, durationMs: Double?, numTurns: Int?, isError: Boolean): String {
        val parts = ArrayList<String>()
        parts.add(if (isError) "Stopped" else "Completed")
        durationMs?.takeIf { it > 0 }?.let { parts.add(duration(it)) }
        numTurns?.takeIf { it > 0 }?.let { parts.add("$it turn" + if (it == 1) "" else "s") }
        costUsd?.takeIf { it > 0 }?.let { parts.add("$" + cost(it)) }
        return parts.joinToString(" · ")
    }

    /** `< 60s` → one-decimal seconds ("51.6s"); longer → "1m 05s". */
    private fun duration(ms: Double): String {
        if (ms < 60_000) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        val totalSec = (ms / 1000.0).toInt()
        return "${totalSec / 60}m ${String.format(Locale.US, "%02ds", totalSec % 60)}"
    }

    /** Up to 4 decimals, trailing zeros trimmed, but at least 2 (`0.4040` → "0.404", `0.4` → "0.40"). */
    private fun cost(v: Double): String {
        val four = String.format(Locale.US, "%.4f", v)
        val trimmed = four.trimEnd('0')
        val decimals = trimmed.length - trimmed.indexOf('.') - 1
        return if (decimals >= 2) trimmed else String.format(Locale.US, "%.2f", v)
    }
}
