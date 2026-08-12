package io.mp.sightline.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigReaderTest {

    private val userJson = """
        {
          "numStartups": 41,
          "mcpServers": { "studio": { "type": "sse", "url": "http://127.0.0.1:64342/sse" } },
          "projects": {
            "/work/app": {
              "mcpServers": {
                "playwright": { "type": "stdio", "command": "npx", "args": ["@playwright/mcp"], "env": { "TOKEN": "s3cret" } }
              },
              "disabledMcpjsonServers": ["rejected"]
            },
            "/work/other": { "mcpServers": { "elsewhere": { "type": "stdio", "command": "x" } } }
          }
        }
    """.trimIndent()

    @Test fun readsUserScopeAndTheMatchingProjectsLocalScope() {
        val got = McpConfigReader.readUserConfig(userJson, listOf("/work/app"))!!
        assertEquals(setOf("studio", "playwright"), got.map { it.name }.toSet())
        assertEquals(McpScope.USER, got.single { it.name == "studio" }.scope)
        assertEquals(McpScope.LOCAL, got.single { it.name == "playwright" }.scope)
    }

    /** Another project's servers are not this project's; keying by cwd is the whole point of the map. */
    @Test fun ignoresOtherProjectsEntries() {
        val names = McpConfigReader.readUserConfig(userJson, listOf("/work/app"))!!.map { it.name }
        assertTrue("a different project's server leaked in: $names", "elsewhere" !in names)
    }

    /**
     * The CLI keys by its own cwd string, and a path can reach us in more than one spelling (macOS
     * `/private` prefixes, symlinks). Trying only one would silently report "no local servers".
     */
    @Test fun triesEveryCandidateProjectKeyInOrder() {
        val got = McpConfigReader.readUserConfig(userJson, listOf("/private/work/app", "/work/app"))!!
        assertTrue("playwright" in got.map { it.name })
    }

    @Test fun configIsKeptVerbatimSoItCanBeSentUnchanged() {
        val pw = McpConfigReader.readUserConfig(userJson, listOf("/work/app"))!!.single { it.name == "playwright" }
        assertTrue("the config must survive intact to be usable", pw.config.json.contains("@playwright/mcp"))
    }

    /** The one thing a config object must never do is print itself — `env` can hold a credential. */
    @Test fun aServerNeverPrintsItsOwnConfig() {
        val pw = McpConfigReader.readUserConfig(userJson, listOf("/work/app"))!!.single { it.name == "playwright" }
        assertTrue("config leaked via toString: ${pw.config}", "s3cret" !in pw.config.toString())
        assertTrue("config leaked via the server's toString: $pw", "s3cret" !in pw.toString())
    }

    @Test fun projectScopeHonoursTheDisabledList() {
        val disabled = McpConfigReader.disabledProjectServers(userJson, listOf("/work/app"))
        assertEquals(setOf("rejected"), disabled)
        val got = McpConfigReader.readProjectConfig(
            """{"mcpServers":{"shared":{"type":"stdio","command":"a"},"rejected":{"type":"stdio","command":"b"}}}""",
            disabled,
        )!!
        assertEquals(listOf("shared"), got.map { it.name })
        assertEquals(McpScope.PROJECT, got.single().scope)
    }

    /**
     * `~/.claude.json` is rewritten constantly by another process, so a torn read must be
     * distinguishable from "you have declared nothing" — one is unknown, the other is a fact.
     */
    @Test fun unparseableInputIsUnknownNotEmpty() {
        assertNull(McpConfigReader.readUserConfig("{ this is not json", listOf("/work/app")))
        assertNull(McpConfigReader.readUserConfig(null, listOf("/work/app")))
        assertNull(McpConfigReader.readProjectConfig("{{{"))
        assertNotNull("a config with no servers is a real, empty answer", McpConfigReader.readUserConfig("{}", listOf("/x")))
        assertEquals(emptyList<DeclaredServer>(), McpConfigReader.readUserConfig("{}", listOf("/x")))
    }

    /** An absent `.mcp.json` is "no project servers", which is knowable — unlike a corrupt one. */
    @Test fun missingProjectFileIsEmptyNotUnknown() {
        assertEquals(emptyList<DeclaredServer>(), McpConfigReader.readProjectConfig(null))
    }

    @Test fun skipsEntriesThatArentServerObjects() {
        val got = McpConfigReader.readUserConfig(
            """{"mcpServers":{"good":{"type":"stdio","command":"a"},"bad":"nonsense","":{"type":"stdio"}}}""",
            listOf("/x"),
        )!!
        assertEquals(listOf("good"), got.map { it.name })
    }

    @Test fun mergePrefersTheMoreSpecificScope() {
        val user = listOf(DeclaredServer("dup", McpScope.USER, ServerConfigJson("""{"u":1}""")))
        val project = listOf(DeclaredServer("dup", McpScope.PROJECT, ServerConfigJson("""{"p":1}""")))
        val local = listOf(DeclaredServer("dup", McpScope.LOCAL, ServerConfigJson("""{"l":1}""")))
        val merged = McpConfigReader.merge(user, project, local)
        assertEquals(1, merged.size)
        assertEquals(McpScope.LOCAL, merged.single().scope)
    }

    @Test fun mergeToleratesUnknownGroups() {
        assertEquals(emptyList<DeclaredServer>(), McpConfigReader.merge(null, null))
    }
}
