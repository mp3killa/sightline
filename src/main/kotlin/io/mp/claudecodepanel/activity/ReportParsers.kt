package io.mp.claudecodepanel.activity

import com.google.gson.JsonParser
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parsers for the **structured report files** Gradle/Android tools write to disk (JUnit XML, detekt /
 * ktlint Checkstyle XML, Android lint XML, SARIF) — richer and more reliable than scraping console
 * text. Platform-free (JDK `javax.xml` + bundled gson only), so it is unit-tested like the rest of
 * `activity/`. The IDE-side `BuildReportReader` locates the files and feeds these results into the graph.
 *
 * XML is parsed with DTDs and external entities disabled (report files are machine-generated, but they
 * are still untrusted input on disk).
 */
object ReportParsers {

    /** What a single report file yielded. */
    data class ReportResult(
        val tests: OutputParsers.TestSummary?,
        val diagnostics: List<OutputParsers.Diagnostic>,
    ) {
        val isEmpty: Boolean get() = tests == null && diagnostics.isEmpty()
    }

    val EMPTY = ReportResult(null, emptyList())

    /** Dispatches by file name / content sniff to the right parser. */
    fun parseReportFile(fileName: String, content: String): ReportResult {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".sarif") -> ReportResult(null, parseSarif(content))
            content.contains("<testsuite") -> ReportResult(parseJUnitXml(content), emptyList())
            content.contains("<issues") -> ReportResult(null, parseAndroidLintXml(content))
            content.contains("<checkstyle") || content.contains("<file ") -> ReportResult(null, parseCheckstyleXml(content))
            else -> EMPTY
        }
    }

    /** Sums every `<testsuite>` in [xml] (a file may hold one; a run holds many). Null if none. */
    fun parseJUnitXml(xml: String): OutputParsers.TestSummary? {
        val doc = parseXml(xml) ?: return null
        val suites = doc.getElementsByTagName("testsuite")
        if (suites.length == 0) return null
        var tests = 0; var failures = 0; var errors = 0; var skipped = 0
        for (i in 0 until suites.length) {
            val s = suites.item(i) as? Element ?: continue
            tests += s.intAttr("tests"); failures += s.intAttr("failures")
            errors += s.intAttr("errors"); skipped += s.intAttr("skipped")
        }
        val failedNames = ArrayList<String>()
        val cases = doc.getElementsByTagName("testcase")
        for (i in 0 until cases.length) {
            val c = cases.item(i) as? Element ?: continue
            val failed = c.getElementsByTagName("failure").length > 0 || c.getElementsByTagName("error").length > 0
            if (!failed) continue
            val cls = c.getAttribute("classname").trim()
            val name = c.getAttribute("name").trim()
            failedNames.add(if (cls.isNotEmpty()) "$cls > $name" else name)
            if (failedNames.size >= 50) break
        }
        val failed = failures + errors
        val passed = (tests - failed - skipped).coerceAtLeast(0)
        return OutputParsers.TestSummary(passed = passed, failed = failed, failedNames = failedNames)
    }

    /** Checkstyle-format XML emitted by **detekt** and **ktlint**: `<file name><error line severity message/>`. */
    fun parseCheckstyleXml(xml: String): List<OutputParsers.Diagnostic> {
        val doc = parseXml(xml) ?: return emptyList()
        val out = ArrayList<OutputParsers.Diagnostic>()
        val files = doc.getElementsByTagName("file")
        for (i in 0 until files.length) {
            val f = files.item(i) as? Element ?: continue
            val path = f.getAttribute("name").trim().ifBlank { null }
            val errs = f.getElementsByTagName("error")
            for (j in 0 until errs.length) {
                val e = errs.item(j) as? Element ?: continue
                out.add(
                    OutputParsers.Diagnostic(
                        path = path,
                        line = e.getAttribute("line").toIntOrNull(),
                        message = e.getAttribute("message").trim(),
                        isError = e.getAttribute("severity").equals("error", ignoreCase = true),
                    ),
                )
                if (out.size >= 100) return out
            }
        }
        return out
    }

    /** Android lint XML: `<issues><issue severity message><location file line/></issue></issues>`. */
    fun parseAndroidLintXml(xml: String): List<OutputParsers.Diagnostic> {
        val doc = parseXml(xml) ?: return emptyList()
        val out = ArrayList<OutputParsers.Diagnostic>()
        val issues = doc.getElementsByTagName("issue")
        for (i in 0 until issues.length) {
            val issue = issues.item(i) as? Element ?: continue
            val severity = issue.getAttribute("severity")
            val message = issue.getAttribute("message").trim()
            val loc = issue.getElementsByTagName("location").item(0) as? Element
            out.add(
                OutputParsers.Diagnostic(
                    path = loc?.getAttribute("file")?.trim()?.ifBlank { null },
                    line = loc?.getAttribute("line")?.toIntOrNull(),
                    message = message,
                    // lint severities: Fatal/Error → error; Warning/Information → warning.
                    isError = severity.equals("Error", ignoreCase = true) || severity.equals("Fatal", ignoreCase = true),
                ),
            )
            if (out.size >= 100) break
        }
        return out
    }

    /** SARIF 2.x (`runs[].results[]`) — the common format detekt/lint/ktlint can all emit. */
    fun parseSarif(json: String): List<OutputParsers.Diagnostic> {
        val out = ArrayList<OutputParsers.Diagnostic>()
        val root = try { JsonParser.parseString(json).asJsonObject } catch (e: Exception) { return emptyList() }
        val runs = root.getAsJsonArray("runs") ?: return emptyList()
        for (runEl in runs) {
            val run = runEl.asJsonObjOrNull() ?: continue
            val results = run.getAsJsonArray("results") ?: continue
            for (rEl in results) {
                val r = rEl.asJsonObjOrNull() ?: continue
                val level = r.get("level")?.takeIf { it.isJsonPrimitive }?.asString ?: "warning"
                val message = r.getAsJsonObject("message")?.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val loc = r.getAsJsonArray("locations")?.firstOrNull()?.asJsonObjOrNull()
                    ?.getAsJsonObject("physicalLocation")
                val uri = loc?.getAsJsonObject("artifactLocation")?.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
                val line = loc?.getAsJsonObject("region")?.get("startLine")?.takeIf { it.isJsonPrimitive }?.asInt
                out.add(
                    OutputParsers.Diagnostic(
                        path = uri?.removePrefix("file://")?.ifBlank { null },
                        line = line,
                        message = message.trim(),
                        isError = level.equals("error", ignoreCase = true),
                    ),
                )
                if (out.size >= 100) return out
            }
        }
        return out
    }

    // ---- helpers ----

    private fun parseXml(xml: String): org.w3c.dom.Document? {
        return try {
            val f = DocumentBuilderFactory.newInstance()
            secure(f)
            f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            null
        }
    }

    private fun secure(f: DocumentBuilderFactory) {
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
        )) runCatching { f.setFeature(feature, true) }
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) runCatching { f.setFeature(feature, false) }
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
    }

    private fun Element.intAttr(name: String): Int = getAttribute(name).toIntOrNull() ?: 0

    private fun com.google.gson.JsonElement.asJsonObjOrNull(): com.google.gson.JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun com.google.gson.JsonArray.firstOrNull(): com.google.gson.JsonElement? =
        if (size() > 0) get(0) else null
}
