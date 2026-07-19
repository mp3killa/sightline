package io.mp.claudecodepanel.ui.state

import io.mp.claudecodepanel.activity.ActivityDenied
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.BuildReported
import io.mp.claudecodepanel.activity.CommandRun
import io.mp.claudecodepanel.activity.ErrorObserved
import io.mp.claudecodepanel.activity.FileEdited
import io.mp.claudecodepanel.activity.FileRead
import io.mp.claudecodepanel.activity.FilePackage
import io.mp.claudecodepanel.activity.FileSearched
import io.mp.claudecodepanel.activity.GradleTaskRun
import io.mp.claudecodepanel.activity.StructuralRelation
import io.mp.claudecodepanel.activity.StatusUpdated
import io.mp.claudecodepanel.activity.SymbolInspected
import io.mp.claudecodepanel.activity.TaskCompleted
import io.mp.claudecodepanel.activity.TaskStarted
import io.mp.claudecodepanel.activity.TestReported
import io.mp.claudecodepanel.activity.TestStarted
import io.mp.claudecodepanel.activity.ToolInvoked
import io.mp.claudecodepanel.activity.WarningObserved
import io.mp.claudecodepanel.activity.WebActivity
import java.time.Instant

/** Semantic status kinds — the UI maps these to a dot colour / small icon. */
enum class StatusKind { READY, WORKING, READING, EDITING, SEARCHING, RUNNING, TESTING, SUCCESS, WARNING, ERROR, PERMISSION, COMPLETED }

/** An immutable snapshot of the coordinated status shown in the strip and the header state dot. */
data class StatusView(
    val kind: StatusKind,
    val primary: String,
    val secondary: String?,
    val priority: Int,
    val animated: Boolean,
    val at: Instant,
)

/**
 * Coordinates a single status from the **same normalised activity-event stream** that feeds the
 * graph, so the strip, the header state dot and the graph focus never disagree.
 *
 * Priority (lower value wins): permission < outcome (error/test/build) < tool activity <
 * streamed status < generic working < completed < ready. Turn boundaries ([taskStarted],
 * [TaskCompleted]) and [permissionRequested]/[permissionResolved] are explicit transitions that
 * always take effect; every other event competes by priority so a lower-priority generic event
 * (e.g. a late "Thinking") can never overwrite richer context (e.g. "Editing ClaudePanel.kt").
 *
 * Platform-free and deterministic (inject [clock]) so it is fully unit-tested.
 */
class StatusModel(private val clock: () -> Instant = Instant::now) {

    var view: StatusView = ready(clock()); private set
    private var savedBeforePermission: StatusView? = null

    fun reset(): StatusView {
        savedBeforePermission = null
        view = ready(clock())
        return view
    }

    fun taskStarted(): StatusView = force(StatusView(StatusKind.WORKING, WORKING_FALLBACK, null, P_WORKING, true, clock()))

    fun permissionRequested(): StatusView {
        if (view.priority > P_PERMISSION) savedBeforePermission = view
        return force(StatusView(StatusKind.PERMISSION, "Waiting for approval", null, P_PERMISSION, true, clock()))
    }

    fun permissionResolved(): StatusView {
        val restore = savedBeforePermission ?: StatusView(StatusKind.WORKING, WORKING_FALLBACK, null, P_WORKING, true, clock())
        savedBeforePermission = null
        return force(restore.copy(at = clock()))
    }

    /** Feed one normalised event; returns the (possibly unchanged) current [view]. */
    fun apply(event: AgentActivityEvent): StatusView {
        val now = clock()
        return when (event) {
            is TaskStarted -> force(StatusView(StatusKind.WORKING, WORKING_FALLBACK, null, P_WORKING, true, now))
            is TaskCompleted -> {
                savedBeforePermission = null
                val kind = if (event.isError) StatusKind.WARNING else StatusKind.SUCCESS
                force(StatusView(kind, event.summary.ifBlank { if (event.isError) "Stopped" else "Done" }, null, P_COMPLETED, false, now))
            }
            is StatusUpdated -> {
                if (event.verb.equals("Thinking", ignoreCase = true)) {
                    offer(StatusView(StatusKind.WORKING, WORKING_FALLBACK, null, P_WORKING, true, now))
                } else {
                    offer(StatusView(StatusKind.WORKING, event.verb, event.detail?.takeIf { it.isNotBlank() }, P_STREAM, true, now))
                }
            }
            is FileRead -> offer(tool(StatusKind.READING, "Reading " + base(event.path), null, now))
            is FileEdited -> {
                val verb = when { event.deleted -> "Deleting "; event.created -> "Creating "; else -> "Editing " }
                offer(tool(StatusKind.EDITING, verb + base(event.path), null, now))
            }
            is FileSearched -> offer(tool(StatusKind.SEARCHING, "Searching " + short(event.query.ifBlank { "project" }, 40), event.path?.let { base(it) }, now))
            is SymbolInspected -> offer(tool(StatusKind.READING, "Inspecting " + event.name, null, now))
            is CommandRun -> offer(tool(StatusKind.RUNNING, "Running " + short(event.description ?: firstToken(event.command), 40), null, now))
            is GradleTaskRun -> offer(tool(StatusKind.RUNNING, "Running " + event.task, "Gradle", now))
            is TestStarted -> offer(tool(StatusKind.TESTING, "Running tests", event.target?.let { short(it, 40) }, now))
            is TestReported -> {
                if (event.failed > 0) offer(outcome(StatusKind.ERROR, "${event.failed} test${plural(event.failed)} failed", "${event.passed} passed", now))
                else offer(outcome(StatusKind.SUCCESS, "${event.passed} test${plural(event.passed)} passed", null, now))
            }
            is BuildReported -> {
                if (event.success) offer(outcome(StatusKind.SUCCESS, "Build succeeded", event.summary, now))
                else offer(outcome(StatusKind.ERROR, "Build failed", event.summary, now))
            }
            is ErrorObserved -> offer(outcome(StatusKind.ERROR, "Error", short(event.message, 60), now))
            is WarningObserved -> offer(tool(StatusKind.WARNING, "Warning", short(event.message, 60), now))
            is WebActivity -> offer(tool(StatusKind.RUNNING, "Fetching " + short(event.label, 40), null, now))
            is ToolInvoked -> offer(tool(StatusKind.WORKING, "Using " + event.summary, null, now))
            is ActivityDenied -> {
                val verb = if (event.cancelled) "Cancelled" else "Blocked"
                offer(outcome(StatusKind.WARNING, "$verb ${event.toolName}", "not run", now))
            }
            // Background structure enrichment never changes the status line.
            is StructuralRelation, is FilePackage -> view
        }
    }

    // ---- internals ----

    private fun tool(kind: StatusKind, primary: String, secondary: String?, at: Instant) =
        StatusView(kind, primary, secondary, P_TOOL, animated = true, at = at)

    private fun outcome(kind: StatusKind, primary: String, secondary: String?, at: Instant) =
        StatusView(kind, primary, secondary, P_OUTCOME, animated = false, at = at)

    /** Accepts the candidate only if it is at least as important (>= priority) as the current view. */
    private fun offer(candidate: StatusView): StatusView {
        if (candidate.priority <= view.priority) view = candidate
        return view
    }

    private fun force(v: StatusView): StatusView { view = v; return v }

    private fun base(path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        val slash = p.lastIndexOf('/')
        return if (slash >= 0) p.substring(slash + 1) else p
    }

    private fun firstToken(cmd: String) = cmd.trim().substringBefore(' ').ifBlank { cmd.trim() }
    private fun short(s: String, max: Int) = if (s.length > max) s.substring(0, max - 1) + "…" else s
    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        const val WORKING_FALLBACK = "Claude is working…"

        // Lower value = higher priority.
        const val P_PERMISSION = 0
        const val P_OUTCOME = 1
        const val P_TOOL = 2
        const val P_STREAM = 3
        const val P_WORKING = 4
        const val P_COMPLETED = 5
        const val P_READY = 6

        fun ready(at: Instant) = StatusView(StatusKind.READY, "Ready", null, P_READY, false, at)
    }
}
