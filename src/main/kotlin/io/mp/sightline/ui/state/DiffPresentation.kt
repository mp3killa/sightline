package io.mp.sightline.ui.state

/** How a diff is laid out for the width available to it. */
enum class DiffLayout {
    /** One column, `+`/`-` prefixed. Always safe. */
    UNIFIED,

    /** Old and new side by side. Needs real width or both columns become unreadable. */
    SIDE_BY_SIDE,
}

/** Added / removed line counts for a rendered diff. */
data class DiffStat(val added: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && removed == 0
}

/**
 * Presentation decisions for a file-edit diff: how big it is, how to say so, whether it fits
 * side-by-side, and how much of it to show before collapsing.
 *
 * A file edit is the *point* of a turn, so it earns more structure than a routine read — but an
 * unbounded diff is worse than no diff: a `Write` renders the whole new file as all-adds, which
 * before this existed could bury the rest of the conversation.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object DiffPresentation {

    /** Rows are (kind, text) where kind is "add" | "del" | "ctx" — the shape `lineDiff` produces. */
    fun stat(rows: List<Pair<String, String>>): DiffStat =
        DiffStat(added = rows.count { it.first == "add" }, removed = rows.count { it.first == "del" })

    /**
     * "Added 33 lines · Removed 8 lines". Singular/plural matters here because these strings sit in a
     * header a reader scans, and "Added 1 lines" reads as a bug in the tool.
     */
    fun headerText(stat: DiffStat): String {
        fun part(n: Int, verb: String) = "$verb $n ${if (n == 1) "line" else "lines"}"
        return when {
            stat.isEmpty -> "No changes"
            stat.added > 0 && stat.removed > 0 -> "${part(stat.added, "Added")} · ${part(stat.removed, "Removed")}"
            stat.added > 0 -> part(stat.added, "Added")
            else -> part(stat.removed, "Removed")
        }
    }

    /**
     * Minimum *logical* width before a two-column diff is worth it. Below this each column is too
     * narrow to hold a real line of code and side-by-side is strictly worse than unified.
     */
    const val SIDE_BY_SIDE_MIN_WIDTH = 820

    fun layout(widthPx: Int, scale: Float = 1f): DiffLayout {
        val logical = if (scale > 0f) widthPx / scale else widthPx.toFloat()
        return if (logical >= SIDE_BY_SIDE_MIN_WIDTH) DiffLayout.SIDE_BY_SIDE else DiffLayout.UNIFIED
    }

    /** Past this many rows a diff is collapsed to [COLLAPSED_ROWS] with an expand control. */
    const val COLLAPSE_ABOVE = 24
    const val COLLAPSED_ROWS = 14

    /**
     * Hard ceiling on rows *rendered at all*, even expanded. A whole-file `Write` can be thousands of
     * lines; past this the transcript stops being navigable and the editor is the right tool. The
     * remainder is reported, never silently dropped — see [overflowText].
     */
    const val MAX_ROWS = 400

    fun isCollapsible(rowCount: Int): Boolean = rowCount > COLLAPSE_ABOVE

    /** How many rows to show given the row count and whether the user has expanded it. */
    fun visibleRows(rowCount: Int, expanded: Boolean): Int = when {
        rowCount <= COLLAPSE_ABOVE -> rowCount
        expanded -> minOf(rowCount, MAX_ROWS)
        else -> COLLAPSED_ROWS
    }

    fun overflow(rowCount: Int, expanded: Boolean): Int = (rowCount - visibleRows(rowCount, expanded)).coerceAtLeast(0)

    /** Never let a cap pass silently — a truncated diff that looks complete is a lie. */
    fun overflowText(hidden: Int): String? =
        if (hidden <= 0) null else "$hidden more ${if (hidden == 1) "line" else "lines"} not shown — open the file to see the rest"

    fun expandText(rowCount: Int): String = "Expand ($rowCount lines)"
}
