package io.mp.sightline.ide

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The **two-face bridge**, end to end against the real [IdeServer] over a real WebSocket.
 *
 * This is the change with the largest blast radius in 0.8.0 and the only one that touches the auth
 * path, so it gets a test that starts the actual server rather than exercising [McpFace] alone. What it
 * proves is the part a unit test cannot: that one socket, one port and one token really do serve two
 * different tool lists, chosen by the path the client connected on, and that a bad token is still
 * refused on both.
 *
 * The CLI's own ws client is known to preserve the path — probed against 2.1.235, which produced
 * `GET / HTTP/1.1` and `GET /sightline HTTP/1.1` against a listener that logged the request line
 * (docs/PROTOCOL.md §6). This test covers our half of that contract.
 */
class IdeServerFacesTest : BasePlatformTestCase() {

    private lateinit var server: IdeServer

    override fun setUp() {
        super.setUp()
        server = project.getService(IdeServer::class.java)
        assertTrue("ide server did not start", server.ensureStarted())
        assertTrue("ide server reported no port", server.port > 0)
    }

    /** One request/response round trip, returning the raw reply or null on timeout. */
    private fun call(path: String, token: String, request: String): String? {
        val reply = arrayOfNulls<String>(1)
        val opened = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val client = object : WebSocketClient(URI("ws://127.0.0.1:${server.port}$path")) {
            override fun onOpen(handshake: ServerHandshake) = opened.countDown()
            override fun onMessage(message: String) {
                reply[0] = message
                answered.countDown()
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                opened.countDown(); answered.countDown()
            }
            override fun onError(ex: Exception?) {
                opened.countDown(); answered.countDown()
            }
        }
        client.addHeader("x-claude-code-ide-authorization", token)
        try {
            client.connectBlocking(5, TimeUnit.SECONDS)
            opened.await(5, TimeUnit.SECONDS)
            // A refused handshake closes the socket, so the send itself throws. That *is* the refusal,
            // and it is one of the two shapes it can take — the other is a connection that stays open
            // (close() only asks the peer to leave) and simply never answers.
            runCatching { client.send(request) }
            answered.await(5, TimeUnit.SECONDS)
        } finally {
            client.closeBlocking()
        }
        return reply[0]
    }

    private fun toolNames(path: String): List<String> {
        val raw = call(path, server.authToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertNotNull("no tools/list reply on $path", raw)
        val tools = JsonParser.parseString(raw!!).asJsonObject
            .getAsJsonObject("result").getAsJsonArray("tools")
        return tools.map { it.asJsonObject.get("name").asString }
    }

    fun `test the ide face serves the editor RPC and no sightline tools`() {
        val names = toolNames("/")
        assertTrue("expected the editor RPC on the ide face, got $names", names.contains("openDiff"))
        assertTrue(names.contains("getDiagnostics"))
        assertTrue(names.contains("getCurrentSelection"))
        // The CLI filters this face down to getDiagnostics before the model sees it, so putting a
        // model-facing tool here makes it unreachable — the bug this whole split exists to fix.
        assertTrue(
            "no android_* tool may be served on the ide face, got $names",
            names.none { it.startsWith("android_") },
        )
    }

    fun `test the sightline face serves no editor RPC`() {
        val names = toolNames(McpFace.SIGHTLINE_PATH)
        // The editor RPC is the CLI's to call over the `ide` connection. Duplicating it here would put
        // openDiff and saveDocument in front of the model, which is exactly what the CLI's own filter
        // exists to prevent.
        assertTrue("the sightline face must not serve editor RPC, got $names", names.none { it == "openDiff" })
        assertTrue(names.none { it == "saveDocument" })
        assertTrue(names.none { it == "getCurrentSelection" })
    }

    fun `test the two faces serve different lists on one port and one token`() {
        // The whole point: same socket, same token, different answer. Compared by *value* — two
        // separately-built lists are never the same reference, so an identity check would pass even if
        // the split had been undone entirely.
        assertNotEquals(toolNames("/"), toolNames(McpFace.SIGHTLINE_PATH))
        assertTrue(toolNames("/").isNotEmpty())
    }

    fun `test an unrecognised path falls back to the ide face rather than exposing the other one`() {
        // Failing the other way would put the model-facing tools on the connection the CLI drives.
        assertEquals(toolNames("/"), toolNames("/not-a-face"))
    }

    fun `test a bad token is refused on both faces`() {
        // `close()` only *asks* the peer to leave, so the message path checks the attachment too. Both
        // faces must honour that — a new entry point is a new place to forget it.
        for (path in listOf("/", McpFace.SIGHTLINE_PATH)) {
            val reply = call(path, "0".repeat(32), """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            // Refused either by the socket being gone or by the message path finding no face on the
            // attachment. What must never happen is a tool list coming back.
            assertNull("an unauthenticated client got a reply on $path: $reply", reply)
        }
    }
}
