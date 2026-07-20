package io.mp.sightline.activity

import java.time.Instant

/**
 * Reduces a stream of [AgentActivityEvent]s into a live graph of nodes/edges plus a rolling focus
 * and timeline. This is the model behind the Agent Activity Map; it is platform-free and
 * deterministic so it can be unit tested in isolation.
 *
 * Design notes:
 *  - A single central [TASK][ActivityNodeType.TASK] node (`task:root`) anchors the graph.
 *  - Files hang off lazily-created **category cluster** nodes (task → category → file).
 *  - Semantic [ActivityNodeState] persists (an edited file stays `EDITING`); the renderer fades
 *    intensity by recency using [ActivityNode.lastSeenAt].
 *  - Retained nodes are capped ([maxRetained]); oldest non-pinned, non-structural nodes are evicted.
 */
class ActivityGraph(
    private val maxRetained: Int = 500,
    private val clock: () -> Instant = Instant::now,
) {
    data class Focus(val verb: String, val label: String, val detail: String?, val nodeId: String?, val at: Instant)

    private val nodesMap = LinkedHashMap<String, ActivityNode>()
    private val edgesMap = LinkedHashMap<String, ActivityEdge>()
    private val timelineList = ArrayList<TimelineEntry>()

    private var lastFocusNodeId: String? = null
    // The most recent command/gradle/test node, so its result (build/test/diagnostic) can link back to
    // it (command → PRODUCED → result). Only command-type events update this, so interleaved reads
    // don't disturb the correlation; the sequential tool model keeps it accurate in the common case.
    private var lastCommandNodeId: String? = null
    // tool_use_id → the command/gradle/test node it created. A result carrying the same id links to the
    // exact producer even when tool calls interleave (parallel-safe); absent id falls back to sequential.
    // Bounded (oldest evicted) and cleared with the session.
    private val producerByToolUse = LinkedHashMap<String, String>()
    var focus: Focus = Focus("Idle", "Agent active", "Waiting for contextual activity…", null, clock())
        private set

    val nodes: Collection<ActivityNode> get() = nodesMap.values
    val edges: Collection<ActivityEdge> get() = edgesMap.values
    val timeline: List<TimelineEntry> get() = timelineList
    fun node(id: String): ActivityNode? = nodesMap[id]

    companion object {
        const val TASK_ID = "task:root"
        const val PATCH_ID = "patch:session"
        private const val MAX_PRODUCERS = 500
    }

    fun clear() {
        nodesMap.clear(); edgesMap.clear(); timelineList.clear()
        lastFocusNodeId = null
        lastCommandNodeId = null
        producerByToolUse.clear()
        focus = Focus("Idle", "Agent active", "Waiting for contextual activity…", null, clock())
    }

    fun setPinned(id: String, pinned: Boolean) { nodesMap[id]?.let { nodesMap[id] = it.copy(pinned = pinned) } }
    fun setHidden(id: String, hidden: Boolean) { nodesMap[id]?.let { nodesMap[id] = it.copy(hidden = hidden) } }

    /** Node ids directly connected to [id] (either direction). */
    fun neighbors(id: String): List<String> =
        edgesMap.values.mapNotNull {
            when (id) { it.sourceNodeId -> it.targetNodeId; it.targetNodeId -> it.sourceNodeId; else -> null }
        }.distinct()

    /** Edges incident to [id] in either direction — for the inspector's relationship list (with evidence). */
    fun edgesTouching(id: String): List<ActivityEdge> =
        edgesMap.values.filter { it.sourceNodeId == id || it.targetNodeId == id }

    /** True when a currently-failing error node points at [id] via AFFECTED_BY. */
    fun hasActiveError(id: String): Boolean =
        edgesMap.values.any {
            it.type == ActivityEdgeType.AFFECTED_BY && it.targetNodeId == id &&
                nodesMap[it.sourceNodeId]?.state == ActivityNodeState.FAILED
        }

    /**
     * Applies one event, returning the id of the primarily-affected node (for the UI to select/pulse),
     * or null for focus-only events.
     */
    fun apply(event: AgentActivityEvent): String? {
        val now = event.at
        val primary: String? = when (event) {
            is TaskStarted -> {
                val label = event.text.ifBlank { "Task" }
                touch(TASK_ID, ActivityNodeType.TASK, label = trim(label, 60), state = ActivityNodeState.ANALYSING,
                    category = ActivityCategory.SESSION, confidence = 1f, now = now, subtitle = "Current request")
                setFocus("Starting", trim(label, 48), null, TASK_ID, now); null // task node is not part of sequential trail
            }
            // General status/tool verbs describe the agent's overall state, not an action on the
            // last-touched file — so they don't carry that file's label (avoids "Thinking Foo.kt").
            is StatusUpdated -> { setFocus(event.verb, event.detail ?: "", null, focus.nodeId, now, record = false); return null }
            is ToolInvoked -> { setFocus("Using ${event.summary}", "", null, focus.nodeId, now, record = false); return null }
            is FileRead -> fileNode(event.path, ActivityNodeState.READING, "Reading", event.confidence, now)
            is FileEdited -> {
                val state = when { event.deleted -> ActivityNodeState.DELETED; event.created -> ActivityNodeState.CREATED; else -> ActivityNodeState.EDITING }
                val verb = when { event.deleted -> "Deleting"; event.created -> "Creating"; else -> "Editing" }
                val id = fileNode(event.path, state, verb, event.confidence, now)
                linkEdit(id, now)
                id
            }
            is FileSearched -> searchNode(event, now)
            is SymbolInspected -> symbolNode(event, now)
            is CommandRun -> commandNode(event, now).also { markProducer(it, event.toolUseId) }
            is GradleTaskRun -> gradleNode(event.task, now, event.confidence).also { markProducer(it, event.toolUseId) }
            is TestStarted -> testSuiteNode(event.target, ActivityNodeState.TESTING, "Testing", now).also { markProducer(it, event.toolUseId) }
            is TestReported -> testReport(event, now)
            is BuildReported -> buildNode(event, now)
            is ErrorObserved -> errorNode(event, now)
            is WarningObserved -> warningNode(event, now)
            is WebActivity -> webNode(event, now)
            is ActivityDenied -> denyNode(event, now)
            // Background PSI enrichment: add structure without grabbing focus or advancing the trail.
            is StructuralRelation -> { relateFiles(event, now); return null }
            is FilePackage -> { setFilePackage(event, now); return null }
            is TaskCompleted -> { completeTask(event, now); TASK_ID }
        }
        if (primary != null) advanceTrail(primary, now)
        evictIfNeeded()
        return primary
    }

    // ---- node builders ----

    private fun fileNode(rawPath: String, state: ActivityNodeState, verb: String, confidence: Float, now: Instant): String {
        val path = ActivityClassifier.normalizePath(rawPath)
        val c = ActivityClassifier.classify(path)
        val id = "file:$path"
        val cat = ensureCategory(c.category, now)
        touch(id, c.nodeType, label = ActivityClassifier.basename(path), state = state, category = c.category,
            confidence = maxOf(confidence, c.confidence * confidence), now = now, path = path,
            subtitle = c.category.label)
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.6f)
        setFocus(verb, ActivityClassifier.basename(path), c.category.label, id, now)
        return id
    }

    private fun searchNode(e: FileSearched, now: Instant): String {
        val q = e.query.ifBlank { "(pattern)" }
        val id = "search:${hash(q + (e.path ?: ""))}"
        touch(id, ActivityNodeType.SEARCH, label = "⌕ ${trim(q, 28)}", state = ActivityNodeState.SEARCHING,
            category = ActivityCategory.SESSION, confidence = e.confidence, now = now, subtitle = e.path)
        edge(TASK_ID, id, ActivityEdgeType.SEARCHES, now, weight = 0.4f)
        setFocus("Searching", trim(q, 40), e.path, id, now)
        return id
    }

    private fun symbolNode(e: SymbolInspected, now: Instant): String {
        val id = "sym:${e.name}"
        val cat = ActivityClassifier.classify(e.path).category
        val catId = ensureCategory(cat, now)
        touch(id, ActivityNodeType.SYMBOL, label = e.name, state = ActivityNodeState.ANALYSING,
            category = cat, confidence = e.confidence, now = now, path = e.path, subtitle = "symbol")
        edge(catId, id, ActivityEdgeType.CONTAINS, now, weight = 0.4f)
        e.path?.let { edge("file:${ActivityClassifier.normalizePath(it)}", id, ActivityEdgeType.CONTAINS, now, weight = 0.3f) }
        setFocus("Inspecting", e.name, "symbol", id, now)
        return id
    }

    private fun commandNode(e: CommandRun, now: Instant): String {
        val id = "cmd:${hash(e.command)}"
        val cat = ensureCategory(ActivityCategory.SHELL, now)
        touch(id, ActivityNodeType.COMMAND, label = "$ ${trim(oneLine(e.command), 34)}", state = ActivityNodeState.ANALYSING,
            category = ActivityCategory.SHELL, confidence = e.confidence, now = now, subtitle = e.description ?: oneLine(e.command))
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
        setFocus("Running", e.description ?: trim(oneLine(e.command), 40), null, id, now)
        return id
    }

    private fun gradleNode(task: String, now: Instant, confidence: Float): String {
        val id = "gradle:$task"
        val cat = ensureCategory(ActivityCategory.GRADLE_BUILD, now)
        touch(id, ActivityNodeType.GRADLE_TASK, label = task, state = ActivityNodeState.ANALYSING,
            category = ActivityCategory.GRADLE_BUILD, confidence = confidence, now = now, subtitle = "Gradle task")
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
        setFocus("Running", task, "Gradle", id, now)
        return id
    }

    private fun testSuiteNode(target: String?, state: ActivityNodeState, verb: String, now: Instant): String {
        val name = target?.takeIf { it.isNotBlank() } ?: "test suite"
        val id = "test:${hash(name)}"
        val cat = ensureCategory(ActivityCategory.TESTING, now)
        touch(id, ActivityNodeType.TEST, label = trim(name, 30), state = state,
            category = ActivityCategory.TESTING, confidence = 1f, now = now, subtitle = "tests")
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
        setFocus(verb, trim(name, 34), "tests", id, now)
        return id
    }

    private fun testReport(e: TestReported, now: Instant): String {
        val suiteId = testSuiteNode("test suite",
            if (e.failed > 0) ActivityNodeState.FAILED else ActivityNodeState.PASSED,
            if (e.failed > 0) "Tests failed" else "Tests passed", now)
        nodesMap[suiteId]?.let { nodesMap[suiteId] = it.copy(subtitle = "${e.passed} passed · ${e.failed} failed") }
        linkProduced(suiteId, now, "test results (${e.passed} passed · ${e.failed} failed)", e.toolUseId)
        val cat = ensureCategory(ActivityCategory.TESTING, now)
        for (fname in e.failedNames.take(20)) {
            val id = "test:${hash(fname)}"
            touch(id, ActivityNodeType.TEST, label = trim(fname.substringAfterLast('>').trim().ifBlank { fname }, 30),
                state = ActivityNodeState.FAILED, category = ActivityCategory.TESTING, confidence = 1f, now = now, subtitle = fname)
            edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
            edge(suiteId, id, ActivityEdgeType.TESTS, now, weight = 0.4f)
        }
        return suiteId
    }

    private fun buildNode(e: BuildReported, now: Instant): String {
        val id = "gradle:build"
        val cat = ensureCategory(ActivityCategory.GRADLE_BUILD, now)
        touch(id, ActivityNodeType.GRADLE_TASK, label = "build",
            state = if (e.success) ActivityNodeState.PASSED else ActivityNodeState.FAILED,
            category = ActivityCategory.GRADLE_BUILD, confidence = 1f, now = now, subtitle = e.summary)
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
        setFocus(if (e.success) "Build succeeded" else "Build failed", "build", e.summary, id, now)
        linkProduced(id, now, if (e.success) "a successful build" else "a build failure", e.toolUseId)
        return id
    }

    private fun errorNode(e: ErrorObserved, now: Instant): String {
        val id = "err:${hash((e.path ?: "") + "|" + e.message)}"
        val cat = ensureCategory(ActivityCategory.DIAGNOSTICS, now)
        touch(id, ActivityNodeType.ERROR, label = "⚠ ${trim(e.message, 30)}", state = ActivityNodeState.FAILED,
            category = ActivityCategory.DIAGNOSTICS, confidence = e.confidence, now = now, subtitle = e.message, path = e.path)
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f)
        e.path?.let { p ->
            val fileId = "file:${ActivityClassifier.normalizePath(p)}"
            if (nodesMap.containsKey(fileId)) edge(id, fileId, ActivityEdgeType.AFFECTED_BY, now, weight = 0.7f,
                evidence = affectedEvidence(p, e.message, e.confidence))
        }
        setFocus("Error", trim(e.message, 44), e.path, id, now)
        linkProduced(id, now, "an error", e.toolUseId)
        return id
    }

    private fun warningNode(e: WarningObserved, now: Instant): String {
        val id = "warn:${hash((e.path ?: "") + "|" + e.message)}"
        val cat = ensureCategory(ActivityCategory.DIAGNOSTICS, now)
        touch(id, ActivityNodeType.WARNING, label = "△ ${trim(e.message, 30)}", state = ActivityNodeState.WARNING,
            category = ActivityCategory.DIAGNOSTICS, confidence = e.confidence, now = now, subtitle = e.message, path = e.path)
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.4f)
        e.path?.let { p ->
            val fileId = "file:${ActivityClassifier.normalizePath(p)}"
            if (nodesMap.containsKey(fileId)) edge(id, fileId, ActivityEdgeType.AFFECTED_BY, now, weight = 0.5f,
                evidence = affectedEvidence(p, e.message, e.confidence))
        }
        setFocus("Warning", trim(e.message, 44), e.path, id, now)
        linkProduced(id, now, "a warning", e.toolUseId)
        return id
    }

    /** Provenance for an error/warning → file link: the diagnostic text reported against that file. */
    private fun affectedEvidence(path: String, message: String, confidence: Float): RelationshipEvidence =
        RelationshipEvidence(
            EvidenceSource.COMMAND_OUTPUT, confidence,
            explanation = "${ActivityClassifier.basename(path)}: ${trim(message, 44)}", sourcePath = path,
        )

    private fun webNode(e: WebActivity, now: Instant): String {
        val id = "web:${hash(e.label)}"
        val cat = ensureCategory(ActivityCategory.NETWORKING_APIS, now)
        touch(id, ActivityNodeType.WEB, label = "↗ ${trim(e.label, 28)}", state = ActivityNodeState.DISCOVERED,
            category = ActivityCategory.NETWORKING_APIS, confidence = e.confidence, now = now, subtitle = e.label)
        edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.4f)
        setFocus("Fetching", trim(e.label, 40), null, id, now)
        return id
    }

    /**
     * Reconciles a denied/cancelled tool: marks the node it created as blocked and drops any
     * optimistic modification edges (the patch link) so the file no longer reads as modified. Covers
     * the file- and command-node cases (the tools users actually get prompted for); anything else
     * falls back to a focus-only "Denied" so the timeline still records the attempt.
     */
    private fun denyNode(e: ActivityDenied, now: Instant): String? {
        val verb = if (e.cancelled) "Cancelled" else "Denied"
        val newState = if (e.cancelled) ActivityNodeState.CANCELLED else ActivityNodeState.DENIED
        val id = when {
            e.path != null -> "file:${ActivityClassifier.normalizePath(e.path)}"
            e.command != null -> "cmd:${hash(e.command)}"
            else -> null
        }
        val node = id?.let { nodesMap[it] }
        if (id == null || node == null) {
            setFocus(verb, e.toolName, "blocked before running", null, now)
            return null
        }
        nodesMap[id] = node.copy(state = newState, subtitle = "$verb by user", lastSeenAt = now)
        // Undo the optimistic "modified" signal created at tool_use time (linkEdit's patch edge).
        edgesMap.values
            .filter { (it.sourceNodeId == id || it.targetNodeId == id) &&
                (it.type == ActivityEdgeType.GENERATED_FROM || it.type == ActivityEdgeType.EDITS ||
                    it.type == ActivityEdgeType.CREATES) }
            .map { it.id }.toList()
            .forEach { edgesMap.remove(it) }
        setFocus(verb, node.label, "blocked before running", id, now)
        return id
    }

    /** Records the node a command/gradle/test event created — both as the sequential fallback and, when
     * the tool_use id is known, in the id→node index so a later result links to the exact producer. */
    private fun markProducer(nodeId: String, toolUseId: String?) {
        lastCommandNodeId = nodeId
        if (toolUseId == null) return
        producerByToolUse[toolUseId] = nodeId
        // Bound the index: drop the oldest correlations once past the cap.
        while (producerByToolUse.size > MAX_PRODUCERS) {
            val it = producerByToolUse.keys.iterator()
            if (it.hasNext()) { it.next(); it.remove() } else break
        }
    }

    /**
     * Links the command/gradle/test node that produced [resultId] to it, tagging the edge with
     * [EvidenceSource.COMMAND_OUTPUT] provenance ("<command> produced <what>") so the inspector can say
     * why the two are connected. The producer is the node correlated by [toolUseId] when known (exact,
     * interleave-safe), otherwise the most recent command (sequential fallback).
     */
    private fun linkProduced(resultId: String, now: Instant, what: String, toolUseId: String? = null) {
        val src = toolUseId?.let { producerByToolUse[it] }?.takeIf { nodesMap.containsKey(it) }
            ?: lastCommandNodeId ?: return
        if (src == resultId || !nodesMap.containsKey(src)) return
        val cmd = nodesMap[src]
        val label = cmd?.subtitle?.takeIf { it.isNotBlank() } ?: cmd?.label ?: "command"
        val ev = RelationshipEvidence(
            EvidenceSource.COMMAND_OUTPUT, confidence = 0.9f,
            explanation = "${trim(label, 40)} produced $what", sourcePath = cmd?.path,
        )
        edge(src, resultId, ActivityEdgeType.PRODUCED, now, weight = 0.5f, evidence = ev)
    }

    /**
     * Draws a real structural edge from a touched file to a resolved project file. Only enriches files
     * already in the graph (Claude touched the source); the target is created as a light DISCOVERED node
     * so the relationship is visible without pulling it into the active trail.
     */
    private fun relateFiles(e: StructuralRelation, now: Instant) {
        val srcId = "file:${ActivityClassifier.normalizePath(e.sourcePath)}"
        if (!nodesMap.containsKey(srcId)) return
        val tgtId = ensureEnrichedFileNode(e.targetPath, now)
        if (srcId == tgtId) return
        val type = when (e.relation) {
            StructuralRelationKind.IMPORTS -> ActivityEdgeType.IMPORTS
            StructuralRelationKind.TESTS -> ActivityEdgeType.TESTS
            StructuralRelationKind.EXTENDS -> ActivityEdgeType.EXTENDS
            StructuralRelationKind.IMPLEMENTS -> ActivityEdgeType.IMPLEMENTS
            StructuralRelationKind.NAVIGATES_TO -> ActivityEdgeType.NAVIGATES_TO
            StructuralRelationKind.REFERENCED_BY -> ActivityEdgeType.REFERENCES
        }
        edge(srcId, tgtId, type, now, weight = 0.4f, bump = false, evidence = structuralEvidence(e))
    }

    /** Provenance for a PSI-derived relationship — e.g. "DriverRepositoryImpl implements DriverRepository". */
    private fun structuralEvidence(e: StructuralRelation): RelationshipEvidence {
        val symbol = ActivityClassifier.basename(e.sourcePath).substringBeforeLast('.')
        val (source, verb) = when (e.relation) {
            StructuralRelationKind.IMPORTS -> EvidenceSource.IMPORT to "imports"
            StructuralRelationKind.EXTENDS -> EvidenceSource.PSI_DECLARATION to "extends"
            StructuralRelationKind.IMPLEMENTS -> EvidenceSource.PSI_DECLARATION to "implements"
            StructuralRelationKind.TESTS -> EvidenceSource.NAMING_HEURISTIC to "tests"
            // The nav graph explicitly declares the destination class (android:name), resolved to a file.
            StructuralRelationKind.NAVIGATES_TO -> EvidenceSource.PSI_REFERENCE to "navigates to"
            // A source references this resource by R.<type>.<name> / @<type>/<name>.
            StructuralRelationKind.REFERENCED_BY -> EvidenceSource.PSI_REFERENCE to "referenced by"
        }
        return RelationshipEvidence(source, e.confidence, "$symbol $verb ${e.targetLabel}", e.sourcePath, symbol)
    }

    private fun ensureEnrichedFileNode(rawPath: String, now: Instant): String {
        val path = ActivityClassifier.normalizePath(rawPath)
        val id = "file:$path"
        if (!nodesMap.containsKey(id)) {
            val c = ActivityClassifier.classify(path)
            val cat = ensureCategory(c.category, now)
            touch(id, c.nodeType, label = ActivityClassifier.basename(path), state = ActivityNodeState.DISCOVERED,
                category = c.category, confidence = 0.6f, now = now, path = path, subtitle = c.category.label)
            edge(cat, id, ActivityEdgeType.CONTAINS, now, weight = 0.5f, bump = false)
        }
        return id
    }

    private fun setFilePackage(e: FilePackage, now: Instant) {
        val id = "file:${ActivityClassifier.normalizePath(e.path)}"
        val n = nodesMap[id] ?: return
        val meta = n.metadata.toMutableMap()
        meta["package"] = e.packageName
        e.module?.let { meta["module"] = it }
        nodesMap[id] = n.copy(metadata = meta)
    }

    private fun linkEdit(fileId: String, now: Instant) {
        touch(PATCH_ID, ActivityNodeType.PATCH, label = "generated patch", state = ActivityNodeState.CREATED,
            category = ActivityCategory.SESSION, confidence = 1f, now = now, subtitle = "modified files")
        edge(PATCH_ID, fileId, ActivityEdgeType.GENERATED_FROM, now, weight = 0.5f)
    }

    private fun completeTask(e: TaskCompleted, now: Instant) {
        touch(TASK_ID, ActivityNodeType.TASK, label = null, state = ActivityNodeState.COMPLETED,
            category = ActivityCategory.SESSION, confidence = 1f, now = now)
        val transient = setOf(
            ActivityNodeState.READING, ActivityNodeState.ANALYSING, ActivityNodeState.SEARCHING,
            ActivityNodeState.DISCOVERED, ActivityNodeState.TESTING,
        )
        for ((id, n) in nodesMap.toList()) {
            if (id == TASK_ID) continue
            if (n.state in transient) nodesMap[id] = n.copy(state = ActivityNodeState.COMPLETED)
        }
        setFocus(if (e.isError) "Stopped" else "Completed", trim(e.summary.ifBlank { "done" }, 44), null, TASK_ID, now)
    }

    private fun ensureCategory(category: ActivityCategory, now: Instant): String {
        val id = "cat:${category.name}"
        touch(id, ActivityNodeType.CATEGORY, label = category.label, state = ActivityNodeState.IDLE,
            category = category, confidence = 1f, now = now, subtitle = "cluster")
        edge(TASK_ID, id, ActivityEdgeType.CONTAINS, now, weight = 0.9f, bump = false)
        return id
    }

    // ---- primitives ----

    private fun touch(
        id: String,
        type: ActivityNodeType,
        label: String?,
        state: ActivityNodeState,
        category: ActivityCategory,
        confidence: Float,
        now: Instant,
        path: String? = null,
        subtitle: String? = null,
    ) {
        val existing = nodesMap[id]
        if (existing == null) {
            nodesMap[id] = ActivityNode(
                id = id, type = type, label = label ?: id, subtitle = subtitle, path = path,
                category = category, state = state, confidence = confidence.coerceIn(0f, 1f),
                interactionCount = 1, firstSeenAt = now, lastSeenAt = now,
            )
        } else {
            nodesMap[id] = existing.copy(
                label = label ?: existing.label,
                subtitle = subtitle ?: existing.subtitle,
                path = path ?: existing.path,
                state = state,
                confidence = maxOf(existing.confidence, confidence).coerceIn(0f, 1f),
                interactionCount = existing.interactionCount + 1,
                lastSeenAt = now,
            )
        }
    }

    private fun edge(
        source: String, target: String, type: ActivityEdgeType, now: Instant,
        weight: Float = 1f, bump: Boolean = true, evidence: RelationshipEvidence? = null,
    ) {
        if (source == target) return
        val id = "$source->$target:${type.name}"
        val existing = edgesMap[id]
        edgesMap[id] = if (existing == null) {
            ActivityEdge(id, source, target, type, weight, 1f, now, evidence)
        } else {
            existing.copy(
                weight = if (bump) minOf(4f, existing.weight + 0.15f) else existing.weight,
                lastActivatedAt = now,
                // Multiple observations can justify the same edge; keep the strongest so the inspector
                // shows the most authoritative "why" (a PSI/import fact outranks command-output inference).
                evidence = RelationshipEvidence.stronger(existing.evidence, evidence),
            )
        }
    }

    private fun advanceTrail(nodeId: String, now: Instant) {
        val prev = lastFocusNodeId
        if (prev != null && prev != nodeId && nodesMap.containsKey(prev) && nodesMap.containsKey(nodeId)) {
            edge(prev, nodeId, ActivityEdgeType.SEQUENTIAL_ACTIVITY, now, weight = 0.25f, bump = false)
        }
        lastFocusNodeId = nodeId
    }

    private fun setFocus(verb: String, label: String, detail: String?, nodeId: String?, now: Instant, record: Boolean = true) {
        focus = Focus(verb, label, detail, nodeId, now)
        if (!record) return
        timelineList.add(TimelineEntry(now, verb, label, nodeId, nodeId?.let { nodesMap[it]?.state } ?: ActivityNodeState.IDLE, detail))
        if (timelineList.size > 600) timelineList.subList(0, timelineList.size - 600).clear()
    }

    private fun evictIfNeeded() {
        if (nodesMap.size <= maxRetained) return
        val removable = nodesMap.values
            .filter { it.type != ActivityNodeType.TASK && it.type != ActivityNodeType.CATEGORY && !it.pinned }
            .sortedBy { it.lastSeenAt }
        val toRemove = removable.take(nodesMap.size - maxRetained).map { it.id }.toSet()
        if (toRemove.isEmpty()) return
        toRemove.forEach { nodesMap.remove(it) }
        edgesMap.values.filter { it.sourceNodeId in toRemove || it.targetNodeId in toRemove }
            .map { it.id }.forEach { edgesMap.remove(it) }
    }

    private fun hash(s: String): String = Integer.toHexString(s.hashCode())
    private fun trim(s: String, max: Int): String = if (s.length > max) s.substring(0, max - 1) + "…" else s
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
