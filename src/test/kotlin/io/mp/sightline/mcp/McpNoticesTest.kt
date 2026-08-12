package io.mp.sightline.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNoticesTest {

    @Test fun aNoopSyncSaysNothing() {
        assertNull("background bookkeeping is not news", McpNotices.syncNotice(McpSyncResult()))
    }

    @Test fun namesWhatWasAddedAndItsToolCount() {
        val n = McpNotices.syncNotice(McpSyncResult(added = listOf("playwright")), mapOf("playwright" to 21))!!
        assertTrue(n, "playwright" in n)
        assertTrue(n, "21 tools" in n)
        assertTrue("the point is that nothing was lost", "nothing lost" in n)
    }

    @Test fun singularToolReadsAsOne() {
        val n = McpNotices.syncNotice(McpSyncResult(added = listOf("probe")), mapOf("probe" to 1))!!
        assertTrue(n, "1 tool)" in n)
    }

    /** A count we don't have is omitted, never printed as a confident zero. */
    @Test fun anUnknownToolCountIsLeftOut() {
        val n = McpNotices.syncNotice(McpSyncResult(added = listOf("probe")), mapOf("probe" to null))!!
        assertTrue(n, "probe" in n)
        assertFalse(n, "0 tool" in n)
    }

    /** A failure keeps the CLI's own sentence — it ends the search instead of starting one. */
    @Test fun aFailureIsNamedWithTheClisReason() {
        val n = McpNotices.syncNotice(McpSyncResult(
            added = listOf("broken"),
            errors = mapOf("broken" to "Executable not found in \$PATH: \"npx\""),
        ))!!
        assertTrue(n, "Executable not found" in n)
        assertFalse("a broken server was not added to anything", "no restart" in n)
    }

    @Test fun aRemovalIsReported() {
        val n = McpNotices.syncNotice(McpSyncResult(removed = listOf("playwright")))!!
        assertTrue(n, "Removed" in n)
        assertTrue(n, "playwright" in n)
    }

    @Test fun projectScopeExplainsWhyItWasNotStarted() {
        val n = McpNotices.pendingNotice(
            listOf(DeclaredServer("repoServer", McpScope.PROJECT, ServerConfigJson("{}"))),
            autoSync = true,
        )!!
        assertTrue(n, ".mcp.json" in n)
        assertTrue("the way out has to be in the sentence", "new conversation" in n)
    }

    @Test fun autoSyncOffSaysSoRatherThanBlamingTheServer() {
        val n = McpNotices.pendingNotice(
            listOf(DeclaredServer("playwright", McpScope.LOCAL, ServerConfigJson("{}"))),
            autoSync = false,
        )!!
        assertTrue(n, "off" in n)
        assertFalse("nothing failed here", "could not" in n)
    }

    @Test fun nothingPendingSaysNothing() {
        assertNull(McpNotices.pendingNotice(emptyList(), autoSync = true))
    }

    @Test fun anOldCliIsToldApartFromABreakage() {
        val n = McpNotices.unsupportedNotice(listOf("playwright"))
        assertTrue(n, "cannot be added to a conversation already in progress" in n)
        assertTrue(n, "update the CLI" in n)
    }

    @Test fun anUnconfirmedSyncClaimsNothing() {
        val n = McpNotices.inconclusiveNotice()
        assertTrue(n, "cannot say" in n)
    }

    /**
     * The guardrail. A server config can carry a credential in `env`; the notices are the one part of
     * this feature that reaches the screen, the clipboard and the transcript, so none of them may ever
     * carry anything but names, counts and the CLI's own words.
     */
    @Test fun noNoticeCanEverCarryAConfigValue() {
        val secret = "sk-ant-not-a-real-token"
        val server = DeclaredServer("leaky", McpScope.PROJECT, ServerConfigJson("""{"env":{"TOKEN":"$secret"}}"""))
        val all = listOfNotNull(
            McpNotices.syncNotice(McpSyncResult(added = listOf("leaky")), mapOf("leaky" to 3)),
            McpNotices.pendingNotice(listOf(server), autoSync = true),
            McpNotices.pendingNotice(listOf(server), autoSync = false),
            McpNotices.unsupportedNotice(listOf("leaky")),
            McpNotices.inconclusiveNotice(),
        )
        for (n in all) {
            assertFalse("a notice leaked a config value: $n", secret in n)
            assertFalse("a notice leaked a config key: $n", "TOKEN" in n)
        }
    }
}
