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
        val toParse = preferXmlOverSarif(files)
        val diagnostics = ArrayList<OutputParsers.Diagnostic>()
        for (f in toParse) {
            val text = readTextBounded(f) ?: continue
            val res = ReportParsers.parseReportFile(f.name, text)
            res.tests?.let { summaries.add(it) }
            diagnostics.addAll(res.diagnostics)
        }
        return buildEvents(root, summaries, diagnostics, now)
    }

    /**
     * A tool that emits the same report as both XML and SARIF (Android lint writes
     * `lint-results-debug.xml` and `lint-results-debug.sarif`) would otherwise be read twice — and the
     * two formats disagree on line numbers, so dedup can't collapse them. Drop the SARIF when an
     * identically-named XML sibling exists; keep SARIF only where it's the sole format.
     */
    private fun preferXmlOverSarif(files: List<File>): List<File> {
        val xmlKeys = files.asSequence()
            .filter { it.name.endsWith(".xml", ignoreCase = true) }
            .map { it.parent + "/" + it.nameWithoutExtension }
            .toHashSet()
        return files.filter { f ->
            !(f.name.endsWith(".sarif", ignoreCase = true) && (f.parent + "/" + f.nameWithoutExtension) in xmlKeys)
        }
    }

    /**
     * Resolves a report-relative path to an absolute one against the project root (absolute paths are
     * kept). Real tools disagree: Android lint's XML uses project-relative paths while its SARIF uses
     * absolute ones — normalising both lets the same finding dedup and attach to the same file node.
     */
    private fun normalizePath(root: File, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val f = File(raw)
        val abs = if (f.isAbsolute) f else File(root, raw)
        return try { abs.canonicalPath } catch (e: Exception) { abs.path }
    }

    /** Only worth scanning after a Gradle / test / static-analysis command. */
    private fun shouldScan(command: String): Boolean =
        OutputParsers.looksLikeGradle(command) || OutputParsers.isTestCommand(command) ||
            OutputParsers.analysisTool(command) != null

    private fun buildEvents(
        root: File,
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
        // Normalise paths first, so the same finding emitted in two report formats (lint's relative XML
        // + absolute SARIF) collapses to one event and attaches to the same file node.
        val seen = HashSet<String>()
        for (d in diagnostics) {
            val path = normalizePath(root, d.path)
            if (!seen.add("$path|${d.line}|${d.message}")) continue
            out.add(if (d.isError) ErrorObserved(path, d.message, now) else WarningObserved(path, d.message, now))
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
