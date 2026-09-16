package io.mp.sightline.activity

import java.time.Instant

/**
 * Normalised, immutable activity events — the **single source of truth** for what the panel says is
 * happening. The UI never depends on Claude's raw stream format directly; [ActivityInterpreter]
 * converts raw tool/stream events into these, and `StatusModel` turns them into the status strip's
 * text, its recovered-failure tally and the per-turn processing summary.
 *
 * [confidence] is 1.0 for direct tool-derived facts and lower for text-derived guesses. It was what
 * let the (removed) activity map draw an inferred node more subtly; it is kept because the
 * distinction is real and a consumer that starts trusting a guess as a fact is the thing to prevent.
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
