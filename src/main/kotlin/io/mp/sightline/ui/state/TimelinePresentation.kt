package io.mp.sightline.ui.state

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The dot drawn on the transcript's timeline rail beside each step of a turn. */
enum class TimelineMarker {
    /** Prose and reasoning: a quiet, muted dot. */
    NEUTRAL,

    /** A tool still in flight, or a prompt waiting on the user: the accent colour. */
    ACTIVE,

    /** A tool that finished cleanly. */
    SUCCESS,

    /** A tool that failed: the one dot a reader must not miss. */
    ERROR,

    /** Denied or cancelled: drawn hollow and muted, so a step that never ran can't read as done. */
    BLOCKED,
}

/**
 * Presentation rules for the timeline-style transcript: one dot per step on a rail, a one-line header,
 * and short `IN` / `OUT` blocks for commands. Decided from structured metadata only, like
 * [ToolEventPresentation] — never from a rendered string.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object TimelinePresentation {

    fun markerFor(outcome: ToolOutcome): TimelineMarker = when (outcome) {
        ToolOutcome.RUNNING -> TimelineMarker.ACTIVE
        ToolOutcome.OK -> TimelineMarker.SUCCESS
        ToolOutcome.ERROR -> TimelineMarker.ERROR
        ToolOutcome.BLOCKED -> TimelineMarker.BLOCKED
    }

    /**
     * The bold title of a tool row. Built-in tools keep their own name ("Bash", "Read", "Edit"), the
     * name the model and the docs use. An MCP tool (`mcp__server__tool_name`) shows its tool part,
     * humanised, since the server prefix is the same on every call.
     */
    fun toolTitle(name: String): String {
        if (!name.startsWith("mcp__")) return name
        val short = name.substringAfterLast("__")
        return short.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * The muted suffix after a Read's file name, e.g. "(from line 97)" or "(lines 10–40)". Null when the
     * whole file was read. Reports the range the model *requested*, which is all the input holds.
     */
    fun readRange(offset: Int?, limit: Int?): String? {
        val from = offset?.takeIf { it > 0 } ?: return null
        return if (limit != null && limit > 0) "(lines $from–${from + limit - 1})" else "(from line $from)"
    }

    /**
     * The thinking row's label. "Thinking…" while it streams. "Thought for 4s" after, which is honest
     * even when the model sent no visible reasoning. Plain "Thought" when no duration was measured,
     * as on a replayed, non-streamed message.
     */
    fun thoughtLabel(durationMillis: Long?, streaming: Boolean): String {
        if (streaming) return "Thinking…"
        val ms = durationMillis ?: return "Thought"
        val secs = ((ms + 500) / 1000).coerceAtLeast(1)
        return if (secs < 60) "Thought for ${secs}s" else "Thought for ${secs / 60}m ${secs % 60}s"
    }

    /** Lines of command output shown before the block fades and offers "Show all". */
    const val OUTPUT_PREVIEW_LINES = 3

    data class OutputPreview(val shown: String, val hiddenLines: Int) {
        val truncated: Boolean get() = hiddenLines > 0
    }

    /**
     * The first [maxLines] of [text], with trailing blank lines dropped first so a final newline doesn't
     * cost a preview line. The hidden count is *stated* by the caller, never silently dropped.
     */
    fun outputPreview(text: String, maxLines: Int = OUTPUT_PREVIEW_LINES): OutputPreview {
        val lines = text.trimEnd('\n', '\r', ' ', '\t').split('\n')
        if (lines.size <= maxLines) return OutputPreview(lines.joinToString("\n"), 0)
        return OutputPreview(lines.take(maxLines).joinToString("\n"), lines.size - maxLines)
    }

    /** "Show 12 more lines" — with the singular handled, since it is read on every long command. */
    fun moreLinesText(hidden: Int): String = if (hidden == 1) "Show 1 more line" else "Show $hidden more lines"

    /** A turn's timestamp in the user's locale's short time style ("8:27 PM", "20:27"). */
    fun timestamp(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
}

/**
 * The rotating word on the "still working" row at the end of a running turn. It signals liveness
 * without claiming anything about what the model is doing. The words are deliberately generic: they
 * describe *waiting*, never a specific action. The activity map and the tool rows report what is
 * actually happening.
 */
object WorkingVerbs {
    val ALL: List<String> = listOf(
        "Working", "Mulling", "Sighting", "Surveying", "Tracing", "Plotting", "Weighing", "Focusing",
        "Sketching", "Untangling", "Piecing together", "Calibrating", "Composing", "Considering",
        "Lining up", "Squinting", "Charting", "Pondering",
    )

    /** How long each word stays before rotating. Slow enough to read, fast enough to show life. */
    const val ROTATE_MILLIS = 3500

    /**
     * The word to show [elapsedMillis] into a turn that started with [seed]. Deterministic, so a
     * repaint can never flicker the word, and the seed varies the starting word between turns.
     */
    fun verbAt(seed: Int, elapsedMillis: Long): String {
        val step = (elapsedMillis.coerceAtLeast(0) / ROTATE_MILLIS).toInt()
        return ALL[Math.floorMod(seed + step, ALL.size)]
    }

    /** "Mulling… · 12s": the word, then elapsed time once there is enough of it to be informative. */
    fun line(seed: Int, elapsedMillis: Long): String {
        val word = verbAt(seed, elapsedMillis) + "…"
        val secs = elapsedMillis / 1000
        return when {
            secs < 3 -> word
            secs < 60 -> "$word · ${secs}s"
            else -> "$word · ${secs / 60}m ${secs % 60}s"
        }
    }
}

/**
 * Which of the user's prompts the sticky bar pinned over the top of the transcript should show. A long
 * turn scrolls its prompt out of view, and the bar keeps the question in sight while its answer is read.
 */
object StickyPrompt {
    /**
     * Index of the latest prompt whose bar has scrolled fully above [viewTop]: the prompt that the
     * content at the top of the view answers. Null when none has, so nothing is pinned at the start.
     * [bottoms] are the prompts' bottom edges in transcript coordinates, in transcript order.
     */
    fun select(bottoms: List<Int>, viewTop: Int): Int? = bottoms.indices.lastOrNull { bottoms[it] <= viewTop }
}
