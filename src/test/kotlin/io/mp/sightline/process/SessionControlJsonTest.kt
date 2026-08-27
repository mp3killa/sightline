package io.mp.sightline.process

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response fixtures here are **captured verbatim** from CLI 2.1.235 during the probes recorded in
 * docs/PROTOCOL.md §6, not hand-written to match the parser. A parser tested against invented input
 * only proves it is self-consistent.
 */
class SessionControlJsonTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    @Test fun buildsTheVerifiedInterruptShape() {
        assertEquals(
            """{"type":"control_request","request_id":"sl-interrupt-1","request":{"subtype":"interrupt"}}""",
            SessionControlJson.interruptRequest("sl-interrupt-1"),
        )
    }

    @Test fun buildsTheVerifiedPermissionModeShape() {
        assertEquals(
            """{"type":"control_request","request_id":"sl-permmode-1",""" +
                """"request":{"subtype":"set_permission_mode","mode":"plan"}}""",
            SessionControlJson.permissionModeRequest("sl-permmode-1", "plan"),
        )
    }

    @Test fun buildsTheVerifiedModelShape() {
        assertEquals(
            """{"type":"control_request","request_id":"model-1","request":{"subtype":"set_model","model":"haiku"}}""",
            SessionControlJson.modelRequest("model-1", "haiku"),
        )
    }

    @Test fun escapesRatherThanSplicingUserText() {
        // A pinned custom model id is free text the user typed; it must never be able to close the JSON.
        val line = SessionControlJson.modelRequest("model-1", """a"b\c""")
        assertTrue(line, line.contains("""a\"b\\c"""))
        // Parses back to exactly what went in.
        val parsed = JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("request").get("model").asString
        assertEquals("""a"b\c""", parsed)
    }

    // ---- request-id correlation ----

    @Test fun recognisesItsOwnRequestIdsAndNothingElse() {
        val id = SessionControlJson.requestId(SessionControlJson.Kind.INTERRUPT, "abc")
        assertEquals(SessionControlJson.Kind.INTERRUPT, SessionControlJson.kindOf(id))
        assertEquals(
            SessionControlJson.Kind.PERMISSION_MODE,
            SessionControlJson.kindOf(SessionControlJson.requestId(SessionControlJson.Kind.PERMISSION_MODE, "abc")),
        )
        // The launch handshake is ours too — its reply carries the slash-command catalogue.
        assertEquals(SessionControlJson.Kind.INITIALIZE, SessionControlJson.kindOf("init-9f2c"))
        // Live MCP sync shares the channel and is not ours to consume.
        assertNull(SessionControlJson.kindOf("mcp-status-1"))
        assertNull(SessionControlJson.kindOf(null))
    }

    @Test fun keepsTheHistoricalModelPrefix() {
        // A CLI already running when the plugin updated still replies to `model-…`; dropping the prefix
        // would silently stop reporting a refused model switch on exactly that session.
        assertEquals(SessionControlJson.Kind.MODEL, SessionControlJson.kindOf("model-9f2c"))
    }

    // ---- replies, verbatim from 2.1.235 ----

    @Test fun parsesTheVerifiedInterruptSuccess() {
        val r = SessionControlJson.parseReply(
            obj("""{"type":"control_response","response":{"subtype":"success","request_id":"sl-interrupt-1","response":{"still_queued":[]}}}"""),
        )!!
        assertEquals(SessionControlJson.Kind.INTERRUPT, r.kind)
        assertTrue(r.ok)
        assertNull(r.error)
    }

    @Test fun parsesTheVerifiedPermissionModeSuccess() {
        val r = SessionControlJson.parseReply(
            obj("""{"type":"control_response","response":{"subtype":"success","request_id":"sl-permmode-1","response":{"mode":"plan"}}}"""),
        )!!
        assertEquals(SessionControlJson.Kind.PERMISSION_MODE, r.kind)
        assertTrue(r.ok)
    }

    @Test fun parsesTheVerifiedUnsupportedSubtypeError() {
        // The exact shape an older CLI returns — the fallback trigger for Stop.
        val r = SessionControlJson.parseReply(
            obj("""{"type":"control_response","response":{"subtype":"error","request_id":"sl-interrupt-1","error":"Unsupported control request subtype: interrupt"}}"""),
        )!!
        assertEquals(SessionControlJson.Kind.INTERRUPT, r.kind)
        assertFalse(r.ok)
        assertEquals("Unsupported control request subtype: interrupt", r.error)
    }

    @Test fun ignoresLinesThatAreNotOurs() {
        // Someone else's control_response, a control *request*, and a plain event.
        assertNull(SessionControlJson.parseReply(obj("""{"type":"control_response","response":{"subtype":"success","request_id":"mcp-1"}}""")))
        assertNull(SessionControlJson.parseReply(obj("""{"type":"control_request","request_id":"sl-interrupt-1","request":{"subtype":"interrupt"}}""")))
        assertNull(SessionControlJson.parseReply(obj("""{"type":"result","subtype":"error_during_execution"}""")))
        assertNull(SessionControlJson.parseReply(obj("""{"type":"control_response"}""")))
    }
}
