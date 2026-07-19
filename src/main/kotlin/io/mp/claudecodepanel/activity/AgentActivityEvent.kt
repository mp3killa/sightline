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

data class CommandRun(
    val command: String,
    val description: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class GradleTaskRun(
    val task: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class TestStarted(
    val target: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class TestReported(
    val passed: Int,
    val failed: Int,
    val failedNames: List<String>,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class BuildReported(
    val success: Boolean,
    val summary: String?,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class ErrorObserved(
    val path: String?,
    val message: String,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent

data class WarningObserved(
    val path: String?,
    val message: String,
    override val at: Instant,
    override val confidence: Float = 1f,
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

/** The task finished — marks touched nodes complete and closes any generated patch. */
data class TaskCompleted(
    val summary: String,
    val isError: Boolean,
    override val at: Instant,
    override val confidence: Float = 1f,
) : AgentActivityEvent
