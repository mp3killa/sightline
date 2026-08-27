package io.mp.sightline.process

import com.google.gson.JsonObject

/**
 * The wire half of the session-control requests Sightline sends to a **running** CLI: `interrupt`,
 * `set_permission_mode` and `set_model`.
 *
 * Every shape here was verified empirically against CLI 2.1.235 — see docs/PROTOCOL.md §6, which
 * records the probes. Kept platform-free and beside [UserMessageJson] (whose escape it reuses rather
 * than copying) so the format is unit-tested rather than trusted.
 *
 * Requests carry a **kind-prefixed** `request_id`. Correlating a reply by prefix rather than by a map
 * of outstanding ids is deliberate: a control request can go unanswered — a process can die between
 * the write and the reply — and a map would then leak an entry for the life of the panel. A prefix
 * cannot get out of step with anything, and the CLI echoes the id back verbatim ([kindOf]).
 */
object SessionControlJson {

    /** Which Sightline-issued control request a `control_response` is answering. */
    enum class Kind { INITIALIZE, INTERRUPT, PERMISSION_MODE, MODEL, REWIND }

    private val prefixes = mapOf(
        // The handshake ClaudeSession sends at launch. Its reply carries the CLI's command catalogue,
        // which is the only place descriptions and argument hints exist — see [SlashCommands].
        Kind.INITIALIZE to "init-",
        Kind.INTERRUPT to "sl-interrupt-",
        Kind.PERMISSION_MODE to "sl-permmode-",
        Kind.REWIND to "sl-rewind-",
        // Historical: this prefix predates the others and is matched as-is so a reply from a CLI that
        // was already running when the plugin updated is still recognised.
        Kind.MODEL to "model-",
    )

    fun requestId(kind: Kind, unique: String): String = prefixes.getValue(kind) + unique

    /** The [Kind] this `request_id` belongs to, or null when it is not one of ours. */
    fun kindOf(requestId: String?): Kind? {
        if (requestId == null) return null
        return prefixes.entries.firstOrNull { requestId.startsWith(it.value) }?.key
    }

    /**
     * `interrupt` — stops the turn in progress without ending the session.
     *
     * Verified against 2.1.235: the reply arrives in ~0.3s carrying `still_queued`, the process and its
     * `session_id` survive, and the next user message runs on the same process. The CLI then emits a
     * `user` message reading `[Request interrupted by user]` and a `result` with
     * `subtype:"error_during_execution"`.
     *
     * What it does **not** do is kill a command already running: a probe's `sleep 40 && touch SENTINEL`
     * created the sentinel forty seconds after the interrupt was acknowledged. The agent takes no
     * further step, but the child process runs to completion — which is why [io.mp.sightline.ui.state.StopPolicy]
     * words the notice the way it does rather than claiming everything stopped.
     */
    fun interruptRequest(requestId: String): String =
        """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"interrupt"}}"""

    /**
     * `set_permission_mode` — changes the permission policy of the running session.
     *
     * Verified against 2.1.235: the reply is `{"mode":"<mode>"}` and the CLI additionally emits a
     * `system/status` echoing the new `permissionMode`. A mode the model cannot honour is refused with
     * an **error** response in the CLI's own words (`auto` on Haiku), which is the one case a caller
     * must report rather than assume.
     */
    fun permissionModeRequest(requestId: String, mode: String): String =
        """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"set_permission_mode","mode":"${UserMessageJson.escape(mode)}"}}"""

    /**
     * `rewind_files` — restores the files the agent wrote since [userMessageId], which is the `uuid` of
     * a user message replayed by `--replay-user-messages`.
     *
     * Verified end to end against 2.1.235: with `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` set on
     * the process, an edited file was restored byte-for-byte and the reply was
     * `{"canRewind":true,"skippedLinks":0}`. Without the variable, or for a message with no checkpoint,
     * the reply is a *success* carrying `{"canRewind":false,"error":"…"}` — so the caller must read the
     * payload rather than trust the subtype.
     */
    fun rewindRequest(requestId: String, userMessageId: String): String =
        """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"rewind_files","user_message_id":"${UserMessageJson.escape(userMessageId)}"}}"""

    fun modelRequest(requestId: String, model: String): String =
        """{"type":"control_request","request_id":"${UserMessageJson.escape(requestId)}",""" +
            """"request":{"subtype":"set_model","model":"${UserMessageJson.escape(model)}"}}"""

    /** A parsed `control_response`, or null when the line is not one. */
    data class Reply(
        val requestId: String,
        val kind: Kind,
        val ok: Boolean,
        val error: String?,
        /** The reply's own `response` payload, when it had one. */
        val payload: JsonObject? = null,
    )

    fun parseReply(o: JsonObject): Reply? {
        if (o.get("type")?.asStringOrNull() != "control_response") return null
        val r = o.get("response")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = r.get("request_id")?.asStringOrNull() ?: return null
        val kind = kindOf(id) ?: return null
        val ok = r.get("subtype")?.asStringOrNull() == "success"
        return Reply(
            id, kind, ok,
            r.get("error")?.asStringOrNull()?.takeIf { it.isNotBlank() },
            r.get("response")?.takeIf { it.isJsonObject }?.asJsonObject,
        )
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
}
