package io.mp.sightline.mcp

/**
 * The model behind **live MCP sync**: keeping a running session's MCP servers in step with the ones
 * the user has declared, without restarting the CLI and losing the conversation.
 *
 * The problem this exists for: `claude mcp add playwright …` while a session is running leaves that
 * session unable to see the server, because its servers were resolved at launch. The advice that
 * follows — quit and reopen — costs the conversation, and the user is left hand-writing a handoff.
 * The CLI can in fact be told about new servers mid-session (docs/PROTOCOL.md §5), so Sightline does
 * that instead.
 *
 * Everything here is platform-free and unit-tested; the Swing and filesystem halves are thin.
 */

/**
 * Where a declared server came from. The distinction is not cosmetic — it decides whether Sightline
 * may start the server's process on its own, so it is part of the model rather than a UI label.
 *
 * [USER] and [LOCAL] both come from `~/.claude.json`, which is only written when the user runs
 * `claude mcp add` (or edits it by hand): a deliberate, typed act naming a specific server. [PROJECT]
 * comes from a checked-in `.mcp.json`, which can appear in a working tree from a `git pull` that the
 * user never read. Same file format, very different provenance.
 */
enum class McpScope(val label: String) {
    /** `mcpServers` at the top level of `~/.claude.json` — every project this user opens. */
    USER("user"),

    /** `projects.<cwd>.mcpServers` in `~/.claude.json` — this project, this user. */
    LOCAL("local"),

    /** `mcpServers` in the project's own `.mcp.json` — shared with anyone who clones the repo. */
    PROJECT("project"),
    ;

    /**
     * Whether Sightline may launch this server by itself when it appears mid-session.
     *
     * True only for what the user typed. A `.mcp.json` server is *not* auto-started: the CLI will load
     * it at the next launch anyway, so this costs the user nothing but a moment — and "a file that
     * arrived from version control silently started a process" is not a trade this plugin makes on
     * someone's behalf. It is still reported, so the state is never a mystery.
     */
    val autoStartable: Boolean get() = this != PROJECT
}

/**
 * A server's configuration, held as the **verbatim JSON text** of its config object.
 *
 * Deliberately opaque. A stdio server config carries `command`, `args` and `env`, and `env` is exactly
 * where an API token lives — so the value is never destructured into fields that some future summary,
 * log line, tooltip or activity-map node could reach for. It has one legitimate destination, the CLI's
 * own stdin, and [toString] is overridden so that an accidental interpolation into a log or a message
 * prints a placeholder rather than a secret. The same instinct as the owner-only `--mcp-config` file:
 * the payload is trusted, the places it might leak into are not.
 */
@JvmInline
value class ServerConfigJson(val json: String) {
    override fun toString(): String = "ServerConfigJson(<redacted>)"
}

/**
 * A server the user has declared somewhere, with the scope that decides how it may be treated.
 *
 * Not a data class: the generated `toString`/`equals` would reach into [config]. Equality is by name
 * and config text, which is what "has this declaration changed?" actually means.
 */
class DeclaredServer(
    val name: String,
    val scope: McpScope,
    val config: ServerConfigJson,
) {
    override fun toString(): String = "DeclaredServer($name, ${scope.label})"

    override fun equals(other: Any?): Boolean =
        other is DeclaredServer && other.name == name && other.scope == scope && other.config.json == config.json

    override fun hashCode(): Int = (name.hashCode() * 31 + scope.hashCode()) * 31 + config.json.hashCode()
}

/**
 * A server the **running session** reports, from a `mcp_status` control response or `system/init`.
 * This is what the CLI says it has, never what Sightline hopes it has.
 */
data class LoadedServer(
    val name: String,
    /** The CLI's own word: `connected`, `failed`, `needs-auth`, … Relayed, never normalised away. */
    val status: String,
    /** Tools the CLI reports for it, or null when the status response didn't say. */
    val toolCount: Int? = null,
    /** The CLI's scope word (`user`, `project`, `dynamic`, …) — informational only. */
    val scope: String? = null,
) {
    val connected: Boolean get() = status.equals("connected", ignoreCase = true)
}

/**
 * The outcome of an `mcp_set_servers` request, exactly as the CLI reported it.
 *
 * [errors] is keyed by server name and its values are the CLI's own sentences ("Executable not found
 * in $PATH: …", "MCP server \"x\" connection timed out after 30000ms"), which are better than anything
 * this plugin could infer — a server named in [added] can still be in [errors], because "added" means
 * "registered", not "working".
 */
data class McpSyncResult(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
) {
    /** Registered *and* not reported broken — the only ones that actually gained the session anything. */
    val addedCleanly: List<String> get() = added.filter { it !in errors }

    val isNoop: Boolean get() = added.isEmpty() && removed.isEmpty() && errors.isEmpty()
}
