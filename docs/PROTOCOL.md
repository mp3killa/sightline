# Claude Code CLI — reverse-engineered protocol notes

Everything here was verified empirically against `claude` **2.1.212 / 2.1.214** (native binary at
`~/.local/share/claude/versions/<v>`) by probing the CLI and by reading the Agent SDK type defs
(`npm pack @anthropic-ai/claude-agent-sdk` → `package/sdk.d.ts`). Re-verify if the CLI major changes.

---

## 1. Driving the CLI (stream-json)

Launch one **persistent** process per conversation:

```
claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages
```

- **Input** (stdin, one JSON per line): `{"type":"user","message":{"role":"user","content":"…"}}`.
  Keep stdin **open** — the same process serves multiple turns (verified). Each turn = one user line.
- **Output** (stdout, NDJSON). Event order for a turn:
  1. `system` / `hook_started`, `hook_response`
  2. `system` / `init` — rich: `session_id`, `model`, `tools`, `permissionMode`, `mcp_servers`, `slash_commands`, `cwd`, `capabilities`, …
  3. `system` / `status` (`"requesting"`), `system` / `thinking_tokens`
  4. `stream_event` wrapping raw Anthropic events: `message_start`, `content_block_start`,
     `content_block_delta` (`delta.type` ∈ `text_delta` | `thinking_delta` | `input_json_delta` |
     `signature_delta`), `content_block_stop`, `message_delta`, `message_stop`
  5. `assistant` — **per-completed-block snapshot** (one block per event, in order)
  6. `user` — carries `tool_result` blocks (`tool_use_id`, `content`, `is_error`)
  7. `result` — `{result, session_id, total_cost_usd, duration_ms, num_turns, is_error, permission_denials, usage}`
- `--include-partial-messages` gives the token-level `stream_event` deltas (live typing).
- Session: `--resume <id>`, `--continue`, `--session-id <uuid>`, `--fork-session`.

**Rendering gotcha (duplicate tool calls):** a `tool_use` is emitted both by the streaming
`content_block_*` path and by the later `assistant` snapshot. `content_block_start` may **omit the
tool `id`**, so a "renderedTools" de-dupe by id fails and the snapshot re-renders (often with the
`cd …`-prefixed command). Fix: **skip snapshot tool_use rendering whenever `sawStream` is true**
(any `content_block_delta` was seen this turn).

---

## 2. Interactive tool approval (control protocol)

The permission prompt (`can_use_tool`) only fires when you pass the flag **`--permission-prompt-tool
stdio`** *and* perform the init handshake. Without the flag the CLI auto-denies un-permitted tools in
headless mode. (Enabling flag confirmed by reading the SDK: it pushes `--permission-prompt-tool
stdio` whenever a `canUseTool` callback is set.)

**Handshake — send on stdin right after start:**
```json
{"type":"control_request","request_id":"init-<uuid>","request":{"subtype":"initialize"}}
```
The CLI replies with a `control_response` (commands/models/account — ignorable). Init and the first
user message can be sent back-to-back (no delay needed; verified).

**Permission request — CLI → us (stdout):**
```json
{"type":"control_request","request_id":"<R>","request":{
  "subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash",
  "input":{...}, "description":"…", "tool_use_id":"toolu_…",
  "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"echo hi *"}],
     "behavior":"allow","destination":"localSettings"}, {"type":"addDirectories","directories":["…"],"destination":"session"}],
  "blocked_path":"…", "title":"…"}}
```

**Our reply — stdin:**
```json
{"type":"control_response","response":{"subtype":"success","request_id":"<R>",
  "response":{"behavior":"allow","updatedInput":{...},"updatedPermissions":[<suggestions>]}}}
```
- Allow: `{"behavior":"allow","updatedInput":<echo the input>}` (+ `updatedPermissions` = the
  `permission_suggestions` array for "always allow"). Verified: the tool then actually runs.
- Deny: `{"behavior":"deny","message":"…"}`.
- Any **unknown** control_request → reply `{"type":"control_response","response":{"subtype":"error","request_id":"<R>","error":"…"}}` so the CLI doesn't hang.

Composes with `--permission-mode` (`default` prompts all, `acceptEdits` only non-edit tools,
`bypassPermissions` none, `plan` read-only, `dontAsk`, `auto`).

**`auto` mode is model-gated.** `--permission-mode auto` (or the `set_permission_mode` control
request) is honored **only on capable models (Sonnet/Opus)** — it uses a model classifier to
approve safe actions and prompt for risky ones. On **Haiku** it fails: the control request returns
`"auto mode unavailable for this model"`, and the **launch flag silently falls back to `default`**
(verified: `permissionMode` echoes `default` for Haiku, `auto` for Sonnet/Opus). So a UI "Auto"
option must assume a capable model.

## 3. JetBrains "MCP Server" plugin (IDE tools for Claude)

Separate from our own `ide` server: the official JetBrains **MCP Server** plugin
(`com.intellij.mcpServer`, since-build 261.25134, loads in AS despite the marketplace refusing
`AI-…` per-build downloads — grab it by `updateId`). It exposes IDE tools (`mcp__studio__*`:
`get_all_open_file_paths`, `get_project_modules`, run configs, problems, terminal, …).

- **Enable it**: Settings → Tools → MCP Server → *Enable* → *Auto-Configure for Claude Code*. That
  writes an SSE server named `studio` at `http://127.0.0.1:64342/sse` into `~/.claude.json`.
- The old `npx @jetbrains/mcp-proxy` **does not work** with the 2025.2+ integrated server
  (connects but "tools fetch failed") — use the built-in SSE auto-config instead.
- **The panel gets these tools for free**: `claude -p` loads user-scoped MCP servers from
  `~/.claude.json` in addition to our `--mcp-config` (we don't pass `--strict-mcp-config`), so
  Claude in the panel can call `mcp__studio__*` while coding. Verified: it read `MyApplication`'s
  modules read-only.

---

## 4. IDE integration (the `ide` MCP server)

The CLI talks to an in-IDE **WebSocket MCP** server (MCP spec 2025-03-26 over ws, JSON-RPC 2.0).

### ⚠️ Discovery mechanism — the important part
- The documented **env-var + lockfile discovery is interactive-terminal only.** In headless `-p`
  mode the CLI **does NOT** read `CLAUDE_CODE_SSE_PORT` / `ENABLE_IDE_INTEGRATION` / `~/.claude/ide/<port>.lock`
  (empirically: no connection, `system/init.mcp_servers` never lists `ide`). Even
  `CLAUDE_CODE_IDE_SKIP_VALID_CHECK=1` doesn't help.
- **What works in `-p`:** register the ws server explicitly via `--mcp-config`:
  ```json
  {"mcpServers":{"ide":{"type":"ws","url":"ws://127.0.0.1:<port>",
     "headers":{"x-claude-code-ide-authorization":"<token>"}}}}
  ```
  Then the CLI connects, does the MCP handshake, and `system/init.mcp_servers` includes
  `{"name":"ide","status":"connected"}` (verified). Name it exactly **`ide`** for the CLI's special
  handling (auto-selection context, `openDiff` for edits when `diffTool=auto`).

### Server details
- Bind **127.0.0.1** only, random port.
- Auth: validate the `x-claude-code-ide-authorization` handshake header == the token (32-char lower
  hex, 16 CSPRNG bytes). Header lookup is case-insensitive in Java-WebSocket.
- Optional lockfile `~/.claude/ide/<port>.lock` (not needed with `--mcp-config`, but harmless):
  `{"pid":…,"workspaceFolders":["<proj>"],"ideName":"Android Studio","transport":"ws","authToken":"…"}`

### MCP messages
- `initialize` → `{protocolVersion:"2025-03-26", capabilities:{tools:{}}, serverInfo:{name,version}}`
- `notifications/initialized` → no reply
- `tools/list` → `{tools:[{name,description,inputSchema:{type:"object",properties:{}}}]}`
- `tools/call` `{name,arguments}` → `{content:[{type:"text",text:<usually JSON-stringified>}]}`
- Notifications IDE→CLI: `selection_changed` `{text,filePath,fileUrl,selection:{start:{line,character},end,isEmpty}}`, `at_mentioned` `{filePath,lineStart,lineEnd}`

### Tools (VS Code registers 12; see `IdeServer.kt`)
`getCurrentSelection`, `getLatestSelection`, `getOpenEditors` (`{tabs:[{uri,isActive,label,languageId,isDirty}]}`),
`getWorkspaceFolders` (`{success,folders:[{name,uri,path}],rootPath}`), `getDiagnostics` (per-file array; **stub returns `[]`**),
`openFile` (`{filePath,preview,startText,endText,makeFrontmost}`),
**`openDiff`** (`{old_file_path,new_file_path,new_file_contents,tab_name}` → **blocking**; returns
`"FILE_SAVED"` on accept / `"DIFF_REJECTED"` on reject; the IDE writes the file on accept),
`close_tab` (`"TAB_CLOSED"`), `closeAllDiffTabs` (`"CLOSED_<n>_DIFF_TABS"`),
`checkDocumentDirty`, `saveDocument`, `executeCode` (Jupyter — N/A here).
Naming: camelCase except `close_tab`. Reference:
<https://github.com/coder/claudecode.nvim/blob/main/PROTOCOL.md>.

### Still to verify live (in a running AS)
- Whether the CLI auto-injects the current selection each prompt vs. only on `getCurrentSelection`.
- Whether edits route through `openDiff` automatically (needs `diffTool=auto`) and how that
  interacts with `--permission-prompt-tool` (possible double approval on edits).
