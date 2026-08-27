package io.mp.sightline.ide

/**
 * Which set of tools a connection to the bridge is asking for.
 *
 * The bridge serves two MCP servers on one socket, and it has to, because of a hard rule in the CLI:
 *
 * > `["mcp__ide__executeCode","mcp__ide__getDiagnostics"]`
 *
 * That allowlist is compiled into the CLI (found in 2.1.235; verified by probe). **Every other tool on
 * a server named `ide` is filtered out before the tool list reaches the model.** For the IDE RPC —
 * `openDiff`, `getCurrentSelection`, `close_tab`, `saveDocument` — that is correct and desirable: the
 * CLI calls those itself over `callIdeRpc`, and the model has no business seeing them.
 *
 * For Sightline's own `android_*` tools it was a silent bug. They were registered on the `ide` server
 * because `--mcp-config` was one hardcoded string and a second server meant editing it — and the
 * consequence, unnoticed until 0.8.0, was that **the model could never see or call a single one of
 * them.** The entire Android tool surface was unreachable, which is also why no amount of use ever
 * produced evidence for or against it.
 *
 * The name `ide` cannot be given up — it is what makes the CLI route edits through `openDiff` and treat
 * the connection as editor context. So the bridge answers to both names on one port, one token, one
 * server, and tells them apart by the path the client connected to. Anything the *model* should call
 * lives on [SIGHTLINE]; anything the *CLI* drives lives on [IDE].
 *
 * Platform-free and unit-tested; `IdeServer` only applies the result.
 */
enum class McpFace {
    /** The server the CLI knows as `ide`: editor RPC, and `getDiagnostics` for the model. */
    IDE,

    /** The server the CLI knows as `sightline`: tools meant for the model to call. */
    SIGHTLINE,
    ;

    companion object {
        /** The path that selects [SIGHTLINE]. Anything else is [IDE], including no path at all. */
        const val SIGHTLINE_PATH = "/sightline"

        /**
         * Reads the face from a WebSocket handshake's resource descriptor.
         *
         * Defaults to [IDE] for anything unrecognised — including null, `/`, and a path with a query
         * string. Defaulting the *other* way would put the android tools on the connection the CLI
         * drives, where they would be filtered out again: the failure this whole class exists to fix.
         */
        fun of(resourceDescriptor: String?): McpFace {
            val path = (resourceDescriptor ?: "").substringBefore('?').substringBefore('#').trimEnd('/')
            return if (path.equals(SIGHTLINE_PATH, ignoreCase = true)) SIGHTLINE else IDE
        }
    }
}
