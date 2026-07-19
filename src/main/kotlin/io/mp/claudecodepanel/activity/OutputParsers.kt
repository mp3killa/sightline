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
        return c.contains(" test") || c.endsWith(" test") || c.contains("./gradlew test") ||
            c.contains("junit") || c.contains("pytest") || c.contains("go test") || c.contains("npm test") ||
            c.contains("npm run test") || c.contains("yarn test")
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
    // javac / generic: "/path/File.java:12: error: message"  or  ":12:5: warning: msg"
    private val genericDiag = Regex("^([^\\s:][^:\\n]*?):(\\d+)(?::\\d+)?:\\s*(error|warning):\\s*(.*)$", RegexOption.MULTILINE)

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
}
