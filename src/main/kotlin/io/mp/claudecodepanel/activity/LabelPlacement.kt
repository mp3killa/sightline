package io.mp.claudecodepanel.activity

/**
 * Keeps map labels legible by refusing to draw two that would overprint each other. Platform-free and
 * deterministic so it is unit-tested; the renderer measures the text boxes and draws whatever comes back.
 *
 * A force layout has no notion of text. Two nodes can settle a perfectly comfortable centre-to-centre
 * distance apart and still have their labels collide, because a label is drawn beside its node and is many
 * times wider than the node is — the real footprint is a wide box, not a circle. This is why the collision
 * happens at low node counts too, and why it is not something [MapDensity]'s tiers address.
 *
 * A clashing label is first retried on the node's other side, and only withheld if that is taken too. Even
 * then the **node stays on the map** — only its text is held back, and hovering or selecting it restores
 * the label (attention outranks everything in [MapDensity.labelPriority]). Nothing is ever hidden,
 * shrunk, or squeezed.
 */
object LabelPlacement {

    /**
     * A measured label box, with the priority that settles a collision. [x] is the preferred box position
     * (conventionally to the right of the node); [alternateX] is the fallback tried when the preferred one
     * is taken (conventionally to the left), or null when the label has nowhere else to go.
     */
    data class Candidate(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val priority: Int,
        val alternateX: Int? = null,
    )

    /** Gap kept between neighbouring labels, so survivors read as separate rather than merely not touching. */
    const val PADDING = 3

    /**
     * Maps each drawable label's id to the box x it should be drawn at. Greedy, highest priority first:
     * a candidate takes its preferred position if free, else its alternate, else it is omitted from the
     * result and its text is not drawn. Ties break by id, so for a settled graph the same labels land in
     * the same places every frame rather than flickering.
     */
    fun place(candidates: List<Candidate>, padding: Int = PADDING): Map<String, Int> {
        if (candidates.isEmpty()) return emptyMap()
        val taken = ArrayList<Box>(candidates.size)
        val out = LinkedHashMap<String, Int>(candidates.size)
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.id })) {
            val options = if (c.alternateX == null) listOf(c.x) else listOf(c.x, c.alternateX)
            val free = options.firstOrNull { x ->
                val box = Box(x, c.y, c.width, c.height)
                taken.none { overlaps(it, box, padding) }
            } ?: continue
            taken.add(Box(free, c.y, c.width, c.height))
            out[c.id] = free
        }
        return out
    }

    private class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Axis-aligned overlap test, with each box grown by [padding] on every side. */
    private fun overlaps(a: Box, b: Box, padding: Int): Boolean =
        a.x - padding < b.x + b.width + padding &&
            b.x - padding < a.x + a.width + padding &&
            a.y - padding < b.y + b.height + padding &&
            b.y - padding < a.y + a.height + padding
}
