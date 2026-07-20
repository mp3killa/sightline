package io.mp.sightline.android

/**
 * How sure we are about a piece of a crash explanation. **This distinction is the whole feature.**
 *
 * Without it, a crash report is a plausible narrative with no way to tell the part that was read off the
 * stack trace from the part that was inferred — and a developer acting on an inference presented as a
 * fact loses more time than one given nothing at all.
 */
enum class Confidence(val heading: String) {
    /** Read directly off the evidence. The trace says this; it is not an interpretation. */
    CONFIRMED("Confirmed"),

    /** Follows from the evidence, but a step of reasoning stands between them. */
    LIKELY("Likely cause"),

    /** Consistent with the evidence and worth checking; not established. */
    POSSIBLE("Contributing factors"),

    /** What we could not determine, and what would settle it. */
    MISSING("Missing evidence"),
}

/** One statement in an investigation, with the evidence that supports it. */
data class Finding(
    val confidence: Confidence,
    val statement: String,
    /** Where this came from: "stack frame", "logcat", "recent change", "manifest". */
    val evidence: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
)

/** A structured explanation of one crash or ANR. */
data class CrashInvestigation(
    val signalKind: LogSignal.Kind,
    val headline: String,
    val findings: List<Finding>,
    val trace: ParsedStackTrace? = null,
) {
    fun byConfidence(c: Confidence): List<Finding> = findings.filter { it.confidence == c }

    /** True when nothing beyond the raw trace was established — worth saying rather than padding. */
    val isThinlyEvidenced: Boolean
        get() = byConfidence(Confidence.CONFIRMED).size <= 1 && byConfidence(Confidence.LIKELY).isEmpty()
}

/**
 * Assembles what is actually known about a crash, sorted by how well it is known.
 *
 * The rule this enforces is structural rather than stylistic: a finding cannot be recorded without a
 * confidence, so nothing can be stated at all without also stating how sure we are. Everything read
 * straight off the trace is [Confidence.CONFIRMED]; anything requiring a step of reasoning is at most
 * [Confidence.LIKELY]; and what we could not determine is listed explicitly under
 * [Confidence.MISSING] rather than left as a silent gap the reader fills in with an assumption.
 *
 * Deliberately narrow: it draws only on the trace, the logcat window around it, and facts already
 * resolved in the Android context. It does not read source — that is Claude's job, better done with
 * this as a starting point than replaced by a heuristic here.
 */
object CrashInvestigator {

    fun investigate(
        signal: LogSignal,
        logcat: List<LogEntry>,
        trace: ParsedStackTrace?,
        appPrefixes: List<String>,
        recentlyChanged: List<String> = emptyList(),
        deviceApiLevel: Int? = null,
        variant: String? = null,
    ): CrashInvestigation {
        val findings = mutableListOf<Finding>()

        // --- Confirmed: read directly off the evidence ---
        if (trace != null) {
            val root = trace.rootCause
            findings += Finding(
                Confidence.CONFIRMED,
                "${root.throwable.substringAfterLast('.')} was thrown" +
                    (root.message?.let { ": $it" } ?: "."),
                evidence = "stack trace",
            )
            trace.blameFrame(appPrefixes)?.let { frame ->
                findings += Finding(
                    Confidence.CONFIRMED,
                    "The deepest frame in this app's code is ${frame.declaringClass}.${frame.method}" +
                        (frame.line?.let { " at line $it" } ?: "") + ".",
                    evidence = "stack frame",
                    filePath = frame.sourceFileName,
                    line = frame.line,
                )
            }
            if (trace.causes.isNotEmpty()) {
                findings += Finding(
                    Confidence.CONFIRMED,
                    "It was wrapped by ${trace.throwable.substringAfterLast('.')}" +
                        (trace.message?.let { ": $it" } ?: "."),
                    evidence = "stack trace",
                )
            }
        }
        signal.process?.let {
            findings += Finding(Confidence.CONFIRMED, "The failing process is $it.", evidence = "logcat")
        }
        deviceApiLevel?.let {
            findings += Finding(Confidence.CONFIRMED, "It happened on API level $it.", evidence = "device")
        }
        variant?.let {
            findings += Finding(Confidence.CONFIRMED, "The build variant is $it.", evidence = "build")
        }

        // --- Likely: one step of reasoning from the evidence ---
        val restarted = logcat.any { it.message.contains("has died") }
        if (restarted) {
            findings += Finding(
                Confidence.LIKELY,
                "The process died and was restarted during this window, so state held only in memory " +
                    "was lost.",
                evidence = "logcat",
            )
        }
        if (signal.kind == LogSignal.Kind.ANR) {
            findings += Finding(
                Confidence.LIKELY,
                "An ANR means the main thread was blocked for several seconds — look for work on the " +
                    "main thread rather than for a thrown exception.",
                evidence = "logcat",
            )
        }
        val strictMode = logcat.filter { it.tag == "StrictMode" }
        if (strictMode.isNotEmpty()) {
            findings += Finding(
                Confidence.LIKELY,
                "StrictMode reported ${strictMode.size} violation(s) in the same window — disk or " +
                    "network work on the main thread.",
                evidence = "logcat",
            )
        }

        // --- Possible: consistent with the evidence, not established ---
        val blameFile = trace?.blameFrame(appPrefixes)?.sourceFileName
        // Only correlate when we actually resolved a file — matching against a sentinel that can
        // never occur is the same result reached less legibly.
        val changedBlameFile = blameFile?.let { name -> recentlyChanged.firstOrNull { it.endsWith("/$name") } }
        if (changedBlameFile != null) {
            findings += Finding(
                Confidence.POSSIBLE,
                "$changedBlameFile was changed recently and appears in the trace — worth diffing first.",
                evidence = "recent change",
                filePath = changedBlameFile,
            )
        }

        // --- Missing: stated, so the reader doesn't fill the gap with an assumption ---
        if (trace == null) {
            findings += Finding(
                Confidence.MISSING,
                "No stack trace was captured. Re-run with logcat attached from before the crash.",
            )
        } else if (trace.blameFrame(appPrefixes) == null) {
            findings += Finding(
                Confidence.MISSING,
                if (appPrefixes.isEmpty())
                    "The app's package isn't known yet, so no frame could be attributed to your code."
                else "No frame in the trace belongs to this app — the failure is inside a library or " +
                    "the framework, called from somewhere this trace doesn't show.",
            )
        }
        if (deviceApiLevel == null) {
            findings += Finding(
                Confidence.MISSING,
                "The device's API level wasn't read, so version-specific behaviour can't be ruled out.",
            )
        }
        if (recentlyChanged.isEmpty()) {
            findings += Finding(
                Confidence.MISSING,
                "No recent-change list was available, so this wasn't correlated against what you edited.",
            )
        }

        return CrashInvestigation(
            signalKind = signal.kind,
            headline = trace?.let { StackTraceResolver.summarise(it, appPrefixes) } ?: signal.summary,
            findings = findings,
            trace = trace,
        )
    }

    /** Render for a prompt or a card: grouped by confidence, in descending certainty. */
    fun format(investigation: CrashInvestigation): String = buildString {
        appendLine("${investigation.signalKind.label}: ${investigation.headline}")
        for (c in Confidence.entries) {
            val group = investigation.byConfidence(c)
            if (group.isEmpty()) continue
            appendLine()
            appendLine("${c.heading}:")
            for (f in group) {
                append("  - ").append(f.statement)
                f.evidence?.let { append(" [").append(it).append("]") }
                appendLine()
            }
        }
    }.trimEnd()
}
