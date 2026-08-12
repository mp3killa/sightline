package io.mp.sightline.mcp

/**
 * Every sentence live MCP sync puts in the transcript.
 *
 * Gathered in one unit-tested place for two reasons. The first is the usual one: wording that says
 * what actually happened is most of this feature's value, and it is easier to keep honest when it is
 * all visible at once. The second is a guardrail — a server config can hold a credential in `env`, so
 * a test asserts that **no notice ever contains anything but names, counts and the CLI's own words**.
 * Nothing here is handed a [ServerConfigJson] at all, which is what makes that test easy to keep true.
 *
 * The tone follows the rest of the panel: state the change and its consequence, never promise
 * something that did not happen, and never a cheerful summary over a failure.
 */
object McpNotices {

    /**
     * What to say after an `mcp_set_servers` the user did not ask for but should know about.
     *
     * Returns null when there is genuinely nothing to report — a no-op sync is not news, and a panel
     * that narrates its own background bookkeeping trains people to skim past the notices that matter.
     *
     * @param toolCounts tool counts by server from the follow-up `mcp_status`; a missing or null entry
     *   simply drops the count rather than printing a confident "0 tools".
     */
    fun syncNotice(result: McpSyncResult, toolCounts: Map<String, Int?> = emptyMap()): String? {
        if (result.isNoop) return null
        val parts = ArrayList<String>()

        val ok = result.addedCleanly
        if (ok.isNotEmpty()) {
            val listed = ok.joinToString(", ") { name ->
                val n = toolCounts[name]
                if (n != null && n > 0) "$name ($n ${plural(n, "tool", "tools")})" else name
            }
            parts += "Added ${plural(ok.size, "MCP server", "MCP servers")} $listed to this conversation" +
                " — no restart, nothing lost."
        }

        // Named individually and with the CLI's own reason: "playwright failed" sends someone hunting,
        // "Executable not found in $PATH: npx" ends the search.
        for ((name, why) in result.errors) parts += "MCP server $name could not start: $why"

        val gone = result.removed.filter { it !in result.errors }
        if (gone.isNotEmpty()) {
            parts += "Removed ${plural(gone.size, "MCP server", "MCP servers")} ${gone.joinToString(", ")}" +
                " — no longer in your configuration."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * What to say about servers that are declared but deliberately **not** started — a checked-in
     * `.mcp.json` that appeared in the working tree, or anything at all when auto-sync is off.
     *
     * Worded so it cannot be read as a failure, and so the way out is in the sentence. It says what
     * *will* happen rather than offering an action, because there is nothing here to click.
     */
    fun pendingNotice(report: List<DeclaredServer>, autoSync: Boolean): String? {
        if (report.isEmpty()) return null
        val names = report.joinToString(", ") { it.name }
        val subject = "${plural(report.size, "MCP server", "MCP servers")} $names"
        return if (!autoSync) {
            "$subject ${plural(report.size, "is", "are")} configured but not in this conversation. " +
                "Live MCP sync is off, so ${plural(report.size, "it", "they")} will load when you start a new one."
        } else {
            // The project-scope case. The reason is stated because "why did it do that?" is otherwise a
            // fair question, and the answer is the whole justification for not acting.
            "$subject ${plural(report.size, "comes", "come")} from this project's .mcp.json rather than from " +
                "your own configuration, so ${plural(report.size, "it was", "they were")} not started " +
                "automatically. ${plural(report.size, "It", "They")} will load when you start a new conversation."
        }
    }

    /**
     * The fallback for a CLI too old to accept `mcp_set_servers`. Says which of the two things is true —
     * the feature is unavailable here, not broken — and what still works.
     */
    fun unsupportedNotice(names: List<String>): String {
        val subject = if (names.isEmpty()) "New MCP servers" else
            "${plural(names.size, "MCP server", "MCP servers")} ${names.joinToString(", ")}"
        return "$subject cannot be added to a conversation already in progress by this version of the " +
            "Claude CLI. Start a new conversation to pick ${plural(names.size, "it", "them")} up, or update the CLI."
    }

    /** A sync that could not be confirmed. Not-knowing is reported as not-knowing. */
    fun inconclusiveNotice(): String =
        "Sightline asked Claude to load your new MCP servers but did not get a reply, so it cannot say " +
            "whether they are available. Check with /mcp, or start a new conversation."

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
}
