package io.mp.sightline.mcp

import com.google.gson.JsonObject
import io.mp.sightline.process.UserMessageJson

/**
 * The wire half of live MCP sync: builds the two control requests and reads their replies.
 *
 * Both requests and both reply shapes were verified empirically against CLI 2.1.228 — see
 * docs/PROTOCOL.md §5, which records the probes. Kept apart from the Swing and process layers so the
 * format is unit-tested rather than trusted.
 *
 * Escaping goes through [UserMessageJson.escape] rather than a second copy. The server *configs* are
 * spliced in as the verbatim JSON they already are ([ServerConfigJson]) — re-encoding them would mean
 * parsing a payload that may hold a credential, for no gain.
 */
object McpControlJson {

    /**
     * `mcp_set_servers` — "Replaces the set of dynamically managed MCP servers."
     *
     * [servers] must be the **complete** managed set; see [McpSyncPolicy]. An empty set is meaningful
     * and legal: it gives up everything Sightline was managing.
     */
    fun setServersRequest(requestId: String, servers: List<DeclaredServer>): String {
        val body = servers.joinToString(",") { """"${UserMessageJson.escape(it.name)}":${it.config.json}""" }
        return """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"mcp_set_servers","servers":{$body}}}"""
    }

    /** `mcp_status` — what the session currently has. Costs no tokens; it never reaches the model. */
    fun statusRequest(requestId: String): String =
        """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"mcp_status"}}"""

    /** The `request_id` a `control_response` line answers, or null if it isn't one. */
    fun responseId(line: JsonObject): String? =
        line.takeIf { it.get("type")?.asStringOrNull() == "control_response" }
            ?.getAsJsonObjectOrNull("response")
            ?.get("request_id")?.asStringOrNull()

    /**
     * The error text of a failed control response, or null when it succeeded.
     *
     * This is also how an **older CLI** announces it cannot do this at all: an unrecognised subtype
     * comes back as `"Unsupported control request subtype: mcp_set_servers"`, on a session that stays
     * perfectly usable afterwards (verified). [isUnsupported] is the precise fallback trigger, so the
     * feature degrades to an explanation rather than a silent nothing.
     */
    fun errorOf(line: JsonObject): String? {
        val r = line.getAsJsonObjectOrNull("response") ?: return null
        if (r.get("subtype")?.asStringOrNull() != "error") return null
        return r.get("error")?.asStringOrNull() ?: "unknown error"
    }

    /** Whether an [errorOf] text means "this CLI has no such control request". */
    fun isUnsupported(error: String): Boolean =
        error.contains("Unsupported control request subtype", ignoreCase = true)

    /**
     * The `{added, removed, errors}` payload of a successful `mcp_set_servers`.
     *
     * Returns null for a response that isn't one, rather than an empty result — "nothing changed" and
     * "this wasn't the reply I was reading" must not collapse into the same value, because the first is
     * reported to the user as a fact.
     */
    fun parseSyncResult(line: JsonObject): McpSyncResult? {
        val payload = successPayload(line) ?: return null
        if (!payload.has("added") && !payload.has("removed") && !payload.has("errors")) return null
        val errors = LinkedHashMap<String, String>()
        payload.getAsJsonObjectOrNull("errors")?.entrySet()?.forEach { (k, v) ->
            errors[k] = v?.asStringOrNull() ?: "failed"
        }
        return McpSyncResult(
            added = payload.stringList("added"),
            removed = payload.stringList("removed"),
            errors = errors,
        )
    }

    /** The `mcpServers` array of a successful `mcp_status`, or null if this isn't that reply. */
    fun parseStatus(line: JsonObject): List<LoadedServer>? {
        val payload = successPayload(line) ?: return null
        val arr = payload.get("mcpServers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.get("name")?.asStringOrNull() ?: return@mapNotNull null
            LoadedServer(
                name = name,
                status = o.get("status")?.asStringOrNull() ?: "unknown",
                toolCount = o.get("tools")?.takeIf { it.isJsonArray }?.asJsonArray?.size(),
                scope = o.get("scope")?.asStringOrNull(),
            )
        }
    }

    /** Server names from `system/init`'s `mcp_servers`, which carries name+status but no tool list. */
    fun parseInitServers(init: JsonObject): List<LoadedServer>? {
        val arr = init.get("mcp_servers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.get("name")?.asStringOrNull() ?: return@mapNotNull null
            LoadedServer(name, o.get("status")?.asStringOrNull() ?: "unknown")
        }
    }

    // ---- internals ----

    private fun successPayload(line: JsonObject): JsonObject? {
        val r = line.getAsJsonObjectOrNull("response") ?: return null
        if (r.get("subtype")?.asStringOrNull() != "success") return null
        return r.getAsJsonObjectOrNull("response")
    }

    private fun JsonObject.stringList(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it?.asStringOrNull() }.orEmpty()

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.asString
}
