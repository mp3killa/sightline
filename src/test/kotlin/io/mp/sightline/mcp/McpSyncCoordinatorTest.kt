package io.mp.sightline.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSyncCoordinatorTest {

    private val sent = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private var canSend = true
    private var n = 0
    private val c = McpSyncCoordinator(
        send = { line -> if (canSend) { sent += line; true } else false },
        notice = { notices += it },
        newRequestId = { "req-${++n}" },
    )

    private fun playwright(cfg: String = """{"type":"stdio","command":"npx"}""") =
        DeclaredServer("playwright", McpScope.LOCAL, ServerConfigJson(cfg))

    private fun lastId() = JsonParser.parseString(sent.last()).asJsonObject.get("request_id").asString
    private fun subtypeOf(line: String) =
        JsonParser.parseString(line).asJsonObject.getAsJsonObject("request").get("subtype").asString

    private fun feed(json: String) = c.onControlResponse(JsonParser.parseString(json).asJsonObject)

    private fun statusReply(id: String, vararg servers: Pair<String, Int>) = feed(
        """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":{"mcpServers":[""" +
            servers.joinToString(",") { (name, tools) ->
                """{"name":"$name","status":"connected","tools":[${(1..tools).joinToString(",") { """{"name":"t$it"}""" }}]}"""
            } + "]}}}"
    )

    @Test fun theFullExchangeAddsTheServerAndReportsIt() {
        assertTrue(c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertEquals("it must ask what the session has before deciding", "mcp_status", subtypeOf(sent.single()))

        statusReply(lastId(), "ide" to 4)
        assertEquals("mcp_set_servers", subtypeOf(sent.last()))
        assertTrue("playwright" in sent.last())

        val syncId = lastId()
        feed("""{"type":"control_response","response":{"subtype":"success","request_id":"$syncId","response":{"added":["playwright"],"removed":[],"errors":{}}}}""")
        assertEquals("it must re-ask rather than guess the tool count", "mcp_status", subtypeOf(sent.last()))

        statusReply(lastId(), "ide" to 4, "playwright" to 21)
        assertEquals(listOf("playwright"), c.managed.map { it.name })
        assertTrue(notices.single(), "playwright (21 tools)" in notices.single())
        assertFalse(c.busy)
    }

    /** Adding servers mid-turn is pointless and removing one could pull it from under a live tool call. */
    @Test fun holdsBackWhileATurnIsRunning() {
        assertFalse(c.offer(listOf(playwright()), autoSync = true, idle = false))
        assertTrue(sent.isEmpty())
    }

    @Test fun aServerTheSessionAlreadyHasCostsNothingAfterTheFirstLook() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "playwright" to 21)
        assertEquals("nothing to do — the CLI loaded it itself", 1, sent.size)
        assertFalse("and it must not ask again and again", c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertEquals(1, sent.size)
    }

    /** The managed set is only committed once the CLI confirms it; a failed request must not claim it. */
    @Test fun doesNotClaimServersWhenTheRequestFails() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        val syncId = lastId()
        feed("""{"type":"control_response","response":{"subtype":"error","request_id":"$syncId","error":"boom"}}""")
        assertTrue(c.managed.isEmpty())
        assertTrue(notices.single(), "boom" in notices.single())
        assertFalse(c.busy)
    }

    @Test fun anOldCliIsExplainedRatherThanReportedAsAFailure() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        val syncId = lastId()
        feed("""{"type":"control_response","response":{"subtype":"error","request_id":"$syncId","error":"Unsupported control request subtype: mcp_set_servers"}}""")
        assertTrue(notices.single(), "cannot be added to a conversation already in progress" in notices.single())
        assertTrue(notices.single(), "playwright" in notices.single())
    }

    /**
     * `mcp_set_servers` does not reply until every server has connected or timed out, so a reply that
     * never comes must free the exchange and be reported as not-knowing.
     */
    @Test fun aReplyThatNeverComesIsReportedAsUnknownAndUnblocksTheNextSync() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        assertTrue(c.busy)
        c.timedOut()
        assertFalse(c.busy)
        assertTrue(notices.single(), "cannot say" in notices.single())
        assertTrue("a wedged request must not block sync for the session", c.offer(listOf(playwright()), autoSync = true, idle = true))
    }

    @Test fun onlyOneExchangeRunsAtATime() {
        assertTrue(c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertFalse(c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertEquals(1, sent.size)
    }

    @Test fun aProjectServerIsExplainedOnceAndNeverStarted() {
        val repo = DeclaredServer("repoServer", McpScope.PROJECT, ServerConfigJson("{}"))
        c.offer(listOf(repo), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        assertEquals("a checked-in server is never launched for the user", 1, sent.size)
        assertTrue(notices.single(), ".mcp.json" in notices.single())

        c.offer(listOf(repo), autoSync = true, idle = true)
        assertEquals("and it is not explained twice", 1, notices.size)
    }

    /** A fresh process has read every config file itself, so nothing carries over. */
    @Test fun aRestartResetsWhatIsManaged() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        feed("""{"type":"control_response","response":{"subtype":"success","request_id":"${lastId()}","response":{"added":["playwright"],"removed":[],"errors":{}}}}""")
        statusReply(lastId(), "playwright" to 2)
        assertEquals(1, c.managed.size)

        c.onProcessStarted()
        assertTrue(c.managed.isEmpty())
        assertFalse(c.busy)
    }

    @Test fun aDeadProcessDoesNotLeaveTheCoordinatorBusy() {
        canSend = false
        assertFalse(c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertFalse(c.busy)
    }

    @Test fun ignoresRepliesThatArentItsOwn() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        assertFalse(feed("""{"type":"control_response","response":{"subtype":"success","request_id":"someone-else","response":{}}}"""))
        assertTrue(c.busy)
    }

    /** An old CLI will not start supporting this mid-process; retrying would only repeat the notice. */
    @Test fun stopsTryingOnceTheCliSaysItCannot() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        feed("""{"type":"control_response","response":{"subtype":"error","request_id":"${lastId()}","error":"Unsupported control request subtype: mcp_set_servers"}}""")
        assertEquals(1, notices.size)

        assertFalse(c.offer(listOf(playwright()), autoSync = true, idle = true))
        assertEquals("the advice must not repeat every poll", 1, notices.size)
        c.onProcessStarted()
        assertTrue("a new process may be a newer CLI", c.offer(listOf(playwright()), autoSync = true, idle = true))
    }

    /**
     * A failed request leaves nothing managed, which makes the same servers look untried on the next
     * poll — without a guard the panel would retry, and repeat the error, every couple of seconds.
     */
    @Test fun doesNotRetryTheSameSetAfterAHardFailure() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        feed("""{"type":"control_response","response":{"subtype":"error","request_id":"${lastId()}","error":"boom"}}""")
        assertEquals(1, notices.size)

        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        assertEquals("the same failing set must not be sent again", 1, notices.size)

        // …but an edited config is a new set, and is tried at once.
        c.offer(listOf(playwright("""{"type":"stdio","command":"/usr/local/bin/npx"}""")), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        assertEquals("mcp_set_servers", subtypeOf(sent.last()))
    }

    @Test fun aFailedServerIsReportedWithTheClisReason() {
        c.offer(listOf(playwright()), autoSync = true, idle = true)
        statusReply(lastId(), "ide" to 1)
        feed("""{"type":"control_response","response":{"subtype":"success","request_id":"${lastId()}","response":{"added":["playwright"],"removed":[],"errors":{"playwright":"Executable not found in ${'$'}PATH: \"npx\""}}}}""")
        statusReply(lastId(), "ide" to 1)
        assertTrue(notices.single(), "Executable not found" in notices.single())
    }
}
