package io.mp.claudecodepanel.activity

import java.io.File
import java.time.Instant

/**
 * Reads the **structured report files** a Gradle/Android command wrote to disk (JUnit XML, detekt/
 * ktlint Checkstyle XML, Android lint XML, SARIF) and turns them into activity events — richer and
 * more reliable than scraping console output. Platform-free (java.io + [ReportParsers]); the UI runs
 * it on a background thread after a build/test/analysis command finishes.
 *
 * Only files modified at/after the command started (minus a small clock skew) are read, so stale
 * reports from earlier runs are ignored. The walk is bounded: prunes source/noise dirs, only descends
 * `build/test-results` and `build/reports` inside a `build/` dir, and caps files, size and events.
 */
class BuildReportScanner {

    fun scan(root: File, command: String, sinceMillis: Long, now: Instant = Instant.now()): List<AgentActivityEvent> {
        if (!shouldScan(command) || !root.isDirectory) return emptyList()
        val files = ArrayList<File>()
        walk(root, 0, files, sinceMillis - SKEW_MS)
        if (files.isEmpty()) return emptyList()

        val summaries = ArrayList<OutputParsers.TestSummary>()
        val diagnostics = ArrayList<OutputParsers.Diagnostic>()
        for (f in files) {
            val text = readTextBounded(f) ?: continue
            val res = ReportParsers.parseReportFile(f.name, text)
            res.tests?.let { summaries.add(it) }
            diagnostics.addAll(res.diagnostics)
        }
        return buildEvents(summaries, diagnostics, now)
    }

    /** Only worth scanning after a Gradle / test / static-analysis command. */
    private fun shouldScan(command: String): Boolean =
        OutputParsers.looksLikeGradle(command) || OutputParsers.isTestCommand(command) ||
            OutputParsers.analysisTool(command) != null

    private fun buildEvents(
        summaries: List<OutputParsers.TestSummary>,
        diagnostics: List<OutputParsers.Diagnostic>,
        now: Instant,
    ): List<AgentActivityEvent> {
        val out = ArrayList<AgentActivityEvent>()
        if (summaries.isNotEmpty()) {
            val passed = summaries.sumOf { it.passed }
            val failed = summaries.sumOf { it.failed }
            val names = summaries.flatMap { it.failedNames }.distinct().take(50)
            if (passed > 0 || failed > 0) out.add(TestReported(passed, failed, names, now))
        }
        val seen = HashSet<String>()
        for (d in diagnostics) {
            if (!seen.add("${d.path}|${d.line}|${d.message}")) continue
            out.add(if (d.isError) ErrorObserved(d.path, d.message, now) else WarningObserved(d.path, d.message, now))
            if (out.size >= MAX_EVENTS) break
        }
        return out
    }

    private fun walk(dir: File, depth: Int, out: MutableList<File>, sinceMillis: Long) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (out.size >= MAX_FILES) return
            if (c.isDirectory) {
                val name = c.name
                if (name.startsWith(".") || name in PRUNE_DIRS) continue
                // Inside a build dir, only the report subtrees are interesting — never `intermediates`.
                if (dir.name == "build" && name != "test-results" && name != "reports") continue
                walk(c, depth + 1, out, sinceMillis)
            } else if (isReportFile(c) && c.lastModified() >= sinceMillis) {
                out.add(c)
            }
        }
    }

    private fun isReportFile(f: File): Boolean {
        val p = f.path.replace('\\', '/')
        if (!p.contains("/build/test-results/") && !p.contains("/build/reports/")) return false
        val n = f.name.lowercase()
        return n.endsWith(".xml") || n.endsWith(".sarif")
    }

    private fun readTextBounded(f: File): String? = try {
        if (f.length() > MAX_FILE_BYTES) null else f.readText()
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_FILES = 400
        private const val MAX_EVENTS = 100
        private const val MAX_FILE_BYTES = 12L * 1024 * 1024
        private const val SKEW_MS = 3000L
        private val PRUNE_DIRS = setOf("src", "node_modules", ".gradle", ".git", ".idea", ".kotlin", ".cxx", "gradle")
    }
}
