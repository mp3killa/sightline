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

    /**
     * Nodes belonging to the session rather than to any region of the codebase — the current request,
     * the generated patch, a search. They are not *unclassified*; there is simply nothing about a user's
     * own request that a code-area heuristic could classify, and filing them under
     * [UNKNOWN] made the inspector report the request the user just typed as "Unknown / Unclassified".
     * No cluster node is created for this category, so it names things without adding a hub to the map.
     */
    SESSION("Session"),
    UNKNOWN("Unknown / Unclassified"),
}

/**
 * Visual/semantic state of a node. The renderer maps these to theme-aware colours.
 *
 * [DENIED] and [CANCELLED] are terminal, non-error states: the action never executed. A denied edit
 * must therefore never look modified/completed, and denial is a user decision — not a failure.
 */
enum class ActivityNodeState {
    IDLE, DISCOVERED, SEARCHING, READING, ANALYSING, SELECTED, EDITING, CREATED,
    DELETED, TESTING, PASSED, WARNING, FAILED, COMPLETED, DENIED, CANCELLED,
}

/** Relationship kinds between nodes. */
enum class ActivityEdgeType {
    CONTAINS, IMPORTS, REFERENCES, CALLS, IMPLEMENTS, EXTENDS, READS, EDITS, CREATES,
    DELETES, SEARCHES, TESTS, TESTED_BY, CONFIGURED_BY, DEPENDS_ON, NAVIGATES_TO,
    GENERATED_FROM, AFFECTED_BY, RELATED_TO, SEQUENTIAL_ACTIVITY,
    // A command/gradle/test run produced this result (build outcome, test report, diagnostic).
    PRODUCED,
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

/**
 * Where a relationship claim came from — so the inspector can say **why** an edge exists instead of
 * asserting it bare. Ordered strongest→weakest; structured/PSI facts outrank name/path heuristics.
 */
enum class EvidenceSource {
    STRUCTURED_TOOL_EVENT, PSI_DECLARATION, PSI_REFERENCE, IMPORT,
    NAMING_HEURISTIC, PATH_HEURISTIC, COMMAND_OUTPUT,
}

/** Provenance attached to an edge: its source, confidence, and a human-readable [explanation]. */
data class RelationshipEvidence(
    val source: EvidenceSource,
    val confidence: Float,
    val explanation: String,
    val sourcePath: String? = null,
    val sourceSymbol: String? = null,
) {
    companion object {
        /**
         * The stronger of two evidences for the *same* relationship — several observations can justify
         * one edge, so this ranks/merges them. [EvidenceSource] is declared strongest→weakest, so a lower
         * ordinal wins; ties break on higher [confidence]. Null means "no claim".
         */
        fun stronger(a: RelationshipEvidence?, b: RelationshipEvidence?): RelationshipEvidence? {
            if (a == null) return b
            if (b == null) return a
            return when {
                a.source.ordinal != b.source.ordinal -> if (a.source.ordinal < b.source.ordinal) a else b
                else -> if (a.confidence >= b.confidence) a else b
            }
        }
    }
}

data class ActivityEdge(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val type: ActivityEdgeType,
    val weight: Float = 1f,
    val confidence: Float = 1f,
    val lastActivatedAt: Instant,
    val evidence: RelationshipEvidence? = null,
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
