package io.mp.sightline.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads the MCP servers a user has **declared**, from the same places the CLI reads them.
 *
 * Pure: it is handed file *text*, never a path, so the whole of the tricky part — three scopes, a
 * disabled-list, malformed input, path keys that don't quite match — is unit-tested with no
 * filesystem. The platform half ([io.mp.sightline.ide.McpConfigWatcher]) only decides when to read.
 *
 * The two files, and why both:
 * - `~/.claude.json` holds `mcpServers` at the top level ([McpScope.USER]) and, per project,
 *   `projects.<cwd>.mcpServers` ([McpScope.LOCAL]). `claude mcp add` writes the local one by default,
 *   which is where the case this feature exists for actually lands.
 * - `<project>/.mcp.json` holds the checked-in servers ([McpScope.PROJECT]).
 *
 * **Nothing here throws.** This runs against a file another program rewrites constantly, and a
 * half-written or future-shaped `~/.claude.json` must degrade to "I could not tell", never to an
 * exception on a background thread or a fabricated empty answer that reads as "you declared nothing".
 * [read] returns null for unreadable input and an empty list for genuinely-none, and those are
 * different answers — the same distinction `HealthStatus.UNKNOWN` exists to preserve.
 */
object McpConfigReader {

    /**
     * Declared servers from `~/.claude.json`.
     *
     * @param userConfigJson the file's text, or null if it could not be read.
     * @param projectKeys candidate keys for this project under `projects`, most-preferred first. More
     *   than one because the CLI keys by its own cwd string, and a path can reach us in more than one
     *   form (a symlinked or `/private`-prefixed temp dir on macOS being the common case). Matching a
     *   single guessed spelling would silently report "no local servers" for a project that has them.
     * @return null if the text could not be parsed at all; otherwise the declarations found.
     */
    fun readUserConfig(userConfigJson: String?, projectKeys: List<String>): List<DeclaredServer>? {
        val root = parseObject(userConfigJson) ?: return null
        val out = ArrayList<DeclaredServer>()
        out += servers(root.optObject("mcpServers"), McpScope.USER, exclude = emptySet())

        val entry = projectEntry(root, projectKeys)
        if (entry != null) out += servers(entry.optObject("mcpServers"), McpScope.LOCAL, exclude = emptySet())
        return out
    }

    /**
     * Declared servers from a project's `.mcp.json`, minus any the user has explicitly disabled.
     *
     * The disabled list lives in `~/.claude.json` (`projects.<cwd>.disabledMcpjsonServers`), which is
     * why it is passed in rather than read here: honouring it is the difference between reporting a
     * server the user has already said no to and leaving them alone.
     */
    fun readProjectConfig(projectMcpJson: String?, disabled: Set<String> = emptySet()): List<DeclaredServer>? {
        if (projectMcpJson == null) return emptyList()
        val root = parseObject(projectMcpJson) ?: return null
        return servers(root.optObject("mcpServers"), McpScope.PROJECT, exclude = disabled)
    }

    /** Names under `projects.<cwd>.disabledMcpjsonServers`, or empty when absent/unparseable. */
    fun disabledProjectServers(userConfigJson: String?, projectKeys: List<String>): Set<String> {
        val root = parseObject(userConfigJson) ?: return emptySet()
        val entry = projectEntry(root, projectKeys) ?: return emptySet()
        val arr = entry.get("disabledMcpjsonServers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptySet()
        return arr.mapNotNull { it.takeIf(::isStringPrimitive)?.asString }.toSet()
    }

    /**
     * Everything declared for this project, de-duplicated by name.
     *
     * A name can appear in more than one scope, and the CLI resolves that itself; what matters here is
     * only that one declaration wins so a server is never sent twice. The more specific scope wins
     * (local → project → user), which is the ordering every layered-config tool uses.
     */
    fun merge(vararg groups: List<DeclaredServer>?): List<DeclaredServer> {
        val rank = mapOf(McpScope.LOCAL to 0, McpScope.PROJECT to 1, McpScope.USER to 2)
        val best = LinkedHashMap<String, DeclaredServer>()
        for (g in groups) for (s in g.orEmpty()) {
            val cur = best[s.name]
            if (cur == null || rank.getValue(s.scope) < rank.getValue(cur.scope)) best[s.name] = s
        }
        return best.values.toList()
    }

    // ---- internals ----

    private fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun projectEntry(root: JsonObject, projectKeys: List<String>): JsonObject? {
        val projects = root.optObject("projects") ?: return null
        for (k in projectKeys) projects.optObject(k)?.let { return it }
        return null
    }

    /**
     * Turns a `mcpServers` map into declarations, keeping each config as verbatim JSON.
     *
     * A non-object entry is skipped rather than coerced: the CLI would reject it anyway, and inventing
     * a shape for it would put a malformed server on the wire under a name the user recognises.
     */
    private fun servers(map: JsonObject?, scope: McpScope, exclude: Set<String>): List<DeclaredServer> {
        if (map == null) return emptyList()
        val out = ArrayList<DeclaredServer>()
        for ((name, value) in map.entrySet()) {
            if (name.isBlank() || name in exclude) continue
            if (value == null || !value.isJsonObject) continue
            out += DeclaredServer(name, scope, ServerConfigJson(value.asJsonObject.toString()))
        }
        return out
    }

    private fun JsonObject.optObject(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun isStringPrimitive(e: JsonElement): Boolean = e.isJsonPrimitive && e.asJsonPrimitive.isString
}
