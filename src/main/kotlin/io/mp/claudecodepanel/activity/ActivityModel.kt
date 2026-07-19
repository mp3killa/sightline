package io.mp.claudecodepanel.activity

import java.time.Instant

/**
 * Platform-free data model for the **Agent Activity Map** (a.k.a. Neural Activity Map / Live
 * Context Graph). Everything in this file is pure Kotlin + [Instant] so it can be unit-tested
 * without the IntelliJ platform.
 *
 * IMPORTANT framing: nodes here represent things the agent is **observably** interacting with
 * (tool calls, file reads/edits, commands, tests, errors). They are NOT the model's hidden
 * reasoning or chain of thought — only externally visible activity is ever recorded.
 */

/** What a node stands for. */
enum class ActivityNodeType {
    TASK, MODULE, PACKAGE, DIRECTORY, FILE, CLASS, INTERFACE, OBJECT, FUNCTION,
    COMPOSABLE, VIEW_MODEL, REPOSITORY, USE_CASE, API_ENDPOINT, TEST, GRADLE_TASK,
    COMMAND, DEPENDENCY, PATTERN, ERROR, WARNING, DOCUMENTATION, PATCH, SEARCH,
    SYMBOL, WEB, CATEGORY,
}

/**
 * Architectural clusters. This is a sensible default set; classification is done by
 * [ActivityClassifier], which callers can extend/override — the categories are not hardcoded
 * into the reducer beyond providing a home cluster for each node.
 */
enum class ActivityCategory(val label: String) {
    UI_COMPOSE("UI / Compose"),
    VIEWMODELS_STATE("ViewModels / State"),
    DOMAIN_USECASES("Domain / Use Cases"),
    DATA_REPOSITORIES("Data / Repositories"),
    NETWORKING_APIS("Networking / APIs"),
    PERSISTENCE_DB("Persistence / Database"),
    DEPENDENCY_INJECTION("Dependency Injection"),
    NAVIGATION("Navigation"),
    ANDROID_FRAMEWORK("Android Framework"),
    GRADLE_BUILD("Gradle / Build"),
    TESTING("Testing"),
    DOCUMENTATION("Documentation"),
    RESOURCES("Resources"),
    CONFIGURATION("Configuration"),
    UTILITIES("Utilities"),
    SHELL("Shell / Commands"),
    DIAGNOSTICS("Errors / Warnings"),
    UNKNOWN("Unknown / Unclassified"),
}

/** Visual/semantic state of a node. The renderer maps these to theme-aware colours. */
enum class ActivityNodeState {
    IDLE, DISCOVERED, SEARCHING, READING, ANALYSING, SELECTED, EDITING, CREATED,
    DELETED, TESTING, PASSED, WARNING, FAILED, COMPLETED,
}

/** Relationship kinds between nodes. */
enum class ActivityEdgeType {
    CONTAINS, IMPORTS, REFERENCES, CALLS, IMPLEMENTS, EXTENDS, READS, EDITS, CREATES,
    DELETES, SEARCHES, TESTS, TESTED_BY, CONFIGURED_BY, DEPENDS_ON, NAVIGATES_TO,
    GENERATED_FROM, AFFECTED_BY, RELATED_TO, SEQUENTIAL_ACTIVITY,
}

data class ActivityNode(
    val id: String,
    val type: ActivityNodeType,
    val label: String,
    val subtitle: String? = null,
    val path: String? = null,
    val category: ActivityCategory = ActivityCategory.UNKNOWN,
    val state: ActivityNodeState = ActivityNodeState.DISCOVERED,
    val confidence: Float = 1f,
    val interactionCount: Int = 0,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

data class ActivityEdge(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val type: ActivityEdgeType,
    val weight: Float = 1f,
    val confidence: Float = 1f,
    val lastActivatedAt: Instant,
)

/** One entry in the session activity timeline (metadata/summaries only — never file contents). */
data class TimelineEntry(
    val at: Instant,
    val verb: String,
    val label: String,
    val nodeId: String?,
    val state: ActivityNodeState,
    val detail: String? = null,
)
