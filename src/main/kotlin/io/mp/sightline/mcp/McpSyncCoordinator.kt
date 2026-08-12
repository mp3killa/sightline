package io.mp.sightline.mcp

import com.google.gson.JsonObject

/**
 * Drives live MCP sync against a running session: ask what it has, decide, tell it, report back.
 *
 * Platform-free by construction — it is handed a way to write a control-request line and a way to put
 * a sentence in the transcript, and knows nothing about Swing, files or the CLI process. That is what
 * makes the awkward part testable: this is a three-step asynchronous exchange
 * (`mcp_status` → `mcp_set_servers` → `mcp_status` again) whose failure modes are the interesting ones.
 *
 * Two properties are deliberate and load-bearing:
 *
 * - **Nothing here ever blocks.** `mcp_set_servers` does not answer until every server has connected
 *   or timed out — measured at 30s for a server that hangs (docs/PROTOCOL.md §5). So sync is never
 *   started from the send path and never waited on; the user types and sends throughout, and the
 *   result arrives as a notice when it arrives. [timedOut] exists so a reply that never comes is
 *   reported as not-knowing rather than leaving a promise unresolved.
 * - **The managed set is only committed once the CLI confirms it.** Assuming success would leave this
 *   class believing it owns a server the CLI never registered, and the next sync would then quietly
 *   drop something else.
 */
class McpSyncCoordinator(
    /** Writes a control-request line to the running CLI; false when there is no process to write to. */
    private val send: (String) -> Boolean,
    /** Puts a sentence in the transcript. */
    private val notice: (String) -> Unit,
    private val newRequestId: () -> String,
) {

    /** What Sightline last successfully told the CLI to manage. Empty for a fresh process. */
    var managed: List<DeclaredServer> = emptyList()
        private set

    private var statusId: String? = null
    private var syncId: String? = null

    /** The set sent in the request now in flight, promoted to [managed] only when the CLI confirms it. */
    private var inFlight: List<DeclaredServer> = emptyList()

    /** True when the status request outstanding is the follow-up that reports what actually loaded. */
    private var confirming = false

    /** The declaration snapshot the outstanding status request is being evaluated against. */
    private var pendingDeclared: List<DeclaredServer> = emptyList()
    private var pendingAutoSync = true

    /** Servers already explained as "declared but not started", so the notice is not repeated. */
    private val reportedPending = LinkedHashSet<String>()

    /**
     * Server names the session was last seen to have, including the ones the CLI loaded by itself.
     *
     * Only [worthChecking] uses it, and only to avoid re-asking about a server that is plainly already
     * there. Without it every declared-and-already-loaded server looks unexplained forever, and a file
     * that other processes rewrite constantly would drive a status round-trip on every single check.
     */
    private var knownLoaded: Set<String> = emptySet()

    /** The result awaiting the confirming status round-trip that will give it tool counts. */
    private var lastResult: McpSyncResult? = null

    /**
     * Set once this CLI has said it does not support `mcp_set_servers`. It will not start supporting it
     * mid-process, so trying again every few seconds would achieve nothing except repeating the notice.
     */
    private var unsupported = false

    /**
     * The last set actually put on the wire, whether or not it worked.
     *
     * Guards the case where the request *fails* — a failure leaves [managed] empty, which makes the same
     * servers look untried on the very next poll. Without this the panel would retry, and re-announce
     * the same error, every couple of seconds for as long as the declaration stood.
     */
    private var lastAttempted: Set<Pair<String, String>> = emptySet()

    val busy: Boolean get() = statusId != null || syncId != null

    /**
     * A new process means the CLI has just read every config file itself, so nothing is managed and
     * nothing said before still applies.
     */
    fun onProcessStarted() {
        managed = emptyList()
        inFlight = emptyList()
        statusId = null
        syncId = null
        confirming = false
        reportedPending.clear()
        knownLoaded = emptySet()
        lastResult = null
        unsupported = false
        lastAttempted = emptySet()
    }

    /**
     * Offers a fresh view of what the user has declared. Starts an exchange only when one is warranted.
     *
     * @param idle whether a turn is *not* in progress. A sync is held back mid-turn: adding servers
     *   while the model is working is pointless (the turn's tools are already resolved) and removing
     *   one could pull a server out from under a tool call in flight.
     * @return true if an exchange was started.
     */
    fun offer(declared: List<DeclaredServer>, autoSync: Boolean, idle: Boolean): Boolean {
        if (busy || !idle || unsupported) return false
        // Cheap pre-check against the last committed set: if there is provably nothing to do and
        // nothing new to explain, don't even spend a status round-trip.
        if (!worthChecking(declared, autoSync)) return false
        pendingDeclared = declared
        pendingAutoSync = autoSync
        val id = newRequestId()
        if (!send(McpControlJson.statusRequest(id))) return false
        statusId = id
        confirming = false
        return true
    }

    /**
     * Feeds a `control_response` line in. Returns true if it was one of ours and was consumed — the
     * caller must not treat a consumed line as anything else.
     */
    fun onControlResponse(line: JsonObject): Boolean {
        val id = McpControlJson.responseId(line) ?: return false
        return when (id) {
            statusId -> { statusId = null; onStatus(line); true }
            syncId -> { syncId = null; onSync(line); true }
            else -> false
        }
    }

    /**
     * Called when a reply has not arrived in time. Reports not-knowing and lets go of the exchange, so
     * a wedged request cannot block every future sync for the life of the session.
     */
    fun timedOut() {
        val wasSyncing = syncId != null
        statusId = null
        syncId = null
        inFlight = emptyList()
        confirming = false
        if (wasSyncing) notice(McpNotices.inconclusiveNotice())
    }

    // ---- the exchange ----

    private fun onStatus(line: JsonObject) {
        val loaded = McpControlJson.parseStatus(line) ?: return
        knownLoaded = loaded.map { it.name }.toSet()
        if (confirming) {
            confirming = false
            reportResult(loaded)
            return
        }

        val plan = McpSyncPolicy.plan(pendingDeclared, loaded, managed, pendingAutoSync)
        explainPending(plan.report, pendingAutoSync)
        if (!plan.needsRequest) return
        // Already tried exactly this and it did not take. Retrying on a timer would only repeat the
        // error; the next genuine edit to the config changes the set and is tried immediately.
        if (identity(plan.send) == lastAttempted) return

        val id = newRequestId()
        inFlight = plan.send
        lastAttempted = identity(plan.send)
        if (!send(McpControlJson.setServersRequest(id, plan.send))) { inFlight = emptyList(); return }
        syncId = id
    }

    private fun identity(servers: List<DeclaredServer>): Set<Pair<String, String>> =
        servers.map { it.name to it.config.json }.toSet()

    private fun onSync(line: JsonObject) {
        val error = McpControlJson.errorOf(line)
        if (error != null) {
            // An old CLI is not a breakage, and must not be reported as one. Anything else is a real
            // error and is surfaced with the CLI's own words.
            if (McpControlJson.isUnsupported(error)) {
                unsupported = true
                notice(McpNotices.unsupportedNotice(inFlight.map { it.name } - managed.map { it.name }.toSet()))
            } else {
                notice("Sightline could not update this conversation's MCP servers: $error")
            }
            inFlight = emptyList()
            return
        }
        val result = McpControlJson.parseSyncResult(line) ?: run { inFlight = emptyList(); return }
        managed = inFlight
        inFlight = emptyList()
        lastResult = result
        if (result.isNoop) return

        // Ask again rather than guess: only the CLI knows how many tools a server actually exposed,
        // and a server it accepted can still have failed to connect.
        val id = newRequestId()
        if (send(McpControlJson.statusRequest(id))) {
            statusId = id
            confirming = true
        } else {
            reportResult(emptyList())
        }
    }

    private fun reportResult(loaded: List<LoadedServer>) {
        val result = lastResult ?: return
        lastResult = null
        val counts = loaded.associate { it.name to it.toolCount }
        McpNotices.syncNotice(result, counts)?.let(notice)
    }

    /** Says once, per server, why something declared is not in this conversation. */
    private fun explainPending(report: List<DeclaredServer>, autoSync: Boolean) {
        val fresh = report.filter { reportedPending.add(it.name) }
        if (fresh.isEmpty()) return
        McpNotices.pendingNotice(fresh, autoSync)?.let(notice)
    }

    /**
     * Whether a status round-trip could possibly change anything.
     *
     * Compares the declaration against the committed managed set only — it cannot see what the CLI
     * loaded by itself, so it is deliberately generous: it may say yes and find nothing to do, but it
     * never says no when there is. Its whole job is to stop a config file that is rewritten constantly
     * from generating an endless stream of pointless exchanges.
     */
    private fun worthChecking(declared: List<DeclaredServer>, autoSync: Boolean): Boolean {
        val managedNames = managed.map { it.name }.toSet()
        val unexplained = declared.any {
            it.name !in managedNames && it.name !in reportedPending && it.name !in knownLoaded
        }
        val configChanged = managed.any { m -> declared.none { it.name == m.name && it.config.json == m.config.json } }
        return unexplained || configChanged || (!autoSync && managed.isNotEmpty())
    }
}
