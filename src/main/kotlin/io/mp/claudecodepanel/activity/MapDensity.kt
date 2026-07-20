package io.mp.claudecodepanel.activity

/**
 * Progressive disclosure for a busy activity map — the stage *before* [ClusterCollapser] folds anything.
 * Platform-free and deterministic so it is unit-tested; the renderer just asks and obeys.
 *
 * The progression, cheapest first: thin out low-value **labels** as the graph grows (a wall of overlapping
 * text is worse than no text), let the visible-node cap hold with an explicit **"Show more"** rather than
 * silently truncating, and make **Fit** frame the bulk of the graph instead of being dictated by a couple
 * of far-flung outliers. Only then does collapsing history come into play.
 *
 * Nothing here ever hides a node — it hides *decoration*. What is on the map stays on the map.
 */
object MapDensity {

    /** How much labelling the graph can carry before it reads as noise. */
    enum class LabelTier { ALL, IMPORTANT, MINIMAL }

    const val IMPORTANT_ABOVE = 40
    const val MINIMAL_ABOVE = 120

    /** Zooming in past this reads as "I am inspecting this region" and earns back one tier of detail. */
    const val ZOOM_RELAX_SCALE = 1.25

    /** Below this zoom an ordinary node's label is dropped even in [LabelTier.ALL] (today's rule). */
    const val ORDINARY_LABEL_SCALE = 0.78

    /** Ordinary nodes smaller than this carry no label — the text would dwarf the dot. */
    const val ORDINARY_LABEL_RADIUS = 6.0

    /** Anchors and anything demanding attention: labelled at every density. */
    private val ALWAYS_LABELLED = setOf(
        ActivityNodeType.TASK, ActivityNodeType.CATEGORY, ActivityNodeType.ERROR,
    )

    /** Plus edits, which the user almost always wants to locate by name. */
    private val IMPORTANT_LABELLED = ALWAYS_LABELLED + ActivityNodeType.PATCH

    fun labelTier(visibleNodeCount: Int, scale: Double): LabelTier {
        val base = when {
            visibleNodeCount > MINIMAL_ABOVE -> LabelTier.MINIMAL
            visibleNodeCount > IMPORTANT_ABOVE -> LabelTier.IMPORTANT
            else -> LabelTier.ALL
        }
        if (scale < ZOOM_RELAX_SCALE) return base
        return when (base) {
            LabelTier.MINIMAL -> LabelTier.IMPORTANT
            else -> LabelTier.ALL
        }
    }

    /**
     * Whether a node draws its label. [attention] covers focus / selection / hover — those are the user's
     * own pointer and are always labelled, at any density, or the map would stop answering "what is this?".
     */
    fun showsLabel(
        tier: LabelTier,
        type: ActivityNodeType,
        attention: Boolean,
        radius: Double,
        scale: Double,
    ): Boolean {
        if (attention) return true
        return when (tier) {
            LabelTier.ALL ->
                type in IMPORTANT_LABELLED || (scale >= ORDINARY_LABEL_SCALE && radius >= ORDINARY_LABEL_RADIUS)
            LabelTier.IMPORTANT -> type in IMPORTANT_LABELLED
            LabelTier.MINIMAL -> type in ALWAYS_LABELLED
        }
    }

    // ---- visible-node cap / "Show more" ----

    /** Matches the `activityMaxNodes` setting's own ceiling. */
    const val MAX_VISIBLE_CAP = 2000

    fun hiddenCount(shown: Int, total: Int): Int = (total - shown).coerceAtLeast(0)

    /** The node counter's text. States the truncation outright rather than quietly showing fewer nodes. */
    fun countText(shown: Int, total: Int): String =
        if (hiddenCount(shown, total) > 0) "$shown of $total · Show more"
        else "$total node" + if (total == 1) "" else "s"

    /** Raising the cap doubles it, stopping at whatever the graph actually holds. */
    fun nextVisibleCap(currentCap: Int, total: Int): Int =
        (currentCap * 2).coerceAtMost(maxOf(total, currentCap)).coerceAtMost(MAX_VISIBLE_CAP)

    // ---- fit ----

    /** Labels sit to the right of nodes, so a denser graph needs more room at the edges. */
    fun fitPadding(visibleNodeCount: Int): Int = when {
        visibleNodeCount > MINIMAL_ABOVE -> 120
        visibleNodeCount > IMPORTANT_ABOVE -> 100
        else -> 80
    }

    /** Fraction trimmed off each end of each axis once the graph is big enough for outliers to matter. */
    const val TRIM_FRACTION = 0.02

    data class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    /**
     * The rectangle Fit should frame. Small graphs get their exact extent. Past [IMPORTANT_ABOVE], a couple
     * of far-flung nodes would otherwise shrink everything else to a speck, so the outer [TRIM_FRACTION] of
     * each axis is trimmed — those few nodes fall outside the view, which is the better trade at that size.
     */
    fun fitBounds(xs: List<Double>, ys: List<Double>): Bounds? {
        if (xs.isEmpty() || ys.isEmpty()) return null
        val trim = if (xs.size > IMPORTANT_ABOVE) (xs.size * TRIM_FRACTION).toInt() else 0
        fun span(values: List<Double>): Pair<Double, Double> {
            val sorted = values.sorted()
            // Never trim so hard that the span inverts or empties.
            val t = trim.coerceAtMost((sorted.size - 1) / 2)
            return sorted[t] to sorted[sorted.size - 1 - t]
        }
        val (minX, maxX) = span(xs)
        val (minY, maxY) = span(ys)
        return Bounds(minX, maxX, minY, maxY)
    }
}
