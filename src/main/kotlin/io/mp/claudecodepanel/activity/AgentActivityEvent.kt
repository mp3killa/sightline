package io.mp.claudecodepanel.activity

import java.time.Instant

/**
 * Normalised, immutable activity events — the **single source of truth** for graph updates.
 * The UI never depends on Claude's raw stream format directly; [ActivityInterpreter] converts
 * raw tool/stream events into these, and [ActivityGraph] reduces these into nodes/edges.
 *
 * [confidence] is 1.0 for direct tool-derived facts and lower for text-derived guesses, so the
 * renderer can draw inferred activity more subtly.
 */
sealed interface AgentActivityEvent {
    val at: Instant
    val confidence: Float
}

/** A new user request began — creates/refreshes the central task node. */
data class TaskStarted(
    val text: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

/** A streamed status verb (e.g. "Thinking") — updates the focus card only, never creates a node. */
data class StatusUpdated(
    val verb: String,
    val detail: String?,
    override val at: Instant,
    override val confidence: Float = 0.35f,
) : AgentActivityEvent

data class FileRead(
    val path: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class FileSearched(
    val query: String,
    val path: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class FileEdited(
    val path: String,
    val created: Boolean = false,
    val deleted: Boolean = false,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class SymbolInspected(
    val name: String,
    val path: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

// [toolUseId] correlates a command to the result it produces, so the graph links the exact producer
// even when tool calls interleave — not just the most recent command. Null falls back to sequential.
data class CommandRun(
    val command: String,
    val description: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class GradleTaskRun(
    val task: String,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class TestStarted(
    val target: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class TestReported(
    val passed: Int,
    val failed: Int,
    val failedNames: List<String>,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class BuildReported(
    val success: Boolean,
    val summary: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class ErrorObserved(
    val path: String?,
    val message: String,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class WarningObserved(
    val path: String?,
    val message: String,
    override val at: Instant,
    override val confidence: Float = 1f,
    val toolUseId: String? = null,
) : AgentActivityEvent

data class WebActivity(
    val label: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

/** A tool with no richer mapping fired — updates the focus card, creates no node. */
data class ToolInvoked(
    val tool: String,
    val summary: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

/** How one file structurally relates to another (resolved to real project files, not path guesses). */
enum class StructuralRelationKind { IMPORTS, TESTS, EXTENDS, IMPLEMENTS, NAVIGATES_TO, REFERENCED_BY }

/**
 * A structural relationship discovered by enriching a file Claude touched — its import resolved to a
 * real project file, or a test file's production target. Background enrichment: it adds a graph edge
 * but never grabs focus, advances the activity trail, or changes the status line.
 */
data class StructuralRelation(
    val sourcePath: String,
    val targetPath: String,
    val targetLabel: String,
    val relation: StructuralRelationKind,
    override val at: Instant,
    override val confidence: Float = 0.85f,
) : AgentActivityEvent

/** Package/module membership for a touched file — stored as node metadata; never focus-changing. */
data class FilePackage(
    val path: String,
    val packageName: String,
    val module: String?,
    override val at: Instant,
    override val confidence: Float = 0.9f,
) : AgentActivityEvent

/**
 * The user denied a pending tool (or it was cancelled before executing). Correlated to the node the
 * tool_use created via [toolUseId]/[path]/[command] so the graph can mark it blocked and undo any
 * optimistic "modified" signal — a denied action must never look executed.
 */
data class ActivityDenied(
    val toolUseId: String?,
    val toolName: String,
    val path: String?,
    val command: String?,
    val cancelled: Boolean = false,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

/** The task finished — marks touched nodes complete and closes any generated patch. */
data class TaskCompleted(
    val summary: String,
    val isError: Boolean,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent
