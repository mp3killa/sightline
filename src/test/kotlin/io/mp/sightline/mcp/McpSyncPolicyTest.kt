package io.mp.sightline.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSyncPolicyTest {

    private fun server(name: String, scope: McpScope = McpScope.LOCAL, cfg: String = """{"command":"$name"}""") =
        DeclaredServer(name, scope, ServerConfigJson(cfg))

    private fun loaded(vararg names: String) = names.map { LoadedServer(it, "connected") }

    @Test fun sendsAServerTheSessionIsMissing() {
        val plan = McpSyncPolicy.plan(
            declared = listOf(server("playwright")),
            loaded = loaded("ide", "studio"),
            managed = emptyList(),
            autoSync = true,
        )
        assertEquals(listOf("playwright"), plan.send.map { it.name })
        assertEquals(listOf("playwright"), plan.newlyManaged.map { it.name })
        assertTrue(plan.needsRequest)
    }

    /**
     * The trap this policy exists for. `mcp_set_servers` is authoritative over the set we send, and a
     * server we added reports as loaded from then on — so "send what is missing" would send an empty
     * set on the very next pass and the CLI would tear down the server we had just started.
     */
    @Test fun keepsResendingWhatItAlreadyManagesEvenThoughItNowReadsAsLoaded() {
        val pw = server("playwright")
        val plan = McpSyncPolicy.plan(
            declared = listOf(pw),
            loaded = loaded("ide", "playwright"),
            managed = listOf(pw),
            autoSync = true,
        )
        assertEquals("the managed server must stay in the set", listOf("playwright"), plan.send.map { it.name })
        assertFalse("an unchanged set is not worth a request", plan.needsRequest)
    }

    /** Servers the CLI loaded by itself are the CLI's. Claiming them means a later sync could remove them. */
    @Test fun neverClaimsServersTheCliLoadedItself() {
        val plan = McpSyncPolicy.plan(
            declared = listOf(server("studio", McpScope.USER)),
            loaded = loaded("studio"),
            managed = emptyList(),
            autoSync = true,
        )
        assertTrue(plan.send.isEmpty())
        assertFalse(plan.needsRequest)
    }

    @Test fun dropsAServerWhoseDeclarationIsGone() {
        val plan = McpSyncPolicy.plan(
            declared = emptyList(),
            loaded = loaded("playwright"),
            managed = listOf(server("playwright")),
            autoSync = true,
        )
        assertTrue(plan.send.isEmpty())
        assertEquals(listOf("playwright"), plan.dropped)
        assertTrue(plan.needsRequest)
    }

    /** An edited command or a corrected token is exactly when a re-send matters, and names alone miss it. */
    @Test fun aChangedConfigCountsAsAChange() {
        val before = server("playwright", cfg = """{"command":"npx"}""")
        val after = server("playwright", cfg = """{"command":"/usr/local/bin/npx"}""")
        val plan = McpSyncPolicy.plan(listOf(after), loaded("playwright"), listOf(before), autoSync = true)
        assertTrue(plan.needsRequest)
        assertEquals(after.config.json, plan.send.single().config.json)
    }

    /**
     * A `.mcp.json` server can arrive from a `git pull` nobody read. It is reported, never started —
     * the CLI will load it at the next launch anyway, so waiting costs a moment and buys consent.
     */
    @Test fun projectScopeServersAreReportedNotStarted() {
        val plan = McpSyncPolicy.plan(
            declared = listOf(server("repoServer", McpScope.PROJECT)),
            loaded = loaded("ide"),
            managed = emptyList(),
            autoSync = true,
        )
        assertTrue("a checked-in server must not be launched for the user", plan.send.isEmpty())
        assertEquals(listOf("repoServer"), plan.report.map { it.name })
        assertFalse(plan.needsRequest)
    }

    @Test fun autoSyncOffObservesAndReportsButNeverActs() {
        val plan = McpSyncPolicy.plan(
            declared = listOf(server("playwright")),
            loaded = loaded("ide"),
            managed = emptyList(),
            autoSync = false,
        )
        assertTrue(plan.send.isEmpty())
        assertEquals(listOf("playwright"), plan.report.map { it.name })
        assertFalse(plan.needsRequest)
    }

    /** With auto-sync off, an already-managed set is left exactly as it is rather than torn down. */
    @Test fun autoSyncOffDoesNotRetractWhatIsAlreadyRunning() {
        val pw = server("playwright")
        val plan = McpSyncPolicy.plan(listOf(pw), loaded("playwright"), listOf(pw), autoSync = false)
        assertEquals(listOf("playwright"), plan.send.map { it.name })
        assertFalse(plan.needsRequest)
    }

    @Test fun aServerIsNeverSentTwice() {
        val pw = server("playwright")
        val plan = McpSyncPolicy.plan(listOf(pw), loaded(), listOf(pw), autoSync = true)
        assertEquals(1, plan.send.size)
    }
}
