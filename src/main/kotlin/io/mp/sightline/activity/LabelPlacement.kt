package io.mp.sightline.activity

/**
 * Keeps map labels legible by refusing to draw two that would overprint each other. Platform-free and
 * deterministic so it is unit-tested; the renderer measures the text boxes and draws whatever comes back.
 *
 * A force layout has no notion of text. Two nodes can settle a perfectly comfortable centre-to-centre
 * distance apart and still have their labels collide, because a label is drawn beside its node and is many
 * times wider than the node is — the real footprint is a wide box, not a circle. This is why the collision
 * happens at low node counts too, and why it is not something [MapDensity]'s tiers address.
 *
 * A clashing label is retried in every slot [slotsAround] offers — the far side of the node first, then
 * above it, then below — and withheld only when all of them are taken. Even then the **node stays on the
 * map**: only its text is held back, and hovering or selecting it restores the label (attention outranks
 * everything in [MapDensity.labelPriority]). Nothing is ever hidden, shrunk, or squeezed.
 */
object LabelPlacement {

    /** A position a label box may be drawn at — its top-left corner. */
    data class Slot(val x: Int, val y: Int)

    /**
     * A measured label box, the priority that settles a collision, and the positions it will accept in
     * preference order. Build [slots] with [slotsAround] unless a test wants to pin them exactly.
     */
    data class Candidate(
        val id: String,
        val width: Int,
        val height: Int,
        val priority: Int,
        val slots: List<Slot>,
    )

    /** Gap kept between neighbouring labels, so survivors read as separate rather than merely not touching. */
    const val PADDING = 3

    /** Gap between a node's edge and its own label box. */
    const val GAP = 2

    /**
     * Where a node's label may go, best first: beside it on the right, then the left, then above, then
     * below. Horizontal first because a label reads as belonging to the node it sits level with; the
     * vertical slots exist because two are not enough. A node crowded on both flanks used to lose its text
     * outright even on a graph with plenty of free space directly above and below it — the withholding
     * followed the rules, and the rules were too narrow.
     */
    fun slotsAround(
        nodeX: Int,
        nodeY: Int,
        radius: Int,
        width: Int,
        height: Int,
        gap: Int = GAP,
        padding: Int = PADDING,
    ): List<Slot> {
        val sideY = nodeY - height / 2
        // A vertical slot is centred over the node, so its x range overlaps both side slots. It therefore
        // has to clear the *band those labels occupy*, not merely the node's circle — otherwise it
        // collides with the very labels that pushed this one off the flanks, and offering it achieves
        // nothing. Clearing the node alone left only 3px between the boxes where [PADDING] wants 6.
        val clearOfSideLabels = height + height / 2 + 2 * padding
        val vertical = maxOf(radius + gap, clearOfSideLabels)
        return listOf(
            Slot(nodeX + radius + gap, sideY),
            Slot(nodeX - radius - gap - width, sideY),
            Slot(nodeX - width / 2, nodeY - vertical - height),
            Slot(nodeX - width / 2, nodeY + vertical),
        )
    }

    /**
     * Maps each drawable label's id to the slot it should be drawn in. Greedy, highest priority first: a
     * candidate takes the first of its slots that is still free, and is omitted from the result — its text
     * not drawn — only when every one of them is taken. Ties break by id, so for a settled graph the same
     * labels land in the same places every frame rather than flickering.
     */
    fun place(candidates: List<Candidate>, padding: Int = PADDING): Map<String, Slot> {
        if (candidates.isEmpty()) return emptyMap()
        val taken = ArrayList<Box>(candidates.size)
        val out = LinkedHashMap<String, Slot>(candidates.size)
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.id })) {
            val free = c.slots.firstOrNull { s ->
                val box = Box(s.x, s.y, c.width, c.height)
                taken.none { overlaps(it, box, padding) }
            } ?: continue
            taken.add(Box(free.x, free.y, c.width, c.height))
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
