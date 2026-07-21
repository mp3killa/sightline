package io.mp.sightline.ui.state

/**
 * Line-level diff between two texts — the rows every edit in the transcript is drawn from.
 *
 * Platform-free so it can be tested directly, which matters more here than for most of this package:
 * it is the only real algorithm behind what a user reads before approving a change, and a diff that
 * misattributes a line is a diff that gets a wrong edit approved.
 *
 * The rows it produces are what [DiffPresentation] counts, sizes and collapses — see its `Rows are
 * (kind, text)` contract.
 */
object LineDiff {

    /** Row kinds, matching the strings [DiffPresentation] expects. */
    const val ADD = "add"
    const val DEL = "del"
    const val CTX = "ctx"

    /**
     * Above this many cells the LCS table is abandoned. The quadratic table is fine for the edits that
     * actually appear in a transcript and ruinous for a whole-file rewrite, so past the ceiling the
     * result degrades to "all of the old, then all of the new" — still correct as a description of the
     * change, just not minimal.
     */
    const val MAX_CELLS = 400_000L

    /**
     * Diffs [aStr] against [bStr], returning `(kind, line)` rows in file order.
     *
     * Both inputs are split on `\n`, so an empty string is one empty line — that is deliberate: `Write`
     * to a new file passes `""` as the old text and must render as an addition, not as nothing.
     */
    fun diff(aStr: String, bStr: String): List<Pair<String, String>> {
        val a = aStr.split("\n")
        val b = bStr.split("\n")
        val n = a.size
        val m = b.size
        if (n.toLong() * m > MAX_CELLS) return a.map { DEL to it } + b.map { ADD to it }

        // dp[i][j] = length of the longest common subsequence of a[i..] and b[j..].
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<Pair<String, String>>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(CTX to a[i]); i++; j++ }
                // Ties go to deletion, so a replaced line reads "- old" then "+ new" rather than the
                // reverse — the order a reviewer expects.
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DEL to a[i]); i++ }
                else -> { out.add(ADD to b[j]); j++ }
            }
        }
        while (i < n) out.add(DEL to a[i++])
        while (j < m) out.add(ADD to b[j++])
        return out
    }

    /** The gutter marker for a row kind, as the unified view and the clipboard copy both render it. */
    fun sign(kind: String): String = when (kind) {
        ADD -> "+ "
        DEL -> "- "
        else -> "  "
    }
}
