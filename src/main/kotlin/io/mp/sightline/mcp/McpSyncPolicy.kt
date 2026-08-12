package io.mp.sightline.mcp

/**
 * Decides what to tell a **running** session about the MCP servers the user has declared.
 *
 * The one non-obvious rule, and the reason this is a class rather than a diff at the call site:
 * `mcp_set_servers` is *authoritative over the set Sightline sent it* (docs/PROTOCOL.md §5). Once
 * `playwright` has been added dynamically the session reports it as loaded — so a naive
 * "send what is declared but not loaded" would send it no longer, and the CLI would take that as
 * "remove it" and tear the server down a moment after starting it. Every request therefore carries the
 * **whole managed set**, and [plan] is given what was last sent so it can construct that.
 *
 * The other rule is [McpScope.autoStartable]: a server the user typed may be started for them; one
 * that appeared in a `.mcp.json` is reported instead. See [McpScope].
 *
 * Platform-free and unit-tested.
 */
object McpSyncPolicy {

    /**
     * @param send the complete set to transmit — the authoritative managed set, not a delta.
     * @param newlyManaged the part of [send] this plan adds, for wording the notice.
     * @param dropped names being given up because their declaration is gone.
     * @param report declared, absent from the session, and deliberately **not** started — a checked-in
     *   `.mcp.json` server, or anything at all when auto-sync is off. Told to the user, never launched.
     * @param needsRequest whether [send] actually differs from what was last sent. False means do
     *   nothing: an identical request is a no-op to the CLI, but it can still block for up to 30s while
     *   a broken server times out, so a pointless one is not free.
     */
    data class Plan(
        val send: List<DeclaredServer>,
        val newlyManaged: List<DeclaredServer>,
        val dropped: List<String>,
        val report: List<DeclaredServer>,
        val needsRequest: Boolean,
    )

    /**
     * @param declared everything the config files declare for this project ([McpConfigReader.merge]).
     * @param loaded what the session says it has, from `mcp_status` / `system/init` — including the
     *   servers the CLI loaded by itself at launch, which must never be re-sent: they are the CLI's,
     *   and Sightline claiming them would mean a later sync could remove them.
     * @param managed exactly what Sightline last sent in an `mcp_set_servers` request (empty at launch,
     *   because a fresh process reads the config files itself).
     * @param autoSync the user's setting. Off means observe and report, never act.
     */
    fun plan(
        declared: List<DeclaredServer>,
        loaded: List<LoadedServer>,
        managed: List<DeclaredServer>,
        autoSync: Boolean,
    ): Plan {
        val declaredByName = declared.associateBy { it.name }
        val loadedNames = loaded.map { it.name }.toSet()
        val managedNames = managed.map { it.name }.toSet()

        // Keep managing anything still declared, picking up an edited config; give up anything whose
        // declaration has gone, which is how a `claude mcp remove` reaches the running session.
        val keep = managed.mapNotNull { declaredByName[it.name] }
        val dropped = managed.map { it.name }.filter { it !in declaredByName }

        // A server the session already has is not ours to manage — that includes the ones the CLI
        // loaded from the very same files at launch, and its own `ide` bridge.
        val missing = declared.filter { it.name !in loadedNames && it.name !in managedNames }
        val startable = missing.filter { autoSync && it.scope.autoStartable }
        val report = missing.filter { !autoSync || !it.scope.autoStartable }

        val send = (keep + startable).distinctBy { it.name }
        return Plan(
            send = send,
            newlyManaged = startable,
            dropped = dropped,
            report = report,
            needsRequest = identity(send) != identity(managed),
        )
    }

    /**
     * What makes two managed sets the same request: the names **and** the config text. Comparing names
     * alone would miss an edited server (a changed command or a corrected token), which is exactly the
     * case where a re-send is the whole point.
     */
    private fun identity(servers: List<DeclaredServer>): Set<Pair<String, String>> =
        servers.map { it.name to it.config.json }.toSet()
}
