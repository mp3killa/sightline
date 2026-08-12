package io.mp.sightline.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response fixtures here are **captured verbatim** from CLI 2.1.228 during the probes recorded in
 * docs/PROTOCOL.md §5, not hand-written to match the parser. A parser tested against invented input
 * only proves it is self-consistent.
 */
class McpControlJsonTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    @Test fun buildsTheVerifiedSetServersShape() {
        val line = McpControlJson.setServersRequest(
            "mcp-1",
            listOf(DeclaredServer("probe", McpScope.LOCAL, ServerConfigJson("""{"type":"stdio","command":"node"}"""))),
        )
        assertEquals(
            """{"type":"control_request","request_id":"mcp-1","request":{"subtype":"mcp_set_servers",""" +
                """"servers":{"probe":{"type":"stdio","command":"node"}}}}""",
            line,
        )
        // and it must be valid JSON, not just the right-looking string
        assertEquals("mcp_set_servers", obj(line).getAsJsonObject("request").get("subtype").asString)
    }

    /** Giving up everything is a legal, meaningful request — not a reason to skip sending. */
    @Test fun anEmptySetIsStillAWellFormedRequest() {
        val line = McpControlJson.setServersRequest("r", emptyList())
        assertEquals(0, obj(line).getAsJsonObject("request").getAsJsonObject("servers").size())
    }

    @Test fun escapesServerNames() {
        val line = McpControlJson.setServersRequest("r", listOf(
            DeclaredServer("""odd"name""", McpScope.LOCAL, ServerConfigJson("{}")),
        ))
        assertTrue("""odd"name""" in obj(line).getAsJsonObject("request").getAsJsonObject("servers").keySet())
    }

    @Test fun readsTheAddedRemovedErrorsPayload() {
        val r = McpControlJson.parseSyncResult(obj("""
            {"type":"control_response","response":{"subtype":"success","request_id":"m1","response":{
              "added":["probe","broken"],"removed":[],
              "errors":{"broken":"Executable not found in ${'$'}PATH: \"definitely-not-a-real-binary-xyz\""}}}}
        """.trimIndent()))!!
        assertEquals(listOf("probe", "broken"), r.added)
        assertEquals(listOf("probe"), r.addedCleanly)
        assertTrue(r.errors.getValue("broken").startsWith("Executable not found"))
        assertFalse(r.isNoop)
    }

    /** "added" means registered, not working — a server can be in both lists, and the UI must not lie. */
    @Test fun aRegisteredButBrokenServerIsNotCountedAsAWin() {
        val r = McpSyncResult(added = listOf("hang"), errors = mapOf("hang" to "connection timed out after 30000ms"))
        assertTrue(r.addedCleanly.isEmpty())
    }

    @Test fun recognisesTheNoopResponse() {
        val r = McpControlJson.parseSyncResult(obj(
            """{"type":"control_response","response":{"subtype":"success","request_id":"m2","response":{"added":[],"removed":[],"errors":{}}}}"""
        ))!!
        assertTrue(r.isNoop)
    }

    @Test fun readsMcpStatusIncludingToolCounts() {
        val servers = McpControlJson.parseStatus(obj("""
            {"type":"control_response","response":{"subtype":"success","request_id":"s1","response":{"mcpServers":[
              {"name":"probe","status":"connected","scope":"dynamic","tools":[{"name":"probe_ping"}]},
              {"name":"broken","status":"failed","scope":"dynamic","tools":[]}]}}}
        """.trimIndent()))!!
        assertEquals(1, servers.single { it.name == "probe" }.toolCount)
        assertTrue(servers.single { it.name == "probe" }.connected)
        assertFalse(servers.single { it.name == "broken" }.connected)
        assertEquals("dynamic", servers.first().scope)
    }

    @Test fun readsInitAnnouncedServers() {
        val servers = McpControlJson.parseInitServers(obj(
            """{"type":"system","subtype":"init","mcp_servers":[{"name":"ide","status":"connected"}]}"""
        ))!!
        assertEquals(listOf("ide"), servers.map { it.name })
        assertNull("init carries no tool list, so none is claimed", servers.single().toolCount)
    }

    /** The precise fallback trigger for a CLI that predates the feature. */
    @Test fun detectsAnUnsupportedSubtype() {
        val err = McpControlJson.errorOf(obj(
            """{"type":"control_response","response":{"subtype":"error","request_id":"u1","error":"Unsupported control request subtype: mcp_set_servers"}}"""
        ))!!
        assertTrue(McpControlJson.isUnsupported(err))
    }

    /** A malformed payload is a bug on our side, not an old CLI — it must not trigger the same advice. */
    @Test fun aRejectedPayloadIsNotMistakenForAnOldCli() {
        val err = McpControlJson.errorOf(obj(
            """{"type":"control_response","response":{"subtype":"error","request_id":"u2","error":"mcp_set_servers: servers must be an object of config objects"}}"""
        ))!!
        assertFalse(McpControlJson.isUnsupported(err))
    }

    @Test fun successCarriesNoError() {
        assertNull(McpControlJson.errorOf(obj(
            """{"type":"control_response","response":{"subtype":"success","request_id":"x","response":{}}}"""
        )))
    }

    @Test fun matchesRepliesByRequestId() {
        assertEquals("m1", McpControlJson.responseId(obj(
            """{"type":"control_response","response":{"subtype":"success","request_id":"m1","response":{}}}"""
        )))
        assertNull(McpControlJson.responseId(obj("""{"type":"system","subtype":"init"}""")))
    }

    /** Reading the wrong reply must not look like "nothing changed" — those are different answers. */
    @Test fun theWrongReplyParsesToNullNotAnEmptyResult() {
        val other = obj("""{"type":"control_response","response":{"subtype":"success","request_id":"s","response":{"mcpServers":[]}}}""")
        assertNull(McpControlJson.parseSyncResult(other))
        assertNull(McpControlJson.parseStatus(obj(
            """{"type":"control_response","response":{"subtype":"success","request_id":"m","response":{"added":[]}}}"""
        )))
    }
}
