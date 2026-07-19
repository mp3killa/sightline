package io.mp.claudecodepanel.activity

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/**
 * Turns Claude's raw, observable stream/tool events into normalised [AgentActivityEvent]s.
 *
 * Structured tool events (Read/Edit/Bash/…) become high-confidence facts. A tool's later result
 * is correlated back to its `tool_use_id` so command output can be parsed for tests, compiler
 * errors and build outcomes. Free-form assistant prose is deliberately NOT mined for file names —
 * only an explicit tool call can create a file/edit node.
 *
 * Stateful (it remembers pending tool calls by id) but deterministic; inject [clock] in tests.
 */
class ActivityInterpreter(private val clock: () -> Instant = Instant::now) {

    private enum class Kind { READ, EDIT, WRITE, SEARCH, BASH, GRADLE, TEST, ANALYSIS, SYMBOL, DIAGNOSTICS, WEB, OTHER }
    private data class Pending(val kind: Kind, val name: String, val path: String?, val command: String?)

    private val pending = HashMap<String, Pending>()

    fun reset() = pending.clear()

    fun taskStarted(text: String): List<AgentActivityEvent> = listOf(TaskStarted(text.trim(), clock()))

    /** A streamed status verb — focus-card only. */
    fun status(verb: String, detail: String? = null): List<AgentActivityEvent> =
        listOf(StatusUpdated(verb, detail, clock()))

    fun taskDone(summary: String, isError: Boolean): List<AgentActivityEvent> =
        listOf(TaskCompleted(summary, isError, clock()))

    /**
     * The user denied (or cancelled) a pending tool. Correlated to its tool_use via [id]; the
     * remembered pending entry supplies the path/command so the graph can find and block the node it
     * created. Removing the pending entry also prevents a late result from re-animating it.
     */
    fun toolDenied(id: String?, toolName: String, input: JsonObject?, cancelled: Boolean = false): List<AgentActivityEvent> {
        val p = id?.let { pending.remove(it) }
        val inp = input ?: JsonObject()
        val path = p?.path ?: inp.str("file_path") ?: inp.str("notebook_path")
            ?: inp.str("new_file_path") ?: inp.str("old_file_path")
        val command = p?.command ?: inp.str("command")
        return listOf(ActivityDenied(id, toolName, path, command, cancelled, clock()))
    }

    /** A tool_use block: name + parsed input + optional id (id lets us correlate the result). */
    fun toolUse(id: String?, rawName: String, input: JsonObject?): List<AgentActivityEvent> {
        val name = rawName
        val inp = input ?: JsonObject()
        val now = clock()
        val out = ArrayList<AgentActivityEvent>()

        fun remember(kind: Kind, path: String? = null, command: String? = null) {
            if (id != null) pending[id] = Pending(kind, name, path, command)
        }

        when (name) {
            "Read" -> inp.str("file_path")?.let { p ->
                remember(Kind.READ, p); out.add(FileRead(p, now))
            }
            "Edit", "MultiEdit" -> inp.str("file_path")?.let { p ->
                remember(Kind.EDIT, p); out.add(FileEdited(p, created = false, at = now))
            }
            "Write" -> inp.str("file_path")?.let { p ->
                remember(Kind.WRITE, p); out.add(FileEdited(p, created = true, at = now))
            }
            "NotebookEdit" -> inp.str("notebook_path")?.let { p ->
                remember(Kind.EDIT, p); out.add(FileEdited(p, created = false, at = now))
            }
            "Grep", "Glob" -> {
                val q = inp.str("pattern") ?: inp.str("query") ?: ""
                remember(Kind.SEARCH, inp.str("path"))
                out.add(FileSearched(q, inp.str("path"), now))
            }
            "Bash", "BashOutput" -> {
                val cmd = inp.str("command") ?: ""
                if (cmd.isNotBlank()) interpretCommand(id, cmd, inp.str("description"), now, out, ::remember)
            }
            "WebFetch" -> inp.str("url")?.let { remember(Kind.WEB); out.add(WebActivity(shorten(it), now)) }
            "WebSearch" -> inp.str("query")?.let { remember(Kind.WEB); out.add(WebActivity(shorten(it), now)) }
            "TodoWrite" -> out.add(StatusUpdated("Planning", todoSummary(inp), now, confidence = 0.6f))
            else -> interpretMcp(id, name, inp, now, out, ::remember)
        }
        return out
    }

    /** A tool_result block, correlated to its tool_use by [id]. Parses command output. */
    fun toolResult(id: String?, text: String, isError: Boolean): List<AgentActivityEvent> {
        val now = clock()
        val p = id?.let { pending.remove(it) }
        val out = ArrayList<AgentActivityEvent>()

        // Command/build/test output is the richest post-hoc signal.
        if (p != null && (p.kind == Kind.BASH || p.kind == Kind.GRADLE || p.kind == Kind.TEST)) {
            OutputParsers.parseTestSummary(text)?.let {
                out.add(TestReported(it.passed, it.failed, it.failedNames, now))
            }
            for (d in OutputParsers.parseCompilerDiagnostics(text)) {
                if (d.isError) out.add(ErrorObserved(d.path, d.message, now))
                else out.add(WarningObserved(d.path, d.message, now))
            }
            OutputParsers.parseBuildOutcome(text)?.let { ok ->
                out.add(BuildReported(ok, firstLine(text), now))
            }
        }

        // Static-analysis output (lint/detekt/ktlint): attach findings to the files they name.
        if (p != null && p.kind == Kind.ANALYSIS) {
            for (d in OutputParsers.parseAnalysisDiagnostics(text)) {
                if (d.isError) out.add(ErrorObserved(d.path, d.message, now))
                else out.add(WarningObserved(d.path, d.message, now))
            }
            OutputParsers.parseBuildOutcome(text)?.let { ok -> out.add(BuildReported(ok, firstLine(text), now)) }
        }

        if (isError) {
            // Attribute the failure to the file we know this tool touched, if any.
            val path = p?.path
            if (out.none { it is ErrorObserved }) out.add(ErrorObserved(path, firstLine(text), now))
        }
        return out
    }

    // ---- helpers ----

    private fun interpretCommand(
        id: String?, cmd: String, desc: String?, now: Instant,
        out: MutableList<AgentActivityEvent>,
        remember: (Kind, String?, String?) -> Unit,
    ) {
        val adb = OutputParsers.adbAction(cmd)
        val analysis = OutputParsers.analysisTool(cmd)
        val gradleTasks = OutputParsers.extractGradleTasks(cmd)
        val isTest = OutputParsers.isTestCommand(cmd)
        when {
            adb != null -> {
                remember(Kind.BASH, null, cmd)
                out.add(CommandRun(cmd, desc ?: OutputParsers.adbDescription(cmd), now))
            }
            analysis != null -> {
                // Static analysis (lint/detekt/ktlint) — its output attaches diagnostics to files.
                remember(Kind.ANALYSIS, null, cmd)
                if (gradleTasks.isNotEmpty()) gradleTasks.forEach { out.add(GradleTaskRun(it, now)) }
                else out.add(CommandRun(cmd, desc ?: "$analysis static analysis", now))
            }
            gradleTasks.isNotEmpty() -> {
                remember(if (isTest) Kind.TEST else Kind.GRADLE, null, cmd)
                // A gradle *test* task is a single Testing-cluster node (avoid a duplicate gradle +
                // test node with the same label); other gradle tasks are Build-cluster nodes.
                if (isTest) out.add(TestStarted(gradleTasks.joinToString(" "), now))
                else gradleTasks.forEach { out.add(GradleTaskRun(it, now)) }
            }
            isTest -> {
                remember(Kind.TEST, null, cmd)
                out.add(CommandRun(cmd, desc, now))
                out.add(TestStarted(null, now))
            }
            else -> {
                remember(Kind.BASH, null, cmd)
                out.add(CommandRun(cmd, desc, now))
            }
        }
    }

    private fun interpretMcp(
        id: String?, name: String, inp: JsonObject, now: Instant,
        out: MutableList<AgentActivityEvent>,
        remember: (Kind, String?, String?) -> Unit,
    ) {
        val short = name.substringAfterLast("__")
        when {
            // IDE / studio file reads.
            short == "read_file" || short == "get_file_text_by_path" || short == "open_file_in_editor" ||
                short == "openFile" ->
                (inp.str("path") ?: inp.str("filePath") ?: inp.str("absolute_path"))?.let {
                    remember(Kind.READ, it, null); out.add(FileRead(it, now))
                }
            // IDE / studio edits.
            short == "replace_text_in_file" || short == "apply_patch" || short == "create_new_file" ->
                (inp.str("path") ?: inp.str("filePath") ?: inp.str("pathInProject"))?.let {
                    remember(Kind.WRITE, it, null)
                    out.add(FileEdited(it, created = short == "create_new_file", at = now))
                }
            short == "openDiff" ->
                (inp.str("new_file_path") ?: inp.str("old_file_path"))?.let {
                    remember(Kind.EDIT, it, null); out.add(FileEdited(it, created = false, at = now))
                }
            // Symbol / navigation lookups.
            short == "get_symbol_info" || short == "search_symbol" || short == "rename_refactoring" ->
                (inp.str("symbolName") ?: inp.str("name") ?: inp.str("query") ?: inp.str("text"))?.let {
                    remember(Kind.SYMBOL, inp.str("filePath"), null)
                    out.add(SymbolInspected(it, inp.str("filePath"), now))
                }
            // Searches.
            short.startsWith("search_") || short.startsWith("find_files") ->
                out.add(FileSearched(inp.str("text") ?: inp.str("query") ?: inp.str("nameKeyword") ?: "", inp.str("directory"), now))
            // Build / run.
            short == "build_project" || short == "execute_run_configuration" -> {
                remember(Kind.GRADLE, null, name)
                out.add(GradleTaskRun(short, now))
            }
            short == "get_file_problems" || short == "get_project_problems" ->
                out.add(StatusUpdated("Reviewing problems", inp.str("filePath"), now, confidence = 0.7f))
            else -> out.add(ToolInvoked(name, short, now))
        }
    }

    private fun todoSummary(inp: JsonObject): String {
        val todos = inp.arrOrNull("todos") ?: return "updating plan"
        val done = todos.count { it.isJsonObject && it.asJsonObject.str("status") == "completed" }
        return "$done/${todos.size()} done"
    }

    private fun firstLine(text: String): String {
        val t = text.trim()
        val nl = t.indexOf('\n')
        val line = if (nl >= 0) t.substring(0, nl) else t
        return shorten(line)
    }

    private fun shorten(s: String, max: Int = 120): String =
        if (s.length > max) s.substring(0, max) + "…" else s

    private fun JsonObject.str(k: String): String? =
        if (has(k) && get(k).isJsonPrimitive) get(k).asString.takeIf { it.isNotBlank() } else null

    private fun JsonObject.arrOrNull(k: String): JsonArray? =
        if (has(k) && get(k).isJsonArray) getAsJsonArray(k) else null
}
