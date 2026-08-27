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
  **Pass this as a file path, not a literal.** `--mcp-config` accepts either ("Load MCP servers from
  JSON files or strings"); verified against 2.1.215 by pointing it at a file and confirming
  `system/init.mcp_servers` listed `ide` (as `failed`, since the port was dead — proof the file was read
  and parsed, not ignored). The literal form puts the auth token in the process arguments, which other
  local users can read. Sightline writes an owner-only temp file and passes its path.
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
`getWorkspaceFolders` (`{success,folders:[{name,uri,path}],rootPath}`),
`getDiagnostics` (optional `uri`/`filePath`; scoped to that file else current+open editors — never a
project sweep; returns `{available,files:[{path,problems:[{severity,message,line,column,source}]}]}`,
`available:false` + `reason` when uncollectable e.g. indexing; reads daemon markup, cached by mod stamp),
`openFile` (`{filePath,preview,startText,endText,makeFrontmost}`),
**`openDiff`** (`{old_file_path,new_file_path,new_file_contents,tab_name}` → **blocking**; returns
`"FILE_SAVED"` on accept / `"DIFF_REJECTED"` on reject; the IDE writes the file on accept. Guarded by
`PathAccessPolicy`: sensitive targets are refused; writes outside the project require a second confirm),
`close_tab` (`{tab_name}` → `"TAB_CLOSED"` **only if a tab actually matched and closed**; otherwise a
failure naming the tab. Matches the file name first, then the full path),
`closeAllDiffTabs` (`"CLOSED_0_DIFF_TABS"` — always zero here, and truthfully so: Sightline reviews a
diff in a **modal dialog**, not an editor tab, so one never outlives its dialog),
`checkDocumentDirty`, `saveDocument`, `executeCode` (Jupyter — N/A here).
Naming: camelCase except `close_tab`. Reference:
<https://github.com/coder/claudecode.nvim/blob/main/PROTOCOL.md>.

---

## 5. Changing a running session's MCP servers (`mcp_set_servers`)

**Verified empirically against CLI 2.1.228 on 2026-08-12.** Everything in this section was probed; the
scripts are throwaway but the captured payloads are the fixtures in `McpControlJsonTest`.

The received wisdom — including from Claude Code itself — is that adding an MCP server needs a full
restart, because a session's tools are resolved at startup. That is true of `/mcp`'s *reconnect*, which
re-establishes a connection without changing the tool index. **It is not true of the control protocol**,
which a stream-json host like Sightline is already speaking.

The CLI's full control-request vocabulary is in the binary (`strings` over the embedded JS finds the
dispatch). The MCP-related ones are `mcp_set_servers`, `mcp_status`, `mcp_reconnect`, `mcp_toggle`,
`mcp_message`, `mcp_authenticate`, `mcp_clear_auth`, `mcp_oauth_callback_url`,
`set_mcp_permission_mode_override`. Two matter here.

**`mcp_set_servers` — "Replaces the set of dynamically managed MCP servers."**

```json
{"type":"control_request","request_id":"X","request":{"subtype":"mcp_set_servers",
  "servers":{"playwright":{"type":"stdio","command":"npx","args":["@playwright/mcp"]}}}}
```
Response payload: `{"added":["playwright"],"removed":[],"errors":{}}`.

**`mcp_status`** takes no arguments and returns `{"mcpServers":[{name,status,scope,tools[],config}]}`.
It costs nothing — it never reaches the model.

What the probes established, in the order it matters:

1. **The tools become genuinely usable in the same conversation.** After adding a probe server, the
   model called `mcp__probe__probe_ping` and returned its output — same process, same session id, no
   relaunch. The next turn's `system/init` re-announces `mcp_servers` and lists the new `mcp__*` tools.
2. **"Authoritative" means only over the set sent by that request.** With `studio` (user scope, from
   `~/.claude.json`) and `ide` (from `--mcp-config`) already connected, an `mcp_set_servers` carrying an
   unrelated server left both untouched, and so did a subsequent `servers:{}` clear. `--mcp-config`
   servers are *reported* with `scope:"dynamic"` but are not part of the replaceable set. **This is what
   makes the feature safe to use at all** — otherwise a sync would tear down the `ide` bridge — so
   re-verify it if the CLI major changes.
3. **It is idempotent.** Re-sending an identical set returns `{"added":[],"removed":[],"errors":{}}` and
   does not churn a connected server. The CLI does the diffing, so the plugin does not have to.
4. **Failures are per-server, named, and in the CLI's own words** — `{"errors":{"broken":"Executable not
   found in $PATH: \"…\""}}`. A server can be in **both** `added` and `errors`: "added" means
   registered, not working. `mcp_status` then reports it as `failed`.
5. **The response does not arrive until every server has connected or timed out.** A server that accepts
   a connection and never answers `initialize` held the reply for **30s** (`"connection timed out after
   30000ms"`). The control channel and the session are fine afterwards — a following `mcp_status`
   returned in 0.1s and a normal turn completed — but **nothing may block on this reply**, which is why
   Sightline's sync is never on the send path. Do **not** set `alwaysLoad` on a synced server either: it
   is documented as blocking startup until the server connects.
6. **An older CLI says so cleanly.** An unknown subtype returns
   `{"subtype":"error","error":"Unsupported control request subtype: …"}` and the session stays usable,
   which is a precise fallback trigger. A malformed payload returns a *different* error
   (`"mcp_set_servers: servers must be an object of config objects"`), so the two must not be conflated.

**Where the CLI itself reads servers from** (all of which a *fresh* process picks up by itself):
`~/.claude.json` → `mcpServers` (user scope) and `projects.<cwd>.mcpServers` (local scope — this is
where `claude mcp add` writes by default), plus `<project>/.mcp.json` (project scope). Note that in
headless `-p` mode a checked-in `.mcp.json` is **auto-loaded with no approval prompt** (verified:
`repoServer` connected with `scope:"project"` and nothing was recorded in `enabledMcpjsonServers`) —
so the approval gate that exists interactively is not one a `-p` host can rely on.

### Still to verify live (in a running AS)
- Whether the CLI auto-injects the current selection each prompt vs. only on `getCurrentSelection`.
- Whether edits route through `openDiff` automatically (needs `diffTool=auto`) and how that
  interacts with `--permission-prompt-tool` (possible double approval on edits).

---

## 6. Session control, subagents, slash commands, checkpoints (CLI 2.1.235)

**Verified empirically against CLI 2.1.235 on 2026-08-27**, by driving `claude -p --input-format
stream-json` directly and by reading schema declarations out of the binary. Every claim below is a
probe result or a schema the CLI itself declares; nothing here is inferred from the changelog.

The plugin had been built against ~2.1.215, and the surface had moved a long way.

### 6.1 The full control-request vocabulary

The SDK's `Query.processControlRequest` dispatch (readable in the binary) gives both directions.

**Host → CLI**, all as `{"type":"control_request","request_id":"…","request":{"subtype":"…",…}}`:
`initialize`, `interrupt`, `set_permission_mode`, `set_mcp_permission_mode_override`, `set_model`,
`set_max_thinking_tokens`, `apply_flag_settings`, `get_settings`, `rewind_files`,
`cancel_async_message`, `seed_read_state`, `set_cwd`, `remote_control`, `generate_session_title`,
`side_question`, `ultrareview_launch`, `submit_feedback`, `message_rated`, `get_workspace_diff`, plus
the `mcp_*` family from §5.

`initialize` accepts far more than the bare subtype: `hooks`, `sdkMcpServers`, `jsonSchema`,
`systemPrompt`, `appendSystemPrompt`, `planModeInstructions`, `appendSubagentSystemPrompt`,
`toolAliases`, `excludeDynamicSections`, `agents`, `title`, `skills`,
`webSearchIsolationExemptMcpServers`, `promptSuggestions`, `agentProgressSummaries`,
`forwardSubagentText`, `supportedDialogKinds`.

**CLI → host**: `can_use_tool` (§2), `hook_callback`, `mcp_message`, `elicitation`,
`request_user_dialog`, `oauth_token_refresh`, `host_auth_token_refresh`. Sightline answers the first
and errors on the rest, which is safe: `dialog_kind` is an open string union and the CLI **fails
closed** — it only sends a dialog whose kind the host declared in `supportedDialogKinds`, and
declaring kinds without a handler is rejected outright.

Probed replies, verbatim:

| Request | Reply |
|---|---|
| `set_permission_mode {"mode":"plan"}` | `{"mode":"plan"}`, **plus** a `system/status` echoing `permissionMode` |
| `get_workspace_diff` | `{"diff":{stats,perFileStats,hunks,skippedLarge,restricted,source}}` |
| `get_settings` | `{"effective":{permissions:{allow:[…]},…}}` |
| `set_max_thinking_tokens` | `{}` (success, no payload) |
| `generate_session_title` | `{"title":null}` with no conversation yet |
| `rewind_files` (no checkpoint) | `{"canRewind":false,"error":"No file checkpoint found for this message."}` |
| unknown subtype | `{"subtype":"error","error":"Unsupported control request subtype: …"}`, session stays usable |

That last one remains the version-gate trigger, unchanged from §5.

### 6.2 `interrupt` — and the one thing it does not do

`{"subtype":"interrupt"}` replies in **~0.3s** with `{"still_queued":[]}`. The process stays alive,
the `session_id` is unchanged, and a following user message runs a normal turn on the same process.
After it the CLI emits, in order:

1. a `user` message whose content is the text `[Request interrupted by user]` (carries a `uuid`);
2. `result` with `subtype:"error_during_execution"`, `is_error:true`, `result:null`.

**It does not kill a command that is already running.** A probe ran `sleep 40 && touch SENTINEL`,
interrupted it three seconds in, and the sentinel appeared forty seconds later. The agent takes no
further step; the shell it already started runs to completion. Killing the CLI process does not stop
that child either. This is why `ui/state/StopPolicy` words its notice around what actually stopped —
"Stopped" alone, in an Android IDE, reads as "your Gradle build stopped", and it did not.

### 6.3 A server named `ide` is filtered down to two tools

The binary carries a hardcoded allowlist:

```
["mcp__ide__executeCode","mcp__ide__getDiagnostics"]
```

**Every other tool on an MCP server named `ide` is filtered out before the tool list reaches the
model.** Verified two ways: a stdio server named `ide` advertising thirteen tools produced exactly one
model-visible tool (`mcp__ide__getDiagnostics`), and the *same* server under the name `sightline`
produced all of them.

For the IDE RPC this is correct and desirable — the CLI calls `openDiff`, `getCurrentSelection`,
`close_tab` and the rest itself over `callIdeRpc`, and the model has no business seeing them. For
Sightline's own `android_*` tools it was a **silent bug**: they were registered on the `ide` server, so
from the model's point of view the entire Android tool surface never existed. Fixed in 0.8.0 by serving
two MCP servers on one socket — see `ide/McpFace`.

Also observed: the CLI **sanitises `.` out of tool names** when presenting them to the model
(`android.getContext` → `mcp__sightline__android_getContext`), which is why the tools were renamed to
underscores at the source rather than left to a rewrite we do not control.

### 6.4 Slash commands work over stream-json stdin

Sending `{"type":"user","message":{"role":"user","content":"/context"}}` **executes the local
command**: the reply was a `<synthetic>` assistant message with `num_turns: 0` and
`total_cost_usd: 0` — no API call at all.

The catalogue comes from two places. `system/init` carries `slash_commands` as bare names. The
`initialize` control_response carries `commands: [{name, description, argumentHint}]`, which is the
only source of descriptions and argument hints. Both are read (`ui/state/SlashCommands`), the richer
winning per name.

### 6.5 Subagent output (`--forward-subagent-text`)

With the flag (or `forwardSubagentText: true` on `initialize`), a subagent's `assistant` and `user`
messages arrive on the same stream as the main agent's, distinguished **only** by a top-level
`parent_tool_use_id` equal to the `Task`'s `tool_use.id`. They carry ordinary content blocks —
`thinking`, `tool_use`, `text` and `tool_result`.

Rendered as ordinary transcript blocks they interleave with the main agent's reply and read as one
confused voice, so `ui/state/SubagentPresentation` folds them into the owning `Task` card and drops
thinking and tool results.

`agentProgressSummaries` additionally produces `system/task_started`, `system/task_progress`,
`system/task_updated` and `system/task_notification`. Not consumed yet.

### 6.6 File checkpointing and `rewind_files` — **verified end to end**

Two halves are required, and the SDK sets both for you:

```
CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true    # arms the backups
--replay-user-messages                            # the only way the checkpoint ids reach the host
```

Each user message we send is then echoed back on stdout carrying a `uuid`; that uuid **is** the
checkpoint. `{"subtype":"rewind_files","user_message_id":"<uuid>"}` restores the files written since.

A probe edited `utils.py` through the `Edit` tool and rewound it: the file came back byte-for-byte
identical to the original, with `{"canRewind":true,"skippedLinks":0}`.

Three things a host must get right:

- **A `success` reply can still carry `canRewind:false`** — the subtype is not the outcome.
- **Only `Write`/`Edit`/`NotebookEdit` are tracked.** Not `Bash`, not a subagent's edits (which is
  most of a `Task`), not directory creation or deletion. A revert that silently covers only part of
  what the agent did is more dangerous than none, which is why `ui/state/CheckpointPolicy` repeats
  the limits at every click rather than in a doc.
- **`skippedLinks`** counts tracked paths the CLI refused to write (a symlink, a moved parent
  directory). Non-zero is a *partial* restore and must not read as a whole one.

The env-var name is SDK-internal and `--rewind-files` is deliberately absent from `claude --help`, so
this is the `UNKNOWN` rung of the fact ladder: offered behind a setting, **off by default**, never
promised.

### 6.7 Two events the panel used to drop

Both schemas are the CLI's own declarations:

```
{"type":"system","subtype":"compact_boundary",
 "compact_metadata":{"trigger":"manual"|"auto","pre_tokens":N,"post_tokens":N?,
                     "cumulative_dropped_tokens":N?  /* @internal — do not surface */}}

{"type":"rate_limit_event","rate_limit_info":{
   "status":"allowed"|"allowed_warning"|"rejected",
   "resetsAt":<epoch int>?, "utilization":N?,
   "rateLimitType":"five_hour"|"seven_day"|"seven_day_opus"|"seven_day_sonnet"
                  |"seven_day_overage_included"|"overage"}}
```

`resetsAt` is typed only as an integer and the unit is not stated; seconds and milliseconds are three
orders of magnitude apart, so `ui/state/SessionNotices` decides by magnitude and yields **no time at
all** for a value that fits neither.

### 6.8 Flags that exist and are not used yet

`--effort <low|medium|high|xhigh|max>`, `--max-budget-usd`, `--fallback-model`, `--autocompact`,
`--json-schema`, `--agents` / `--agent`, `--plugin-dir` / `--plugin-url`, `--add-dir`, `--tools` /
`--allowedTools` / `--disallowedTools`, `--setting-sources`, `--session-id`, `-n/--name`,
`--include-hook-events` (verified: emits `system/hook_started` + `system/hook_response` per hook),
`--prompt-suggestions` (emits a `prompt_suggestion` event after each turn), `--worktree`, `--bg`,
`--cloud`, `--from-pr`. Subcommands: `claude agents`, `claude ultrareview`, `claude doctor`.
