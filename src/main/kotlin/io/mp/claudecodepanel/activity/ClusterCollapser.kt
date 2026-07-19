package io.mp.claudecodepanel.activity

/**
 * Decides how to fold clutter out of a busy activity graph. Platform-free and deterministic so it can
 * be unit-tested and reused by the renderer.
 *
 * The strategy the map applies (cheapest → most aggressive): hide low-value labels, then collapse
 * **historical** (finished) repeated command/test/gradle nodes into a single per-cluster aggregate
 * ("12 commands"), which the user can expand. Only finished, unimportant nodes are ever folded — an
 * active run, a failure, the current focus, the selection, and pinned nodes always stay visible, so
 * collapsing never hides something the user is looking at or something that still needs attention.
 */
object ClusterCollapser {

    /** A synthetic stand-in for several folded nodes of one kind within one cluster. */
    data class Aggregate(
        val id: String,
        val category: ActivityCategory,
        val type: ActivityNodeType,
        val label: String,
        val memberIds: List<String>,
    ) {
        val count: Int get() = memberIds.size
    }

    /** The result of planning a collapse: which node ids to hide, and the aggregates that replace them. */
    data class Plan(val hiddenIds: Set<String>, val aggregates: List<Aggregate>) {
        val isEmpty: Boolean get() = aggregates.isEmpty()
        fun aggregateFor(memberId: String): Aggregate? = aggregates.firstOrNull { memberId in it.memberIds }

        companion object { val EMPTY = Plan(emptySet(), emptyList()) }
    }

    // Node types worth aggregating when they pile up — the repetitive "history" of a session.
    private val FOLDABLE_TYPES = setOf(
        ActivityNodeType.COMMAND, ActivityNodeType.GRADLE_TASK, ActivityNodeType.TEST,
    )

    // A node is "historical" (safe to fold) only in a finished, non-attention state.
    private val HISTORICAL_STATES = setOf(
        ActivityNodeState.COMPLETED, ActivityNodeState.PASSED,
        ActivityNodeState.DENIED, ActivityNodeState.CANCELLED, ActivityNodeState.IDLE,
    )

    /**
     * Plans a collapse over [nodes]. [keepIds] are never folded (typically task/focus/selection/pinned);
     * a group of the same (category, type) with at least [minGroup] foldable members becomes one
     * aggregate. Returns [Plan.EMPTY] when nothing is worth folding.
     *
     * @param expanded aggregate ids the user has chosen to expand — their members stay visible.
     */
    fun plan(
        nodes: Collection<ActivityNode>,
        keepIds: Set<String> = emptySet(),
        minGroup: Int = 4,
        expanded: Set<String> = emptySet(),
    ): Plan {
        val groups = LinkedHashMap<Pair<ActivityCategory, ActivityNodeType>, MutableList<ActivityNode>>()
        for (n in nodes) {
            if (!isFoldable(n) || n.id in keepIds) continue
            groups.getOrPut(n.category to n.type) { ArrayList() }.add(n)
        }
        val aggregates = ArrayList<Aggregate>()
        val hidden = LinkedHashSet<String>()
        // Deterministic order: by category then type name.
        for ((key, members) in groups.entries.sortedBy { "${it.key.first.name}/${it.key.second.name}" }) {
            if (members.size < minGroup) continue
            val (category, type) = key
            val id = aggregateId(category, type)
            if (id in expanded) continue // user expanded this cluster — leave its members visible
            val ordered = members.sortedWith(compareBy({ it.lastSeenAt }, { it.id })).map { it.id }
            aggregates.add(Aggregate(id, category, type, label(type, ordered.size), ordered))
            hidden.addAll(ordered)
        }
        return if (aggregates.isEmpty()) Plan.EMPTY else Plan(hidden, aggregates)
    }

    private fun isFoldable(n: ActivityNode): Boolean =
        n.type in FOLDABLE_TYPES && !n.pinned && !n.hidden && n.state in HISTORICAL_STATES

    fun aggregateId(category: ActivityCategory, type: ActivityNodeType): String =
        "agg:${category.name}:${type.name}"

    private fun label(type: ActivityNodeType, count: Int): String = when (type) {
        ActivityNodeType.COMMAND -> "$count commands"
        ActivityNodeType.GRADLE_TASK -> "$count Gradle tasks"
        ActivityNodeType.TEST -> "$count test runs"
        else -> "$count items"
    }
}
