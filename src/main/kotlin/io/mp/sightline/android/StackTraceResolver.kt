package io.mp.sightline.android

/** One parsed stack frame. [fileName] and [line] are null for a native or synthetic frame. */
data class StackFrame(
    val declaringClass: String,
    val method: String,
    val fileName: String? = null,
    val line: Int? = null,
) {
    val packageName: String get() = declaringClass.substringBeforeLast('.', "")

    /** `com.example.ui.RouteScreen$Content` → `RouteScreen` — the class a *file* would be named after. */
    val topLevelClassSimpleName: String
        get() = declaringClass.substringAfterLast('.').substringBefore('$')

    /**
     * The source file this frame points at, derived from the class when the frame didn't name one.
     * Kotlin frames usually carry `RouteScreen.kt`; Java-style frames sometimes carry nothing.
     */
    val sourceFileName: String? get() = fileName ?: topLevelClassSimpleName.takeIf { it.isNotEmpty() }?.plus(".kt")
}

/** A parsed exception: the throwable, its message, its frames, and any `Caused by` chain. */
data class ParsedStackTrace(
    val throwable: String,
    val message: String?,
    val frames: List<StackFrame>,
    val causes: List<ParsedStackTrace> = emptyList(),
) {
    /** The throwable that actually failed — the deepest cause, which is what to report. */
    val rootCause: ParsedStackTrace get() = causes.lastOrNull()?.rootCause ?: this

    /**
     * The frame a developer wants to land on: the deepest one inside their own code.
     *
     * A crash's top frame is almost always framework or library code — `ArrayList.get`,
     * `Handler.dispatchMessage` — and opening that helps nobody. [appPackagePrefixes] is what makes
     * this possible, and it comes from the resolved applicationId/namespace rather than a guess.
     */
    fun blameFrame(appPackagePrefixes: List<String>): StackFrame? {
        val candidates = (listOf(this) + allCauses()).flatMap { it.frames }
        return candidates.firstOrNull { f -> appPackagePrefixes.any { f.declaringClass.startsWith(it) } }
    }

    fun allCauses(): List<ParsedStackTrace> = causes.flatMap { listOf(it) + it.allCauses() }
}

/**
 * Parses JVM/Android stack traces out of logcat or Gradle output.
 *
 * This exists to close a real gap: `activity/OutputParsers.parseLogcatCrashes` already recognises a
 * `FATAL EXCEPTION`, but emits it with `path = null`, so an Android crash attaches to **nothing** in the
 * activity graph and its transcript card has nowhere to send you. Turning frames into `file:line` makes
 * a crash clickable and lets the graph draw a real `AFFECTED_BY` edge to the file that threw.
 *
 * Platform-free: it produces *candidates* (a file name and a line), and the IDE half resolves those to
 * an actual file. That split is deliberate — the parsing is exhaustively testable, and the resolution
 * needs `FilenameIndex`, which must not creep into a unit-tested class.
 */
object StackTraceResolver {

    // `\tat com.example.Foo.bar(Foo.kt:42)` — also matches logcat's leading tag columns.
    private val FRAME = Regex(
        """^\s*(?:.*?\s)?at\s+([\w$.]+)\.([\w$<>]+)\((?:([\w$.]+\.\w+):(\d+)|([^)]*))\)\s*$""",
    )

    // `java.lang.IllegalStateException: something went wrong`
    private val THROWABLE = Regex("""^\s*(?:.*?\s)?((?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable)[\w$]*)(?::\s*(.*))?\s*$""")

    private val CAUSED_BY = Regex("""^\s*(?:.*?\s)?Caused by:\s*(.*)$""")

    /**
     * Parse the first complete trace in [text], or null.
     *
     * Tolerates logcat's line prefixes (`06-01 12:00:00.000  1234  1234 E AndroidRuntime:`) because
     * that is where these arrive from in practice, and a parser that only handled clean `printStackTrace`
     * output would miss every real crash.
     */
    fun parse(text: String): ParsedStackTrace? {
        val lines = text.lines()
        val start = lines.indexOfFirst { THROWABLE.matches(it) && !CAUSED_BY.containsMatchIn(it) }
        if (start < 0) return null
        return parseFrom(lines, start).first
    }

    /** Every distinct trace in [text] — a logcat window can hold several. */
    fun parseAll(text: String, limit: Int = 10): List<ParsedStackTrace> {
        val lines = text.lines()
        val out = mutableListOf<ParsedStackTrace>()
        var i = 0
        while (i < lines.size && out.size < limit) {
            if (THROWABLE.matches(lines[i]) && !CAUSED_BY.containsMatchIn(lines[i])) {
                val (trace, next) = parseFrom(lines, i)
                if (trace != null) {
                    out += trace
                    i = next
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun parseFrom(lines: List<String>, start: Int): Pair<ParsedStackTrace?, Int> {
        val head = THROWABLE.find(lines[start]) ?: return null to start + 1
        val throwable = head.groupValues[1]
        val message = head.groupValues[2].takeIf { it.isNotBlank() }?.trim()

        val frames = mutableListOf<StackFrame>()
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            FRAME.find(line)?.let { m ->
                frames += StackFrame(
                    declaringClass = m.groupValues[1],
                    method = m.groupValues[2],
                    fileName = m.groupValues[3].takeIf { it.isNotBlank() },
                    line = m.groupValues[4].toIntOrNull(),
                )
                i++
                return@let
            } ?: break
        }
        // `... 24 more` continues the previous trace; it is not the end of one.
        while (i < lines.size && Regex("""^\s*(?:.*?\s)?\.\.\.\s+\d+\s+more\s*$""").matches(lines[i])) i++

        val causes = mutableListOf<ParsedStackTrace>()
        while (i < lines.size) {
            val caused = CAUSED_BY.find(lines[i]) ?: break
            val causeLines = listOf(caused.groupValues[1]) + lines.subList(i + 1, lines.size)
            val (cause, consumed) = parseFrom(causeLines, 0)
            if (cause == null) break
            causes += cause
            i += consumed
        }

        if (frames.isEmpty() && causes.isEmpty()) return null to start + 1
        return ParsedStackTrace(throwable, message, frames, causes) to i
    }

    /**
     * A one-line summary for a card or a graph node label:
     * `IllegalStateException in RouteViewModel.load (RouteViewModel.kt:42)`.
     */
    fun summarise(trace: ParsedStackTrace, appPackagePrefixes: List<String>): String {
        val root = trace.rootCause
        val simple = root.throwable.substringAfterLast('.')
        val frame = trace.blameFrame(appPackagePrefixes) ?: root.frames.firstOrNull()
        val where = frame?.let { f ->
            val loc = f.fileName?.let { fn -> f.line?.let { "$fn:$it" } ?: fn }
            "${f.topLevelClassSimpleName}.${f.method}" + (loc?.let { " ($it)" } ?: "")
        }
        return if (where != null) "$simple in $where" else simple
    }

    /**
     * Package prefixes that count as "the app", from whatever the fact ladder resolved.
     *
     * Both the applicationId and the namespace matter and they are often different: a flavour's
     * `applicationIdSuffix` makes the installed id `com.example.driver.staging` while the code still lives
     * in `com.example.compose`. Blaming on the applicationId alone would find no frames at all in exactly
     * the multi-flavour projects this is meant to help.
     */
    fun appPrefixes(applicationId: String?, namespace: String?): List<String> =
        listOfNotNull(applicationId, namespace)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Strip a flavour suffix so `com.example.driver.staging` still matches `com.example.driver.*`.
            .flatMap { id -> listOf(id, id.substringBeforeLast('.').takeIf { it.count { c -> c == '.' } >= 1 } ?: id) }
            .distinct()
}
