package io.mp.sightline.ui.state

/**
 * The model picker's list, and the one honest answer to "which model am I talking to?".
 *
 * There is **no way to enumerate models**: the CLI has no list command, and the plugin never holds an
 * API key — the CLI owns authentication. So the catalogue is built from the three things that are
 * actually knowable, and nothing is invented:
 *
 * 1. The **aliases the CLI documents** (`--model` accepts `opus`/`sonnet`/`haiku`/`fable`). Stable, and
 *    they always mean "the latest of that tier", which is what a picker should offer.
 * 2. The **model the CLI reports it is using**, from the `model` field of its `system/init` event. An
 *    alias resolves to a dated id (`sonnet` → `claude-sonnet-5`), and only the CLI knows the mapping —
 *    so the current entry is *reported*, never derived by matching strings here.
 * 3. Whatever **custom ids the user has pinned**, kept because a dated id is the whole point of pinning.
 *
 * Platform-free and unit-tested; the Swing menu is a thin projection of [entries].
 */
object ModelCatalog {

    /** A picker row. [id] is what goes to `--model` / `set_model`; null means "the CLI's own default". */
    data class Entry(
        val id: String?,
        val label: String,
        val detail: String?,
        val current: Boolean,
        val custom: Boolean = false,
    )

    /** The CLI's documented aliases, in capability order. An alias always means "latest of that tier". */
    val ALIASES: List<Pair<String, String>> = listOf(
        "opus" to "Most capable",
        "sonnet" to "Balanced",
        "haiku" to "Fastest",
        "fable" to "Fable",
    )

    /** Display name for an alias or id: aliases are title-cased, a full id is shown verbatim. */
    fun label(id: String?): String = when {
        id.isNullOrBlank() -> "Default"
        ALIASES.any { it.first == id } -> id.replaceFirstChar { it.uppercase() }
        else -> id
    }

    /**
     * Builds the picker rows.
     *
     * @param selected what settings ask for — an alias, a full id, or blank for the CLI default.
     * @param reported what the CLI last said it was actually running ([resolvedNote] explains it).
     * @param customs ids the user pinned, most recent first; blanks and duplicates of an alias are dropped.
     *
     * Exactly one row is marked current, and it is chosen by what was **selected**, not by trying to
     * match [reported] against an alias — `sonnet` and `claude-sonnet-5` are the same model, and a
     * string comparison would mark neither.
     */
    fun entries(selected: String?, reported: String? = null, customs: List<String> = emptyList()): List<Entry> {
        val sel = selected?.trim().orEmpty()
        val out = ArrayList<Entry>()

        out += Entry(
            id = null,
            label = "Default",
            detail = "Whatever the CLI is configured to use",
            current = sel.isEmpty(),
        )
        for ((alias, detail) in ALIASES) {
            out += Entry(alias, label(alias), detail, current = sel == alias)
        }

        val aliasIds = ALIASES.map { it.first }.toSet()
        val pinned = customs.map { it.trim() }.filter { it.isNotEmpty() && it !in aliasIds }.distinct()
        for (id in pinned) {
            out += Entry(id, id, "Pinned", current = sel == id, custom = true)
        }

        // A selected id that isn't an alias and isn't pinned yet (set in Settings, or via extraArgs)
        // still has to appear, or the menu would show nothing as current.
        if (sel.isNotEmpty() && sel !in aliasIds && sel !in pinned) {
            out += Entry(sel, sel, "In use", current = true, custom = true)
        }
        return out
    }

    /**
     * The line under the menu telling the user what is *actually* running, or null when the CLI hasn't
     * said yet. Worded as a report, because an alias resolving to a dated id is the CLI's business and
     * this panel only relays it — the same reason the permission-mode fallback is surfaced rather than
     * assumed.
     */
    fun resolvedNote(reported: String?): String? =
        reported?.trim()?.takeIf { it.isNotEmpty() }?.let { "Currently running $it" }

    /** Keeps the pinned list bounded and most-recent-first, without duplicating an alias. */
    fun remember(customs: List<String>, id: String, max: Int = MAX_CUSTOM): List<String> {
        val clean = id.trim()
        if (clean.isEmpty() || ALIASES.any { it.first == clean }) return customs
        return (listOf(clean) + customs.filter { it != clean }).take(max)
    }

    const val MAX_CUSTOM = 8
}
