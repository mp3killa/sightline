package io.mp.sightline.android

/** Android log priority. Ordinal is severity order, so filtering is a comparison. */
enum class LogLevel(val code: Char) {
    VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E'), ASSERT('F');

    companion object {
        fun of(code: Char): LogLevel? = entries.firstOrNull { it.code == code.uppercaseChar() }
    }
}

/** One logcat line. [raw] is kept so a capture can be shown verbatim after redaction. */
data class LogEntry(
    val timestamp: String?,
    val pid: Int?,
    val tid: Int?,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val raw: String,
)

/** Repeated lines folded into one, with a count. */
data class GroupedEntry(val entry: LogEntry, val repeats: Int) {
    val display: String
        get() = if (repeats <= 1) entry.raw else "${entry.raw}  (×$repeats)"
}

/** A runtime event worth surfacing on its own — the things a developer is actually hunting. */
data class LogSignal(
    val kind: Kind,
    val summary: String,
    val firstLine: String,
    val process: String? = null,
) {
    enum class Kind(val label: String) {
        CRASH("Crash"),
        ANR("ANR"),
        STRICT_MODE("StrictMode violation"),
        OUT_OF_MEMORY("Out of memory"),
        PROCESS_DEATH("Process died"),
        PROCESS_START("Process started"),
    }
}

/**
 * Parses `adb logcat -v threadtime` and pulls the signals out of it.
 *
 * The format is a stable, documented adb contract, which is why this is a parser rather than a heuristic.
 * Everything it doesn't recognise is preserved as an entry with the raw text intact — a logcat window is
 * evidence, and quietly dropping lines from evidence is worse than leaving them unparsed.
 *
 * Grouping and noise suppression exist because of what a real capture looks like: thirty seconds of
 * logcat from a Compose app is largely `Choreographer` frame-skips and vendor chatter, and the two lines
 * that matter are somewhere in the middle. Folding repeats and hiding known framework noise is what makes
 * a capture small enough to attach without burying the thing being asked about.
 */
object LogcatParser {

    // `06-01 12:00:00.123  4521  4602 E AndroidRuntime: FATAL EXCEPTION: main`
    private val THREADTIME = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]*?)\s*:\s?(.*)$""",
    )

    // `E/AndroidRuntime( 4521): FATAL EXCEPTION: main` — the older brief format.
    private val BRIEF = Regex("""^([VDIWEFA])/([^(]+)\(\s*(\d+)\):\s?(.*)$""")

    /** Tags that are almost always framework noise. Suppressed only when explicitly asked. */
    private val NOISY_TAGS = setOf(
        "Choreographer", "OpenGLRenderer", "BufferQueueProducer", "SurfaceView", "ViewRootImpl",
        "InputMethodManager", "HwBinder", "libEGL", "chatty", "Gralloc", "vndksupport",
        "BpBinder", "ziparchive", "ProfileInstaller", "nativeloader",
    )

    fun parse(text: String): List<LogEntry> = text.lineSequence().mapNotNull(::parseLine).toList()

    fun parseLine(line: String): LogEntry? {
        if (line.isBlank()) return null
        THREADTIME.find(line)?.let { m ->
            val level = LogLevel.of(m.groupValues[4].first()) ?: return@let
            return LogEntry(
                timestamp = m.groupValues[1],
                pid = m.groupValues[2].toIntOrNull(),
                tid = m.groupValues[3].toIntOrNull(),
                level = level,
                tag = m.groupValues[5].trim(),
                message = m.groupValues[6],
                raw = line,
            )
        }
        BRIEF.find(line)?.let { m ->
            val level = LogLevel.of(m.groupValues[1].first()) ?: return@let
            return LogEntry(
                timestamp = null,
                pid = m.groupValues[3].toIntOrNull(),
                tid = null,
                level = level,
                tag = m.groupValues[2].trim(),
                message = m.groupValues[4],
                raw = line,
            )
        }
        // A continuation line — a stack frame, a multi-line message. Kept, because dropping it would
        // break exactly the traces this is most often used to read.
        return LogEntry(null, null, null, LogLevel.VERBOSE, "", line.trim(), line)
    }

    /** Entries at or above [minLevel], optionally for one pid, optionally without framework noise. */
    fun filter(
        entries: List<LogEntry>,
        minLevel: LogLevel = LogLevel.VERBOSE,
        pid: Int? = null,
        hideNoise: Boolean = false,
    ): List<LogEntry> = entries.filter { e ->
        // A continuation line has no level or pid of its own; keeping it is what preserves stack traces
        // through a filter that would otherwise cut them off after their first line.
        val isContinuation = e.tag.isEmpty() && e.timestamp == null
        when {
            isContinuation -> true
            e.level.ordinal < minLevel.ordinal -> false
            pid != null && e.pid != null && e.pid != pid -> false
            hideNoise && e.tag in NOISY_TAGS -> false
            else -> true
        }
    }

    /**
     * Fold consecutive identical messages. Compares tag + message rather than the raw line, so entries
     * that differ only by timestamp still collapse — which is the case that actually floods a capture.
     */
    fun group(entries: List<LogEntry>): List<GroupedEntry> {
        val out = mutableListOf<GroupedEntry>()
        for (e in entries) {
            val last = out.lastOrNull()
            if (last != null && last.entry.tag == e.tag && last.entry.message == e.message && e.tag.isNotEmpty()) {
                out[out.size - 1] = last.copy(repeats = last.repeats + 1)
            } else {
                out += GroupedEntry(e, 1)
            }
        }
        return out
    }

    /**
     * The events worth calling out. Conservative by design — each needs an explicit marker Android
     * emits, so an ordinary log produces no signals rather than a speculative one.
     */
    fun signals(entries: List<LogEntry>): List<LogSignal> {
        val out = mutableListOf<LogSignal>()
        for ((i, e) in entries.withIndex()) {
            val msg = e.message
            when {
                msg.startsWith("FATAL EXCEPTION") -> {
                    val process = entries.drop(i + 1).take(3)
                        .firstNotNullOfOrNull { PROCESS.find(it.message)?.groupValues?.get(1) }
                    val throwableLine = entries.drop(i + 1).take(5)
                        .firstOrNull { THROWABLE_LINE.containsMatchIn(it.message) }?.message
                    out += LogSignal(
                        LogSignal.Kind.CRASH,
                        throwableLine?.trim() ?: msg,
                        e.raw,
                        process,
                    )
                }
                msg.startsWith("ANR in ") -> out += LogSignal(
                    LogSignal.Kind.ANR,
                    msg.trim(),
                    e.raw,
                    ANR_PROCESS.find(msg)?.groupValues?.get(1),
                )
                e.tag == "StrictMode" || msg.contains("StrictMode policy violation") -> out += LogSignal(
                    LogSignal.Kind.STRICT_MODE,
                    msg.take(160).trim(),
                    e.raw,
                )
                msg.contains("OutOfMemoryError") -> out += LogSignal(
                    LogSignal.Kind.OUT_OF_MEMORY,
                    msg.take(160).trim(),
                    e.raw,
                )
                // `Process com.example (pid 4521) has died` — the tell that an app restarted underneath
                // whatever the developer was doing, which explains a great many confusing sessions.
                PROCESS_DIED.containsMatchIn(msg) -> out += LogSignal(
                    LogSignal.Kind.PROCESS_DEATH,
                    msg.trim(),
                    e.raw,
                    PROCESS_DIED.find(msg)?.groupValues?.get(1),
                )
                PROCESS_STARTED.containsMatchIn(msg) -> out += LogSignal(
                    LogSignal.Kind.PROCESS_START,
                    msg.trim(),
                    e.raw,
                    PROCESS_STARTED.find(msg)?.groupValues?.get(1),
                )
            }
        }
        return out
    }

    private val PROCESS = Regex("""Process:\s*([\w.]+)""")
    private val THROWABLE_LINE = Regex("""^\s*(?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable)""")
    private val ANR_PROCESS = Regex("""ANR in ([\w.]+)""")
    private val PROCESS_DIED = Regex("""Process ([\w.]+) \(pid \d+\) has died""")
    private val PROCESS_STARTED = Regex("""Start proc \d+:([\w.]+)""")

    /**
     * The `adb logcat` argv for a capture. `-d` dumps and exits — without it logcat streams forever and
     * the call never returns.
     */
    fun captureArgs(pid: Int? = null, minLevel: LogLevel = LogLevel.VERBOSE, maxLines: Int = 2000): List<String> =
        buildList {
            add("logcat")
            add("-d")
            add("-v"); add("threadtime")
            add("-t"); add(maxLines.toString())
            pid?.let { add("--pid=$it") }
            if (minLevel != LogLevel.VERBOSE) add("*:${minLevel.code}")
        }
}
