package io.mp.claudecodepanel.activity

/**
 * Pure parsers that turn raw shell/Gradle/compiler/test output text into structured facts.
 * Conservative on purpose: only well-known patterns are recognised, so a stray word in prose
 * never fabricates a test result or an error.
 */
object OutputParsers {

    data class TestSummary(val passed: Int, val failed: Int, val failedNames: List<String>)
    data class Diagnostic(val path: String?, val line: Int?, val message: String, val isError: Boolean)

    // ---- commands ----

    fun looksLikeGradle(command: String): Boolean {
        val c = command.trim()
        return c.contains("gradlew") || Regex("(^|\\s|&&|;|\\|)gradle(\\s|$)").containsMatchIn(c)
    }

    fun isTestCommand(command: String): Boolean {
        val c = command.lowercase()
        if (looksLikeGradle(command) && Regex(":?\\w*test\\w*").containsMatchIn(c)) return true
        // Android instrumented/connected tests don't always contain the word "test".
        if (c.contains("connectedcheck") || c.contains("connectedandroidtest")) return true
        return c.contains(" test") || c.endsWith(" test") || c.contains("./gradlew test") ||
            c.contains("junit") || c.contains("pytest") || c.contains("go test") || c.contains("npm test") ||
            c.contains("npm run test") || c.contains("yarn test")
    }

    /**
     * The `adb` sub-action if [command] invokes adb (`install`/`uninstall`/`shell`/`logcat`/…), else
     * null. Anchored: a command segment must *start* with adb (optionally a path to it), so prose that
     * merely mentions "adb" isn't mistaken for a command. Flags like `-s <serial>`, `-d`, `-e` between
     * `adb` and the sub-command are skipped.
     */
    fun adbAction(command: String): String? {
        val seg = command.split("&&", ";", "|").map { it.trim() }.firstOrNull {
            val first = it.split(Regex("\\s+")).firstOrNull() ?: ""
            first == "adb" || first.substringAfterLast('/') == "adb"
        } ?: return null
        val tokens = seg.split(Regex("\\s+"))
        var j = 1
        while (j < tokens.size) {
            val t = tokens[j]
            if (t == "-s" || t == "-p" || t == "-P" || t == "-H" || t == "-L") { j += 2; continue }
            if (t.startsWith("-")) { j++; continue }
            return t.lowercase()
        }
        return "adb"
    }

    /** A human phrase for an adb command, for the focus card / status strip. */
    fun adbDescription(command: String): String = when (adbAction(command)) {
        "install", "install-multiple" -> "Installing app (adb)"
        "uninstall" -> "Uninstalling app (adb)"
        "logcat" -> "Reading logcat (adb)"
        "devices" -> "Listing devices (adb)"
        "push" -> "Pushing file (adb)"
        "pull" -> "Pulling file (adb)"
        "shell" -> if (command.contains("am start")) "Launching app (adb)" else "adb shell"
        else -> "adb"
    }

    /** Which static-analysis tool [command] runs (`detekt`/`ktlint`/`lint`), or null. */
    fun analysisTool(command: String): String? {
        val c = command.lowercase()
        return when {
            c.contains("detekt") -> "detekt"
            c.contains("ktlint") -> "ktlint"
            // Android lint gradle task (:app:lintDebug) or a bare `lint` — but not "ktlint"/"sublint".
            Regex("(^|\\s|:)lint\\w*").containsMatchIn(c) -> "lint"
            else -> null
        }
    }

    /** Extracts Gradle task tokens (`./gradlew clean :app:testDebug` -> ["clean",":app:testDebug"]). */
    fun extractGradleTasks(command: String): List<String> {
        if (!looksLikeGradle(command)) return emptyList()
        // Take the segment of the command containing the gradle invocation (before && / ; / |).
        val segment = command.split("&&", ";", "|").firstOrNull { looksLikeGradle(it) } ?: command
        val tokens = segment.trim().split(Regex("\\s+"))
        val start = tokens.indexOfFirst { it.contains("gradle") }
        if (start < 0) return emptyList()
        return tokens.drop(start + 1)
            .filter { it.isNotBlank() && !it.startsWith("-") && !it.contains("=") }
    }

    // ---- results ----

    /** Recognises "SUCCESS"/"FAILED" build lines. Null when the text isn't a build outcome. */
    fun parseBuildOutcome(text: String): Boolean? {
        return when {
            text.contains("BUILD SUCCESSFUL") -> true
            text.contains("BUILD FAILED") -> false
            else -> null
        }
    }

    private val gradleCount = Regex("(\\d+)\\s+tests?\\s+completed(?:,\\s*(\\d+)\\s+failed)?", RegexOption.IGNORE_CASE)
    private val junitCount = Regex("Tests run:\\s*(\\d+).*?Failures:\\s*(\\d+).*?Errors:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val pytestCount = Regex("(?:(\\d+)\\s+passed)?(?:,\\s*)?(?:(\\d+)\\s+failed)?", RegexOption.IGNORE_CASE)
    // "com.example.FooTest > does something FAILED"  /  "FooTest.bar FAILED"
    private val failedName = Regex("([\\w.]+(?:\\s*>\\s*[^\\n]+?|\\.[\\w$]+))\\s+FAILED")

    /** Parses a test run summary from Gradle/JUnit/pytest output. Null when no test totals appear. */
    fun parseTestSummary(text: String): TestSummary? {
        val failedNames = failedName.findAll(text)
            .map { it.groupValues[1].trim().replace(Regex("\\s+"), " ") }
            .distinct().take(50).toList()

        junitCount.find(text)?.let { m ->
            val run = m.groupValues[1].toInt()
            val fails = m.groupValues[2].toInt() + m.groupValues[3].toInt()
            return TestSummary(passed = (run - fails).coerceAtLeast(0), failed = fails, failedNames = failedNames)
        }
        gradleCount.find(text)?.let { m ->
            val total = m.groupValues[1].toInt()
            val fails = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return TestSummary(passed = (total - fails).coerceAtLeast(0), failed = fails, failedNames = failedNames)
        }
        if (text.contains("passed", ignoreCase = true) && text.contains("failed", ignoreCase = true)) {
            pytestCount.find(text)?.let { m ->
                val passed = m.groupValues[1].toIntOrNull() ?: 0
                val failed = m.groupValues[2].toIntOrNull() ?: 0
                if (passed > 0 || failed > 0) return TestSummary(passed, failed, failedNames)
            }
        }
        // A bare list of "X FAILED" lines with no totals still tells us tests failed.
        if (failedNames.isNotEmpty()) return TestSummary(passed = 0, failed = failedNames.size, failedNames = failedNames)
        return null
    }

    // Kotlin: "e: /path/File.kt: (12, 5): message"  or  "w: file:///path: (..)"
    private val kotlinDiag = Regex("^([ew]):\\s*(?:file://)?([^:\\n]+?):\\s*\\((\\d+),\\s*\\d+\\):\\s*(.*)$", RegexOption.MULTILINE)
    // javac / Android lint / generic: "/path/File.java:12: error: msg", ":12:5: warning: msg",
    // "src/main/AndroidManifest.xml:9: Warning: msg [Rule]" (case-insensitive for lint's "Warning:").
    private val genericDiag = Regex(
        "^([^\\s:][^:\\n]*?):(\\d+)(?::\\d+)?:\\s*(error|warning):\\s*(.*)$",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )
    // detekt / ktlint: "path/File.kt:10:5: message [Rule]" — no severity word. Requires a source-file
    // extension so it can't match prose; applied only to known static-analysis output.
    private val analysisDiag = Regex(
        "^\\s*([\\w./\\\\-]+\\.(?:kt|kts|java|xml)):(\\d+)(?::(\\d+))?:?\\s*(\\S.*)$",
        RegexOption.MULTILINE,
    )

    /** Parses compiler diagnostics (Kotlin `e:`/`w:` and javac `path:line: error:`). */
    fun parseCompilerDiagnostics(text: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (m in kotlinDiag.findAll(text)) {
            out.add(
                Diagnostic(
                    path = m.groupValues[2].trim().ifBlank { null },
                    line = m.groupValues[3].toIntOrNull(),
                    message = m.groupValues[4].trim(),
                    isError = m.groupValues[1] == "e",
                ),
            )
        }
        for (m in genericDiag.findAll(text)) {
            out.add(
                Diagnostic(
                    path = m.groupValues[1].trim().ifBlank { null },
                    line = m.groupValues[2].toIntOrNull(),
                    message = m.groupValues[4].trim(),
                    isError = m.groupValues[3].equals("error", ignoreCase = true),
                ),
            )
        }
        return out.take(50)
    }

    /**
     * Diagnostics from static-analysis tools (detekt / ktlint / Android lint), which print
     * `path:line[:col]: message` — often without a severity keyword. Conservative: only lines whose
     * path has a source extension match, and severity defaults to warning unless the message clearly
     * signals an error. Call this only for output known to come from an analysis command.
     */
    fun parseAnalysisDiagnostics(text: String): List<Diagnostic> {
        // Prefer the richer compiler/lint parse when a severity keyword is actually present.
        val severityBased = parseCompilerDiagnostics(text)
        val seen = severityBased.mapNotNull { it.path?.let { p -> p to it.line } }.toMutableSet()
        val out = ArrayList(severityBased)
        for (m in analysisDiag.findAll(text)) {
            val path = m.groupValues[1].trim().ifBlank { null }
            val line = m.groupValues[2].toIntOrNull()
            if (path != null && (path to line) in seen) continue // don't double-count severity-tagged lines
            val message = m.groupValues[4].trim()
            val isError = message.startsWith("error", ignoreCase = true) || message.contains("error:", ignoreCase = true)
            out.add(Diagnostic(path, line, message, isError))
            if (path != null) seen.add(path to line)
            if (out.size >= 50) break
        }
        return out.take(50)
    }
}
