package io.mp.claudecodepanel.ui.state

/**
 * Running tally of what a turn actually did, so a collapsed turn can say so in one line instead of
 * showing nothing at all.
 *
 * With `showDetails` off — the default — every tool card is hidden, so a turn that read twelve files,
 * edited four and ran the tests looks identical to one that answered from memory. This is the
 * one-line stand-in: *"17 operations · 4 files edited · 3 checks passed"*.
 *
 * Counts come from **structured** tool metadata, never from parsing rendered text. Files edited are
 * counted **distinctly** — three edits to one file is one file changed, and claiming three would
 * overstate the work.
 */
data class ProcessingSummary(
    val operations: Int = 0,
    private val editedPaths: Set<String> = emptySet(),
    val checksPassed: Int = 0,
    val checksFailed: Int = 0,
    val denied: Int = 0,
) {
    val filesEdited: Int get() = editedPaths.size
    val isEmpty: Boolean get() = operations == 0

    /**
     * Folds one completed tool call in. [path] is only meaningful for a mutating tool; a null path on
     * an edit still counts as an operation but cannot be de-duplicated, so it is not counted as a file.
     */
    fun plus(toolName: String?, outcome: ToolOutcome, path: String? = null): ProcessingSummary {
        val edits = if (toolName in ToolEventPresentation.MUTATING_TOOLS &&
            outcome == ToolOutcome.OK && !path.isNullOrBlank()
        ) editedPaths + path else editedPaths
        // A "check" is a command that ran to a verdict — that is what a reader wants counted, rather
        // than every file the agent happened to open on the way there.
        val isCheck = toolName == "Bash" && (outcome == ToolOutcome.OK || outcome == ToolOutcome.ERROR)
        return copy(
            operations = operations + 1,
            editedPaths = edits,
            checksPassed = checksPassed + if (isCheck && outcome == ToolOutcome.OK) 1 else 0,
            checksFailed = checksFailed + if (isCheck && outcome == ToolOutcome.ERROR) 1 else 0,
            denied = denied + if (outcome == ToolOutcome.BLOCKED) 1 else 0,
        )
    }

    /**
     * "17 operations · 4 files edited · 3 checks passed · 1 failed". Empty when nothing ran, so the
     * caller can hide the row entirely rather than print a confusing "0 operations".
     */
    fun text(): String {
        if (isEmpty) return ""
        val parts = ArrayList<String>(4)
        parts.add(plural(operations, "operation"))
        if (filesEdited > 0) parts.add("${plural(filesEdited, "file")} edited")
        if (checksPassed > 0) parts.add("$checksPassed ${if (checksPassed == 1) "check" else "checks"} passed")
        if (checksFailed > 0) parts.add("$checksFailed failed")
        if (denied > 0) parts.add("$denied denied")
        return parts.joinToString(" · ")
    }

    private fun plural(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
}
