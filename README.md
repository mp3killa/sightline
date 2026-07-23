# Sightline

**Sightline** is a **graphical chat panel for Claude Code in Android Studio** (and other IntelliJ‑based IDEs).
It drives your locally installed `claude` CLI over its streaming‑JSON protocol and renders the
conversation — streaming replies, extended thinking, tool calls, edits with diffs, and results —
inside a **native Swing tool window**. No embedded browser, no runtime changes: it works on the
stock Android Studio runtime.

> This is an **independent wrapper**, not an official Anthropic product. If you want the official
> integration (diffs, selection sharing, diagnostics, `Cmd+Esc` launch) in a *terminal*, install
> the official “Claude Code [Beta]” plugin from the JetBrains Marketplace. This project adds a
> **graphical panel** instead.

---

## What you get

- A **Sightline** tool window (right dock) whose colors follow your IDE theme.
- **Streaming** assistant replies rendered as **real Markdown** — headings, nested and task lists,
  GFM tables (horizontally scrollable when wide), block quotes, `> [!WARNING]` callouts, and fenced
  code that is **syntax‑highlighted in the IDE's own colours**, with Copy and Expand/Collapse past
  24 lines. Inline **extended‑thinking** collapses into its own row.
- **Project‑aware file links**: a file reference in a reply becomes clickable when it actually
  resolves in your project — never a guess.
- **Tool‑call rendering**: `Bash` commands, `Read`/`Grep`/`Glob`, `Write`, `TodoWrite`, `WebFetch`/`WebSearch`, and **colored line‑diffs** for `Edit`/`MultiEdit`, with a JSON fallback for anything else.
- **Tool results** attached under the call that produced them (truncated when long, red on error).
- **Interactive per‑tool approval** — inline Allow / Always allow / Deny cards over the CLI control
  protocol, plus **AskUserQuestion** rendered as a real form (radio / checkbox / free‑text "Other").
- **Paste images as context** — ⌘V a screenshot (or "Copy Image" from a browser) into the composer
  and it attaches as a removable thumbnail chip, downscaled and encoded **in memory** (never written
  to disk), then sent alongside your text so you can ask about exactly what you're seeing. Pasting a
  copied *file* attaches it as an `@path` chip instead.
- **Native IDE integration** via the `ide` MCP server: Claude sees your selection and open editors,
  gets scoped diagnostics, and edits open in **Android Studio's own diff viewer** to accept or reject.
- The **Agent Activity Map** — a live force‑directed graph of what Claude is *observably* touching
  (files, searches, commands, Gradle tasks, tests, errors), with a focus card, node inspector,
  timeline, and **Chat / Split / Map** layouts.
- A **Health check** panel (composer **More ▸ Health check…**) that preflights the CLI, the IDE
  server and your settings, with a **sanitised** "Copy report" for bug reports.
- **Multi‑turn** conversations over a single persistent CLI process, with automatic session **resume** after Stop.

## Requirements

- **Android Studio 2026.1** (build 261) — the build targets your installed copy. Other IntelliJ
  IDEs of the same generation work too (the plugin only depends on the platform module).
- The **Claude Code CLI** installed and logged in (`claude --version` should work in a terminal).
- **JDK 21** to build — Android Studio bundles one; the build is preconfigured to use it.

## Build

```bash
# Uses Android Studio's bundled JBR 21 (already set in gradle.properties).
./gradlew buildPlugin
```

Installable zip:

```
build/distributions/sightline-0.1.0-beta.zip
```

CI builds without a local Android Studio by selecting a downloadable platform — the same command CI
runs, if you want to reproduce it:

```bash
./gradlew test buildPlugin -PplatformType=AI -PplatformVersion=2025.3.1.1
```

Releasing is documented in [docs/RELEASING.md](docs/RELEASING.md).

## Install into Android Studio

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Choose `build/distributions/sightline-0.1.0-beta.zip`
3. Restart the IDE.
4. Open the **Sightline** tool window on the right edge and start chatting.

## Develop / run a sandbox IDE

```bash
./gradlew runIde
```

Launches a throwaway Android Studio with the plugin preinstalled. Edit the Kotlin under
`src/main/kotlin`, then re‑run.

## Settings

**Settings → Tools → Sightline** (or the ⚙ button in the panel).

| Setting | Default | Notes |
|---|---|---|
| Claude command | `claude` | Auto‑detected across common install dirs and your login shell. Set an absolute path (or `npx @anthropic-ai/claude-code`) to override. |
| Model | Default | `Default` uses the CLI's configured model; or pick Opus / Sonnet / Haiku. |
| Permission mode | **Auto** | See below. |
| Interactive approval | on | Inline Allow/Deny cards (`--permission-prompt-tool stdio` + control protocol). |
| IDE integration | on | Runs the `ide` MCP server: selection, open editors, diagnostics, native diffs. |
| Stream partial messages | on | The live typing effect. |
| Show details | off | Detailed transcript (thinking + tool cards) vs. compact. Approval and question cards always stay visible. |
| Show activity map | on | Plus layout (`chat`/`split`/`map`, default **split**), reduce motion, and node caps (200 visible / 500 retained). |
| Extra CLI args | — | Advanced: appended to every invocation. |

### Permission modes

Passed to the CLI as `--permission-mode`, and composed with interactive approval:

- **Auto** (`auto`, default) — Claude approves safe actions and pauses for risky ones. **Model‑gated**:
  only honored on Sonnet/Opus; on Haiku the CLI silently falls back to `default`.
- **Ask** (`default`) — Claude asks for approval before each tool that needs it.
- **Auto‑edit** (`acceptEdits`) — edits apply automatically; commands still prompt.
- **Plan** — read‑only exploration and planning.
- **Unrestricted** (`bypassPermissions`) — never asks. Use only in trusted repos.

## How it works

```
 Android Studio  --  Sightline tool window
 +-----------------------------------------------+
 | ClaudePanel.kt (Swing)                         |
 |   transcript: per-turn block components        |   renders events
 |     (text / thinking / tool card / approval)   |
 |   ActivityMapPanel  (chat | split | map)       |
 |   header (wordmark / state / layout switch)    |
 |   composer (textarea + mode chip + Send/Stop)  |
 |        ^ parsed stream-json events             |
 |        |                                       |
 |   ClaudeSession.kt  -- persistent process -----+--> claude -p
 |        stdin: {"type":"user",...}              |     --input-format stream-json
 |        stdout: NDJSON stream events            |     --output-format stream-json
 |        control protocol: can_use_tool          |     --permission-prompt-tool stdio
 |                                                |     --include-partial-messages
 |   IdeServer.kt  --  ws MCP server  <-----------+--- --mcp-config (ide)
 +-----------------------------------------------+
```

- **`ClaudeSession`** launches one long‑lived `claude` process in bidirectional streaming‑JSON
  mode. Each user turn is one JSON line to stdin; every stdout line is a stream event
  (`system/init`, `stream_event` deltas, `assistant`/`user` blocks, `result`). It also serves the
  **control protocol**, which is how `can_use_tool` approval prompts arrive.
- **`ClaudePanel`** parses those events (Gson) into a transcript of **block components** — a
  `TextBlock` streams tokens live and, once the block ends, swaps to a Markdown component tree;
  tool calls become collapsible cards with diffs; approvals and questions render as inline forms.
- **`IdeServer`** is a WebSocket MCP server the CLI connects to as `ide`, giving Claude the
  editor selection, open editors, scoped diagnostics and native diffs — all path‑guarded by
  `PathAccessPolicy`.

Key source files:

| File | Role |
|---|---|
| [ClaudeToolWindowFactory.kt](src/main/kotlin/io/mp/sightline/ClaudeToolWindowFactory.kt) | Registers the tool window |
| [ui/ClaudePanel.kt](src/main/kotlin/io/mp/sightline/ui/ClaudePanel.kt) | Swing chat UI + event rendering |
| [ui/markdown/](src/main/kotlin/io/mp/sightline/ui/markdown/) | Markdown parsing + rendering for assistant messages |
| [ui/ActivityMapPanel.kt](src/main/kotlin/io/mp/sightline/ui/ActivityMapPanel.kt) | The Agent Activity Map view |
| [activity/](src/main/kotlin/io/mp/sightline/activity/) | Platform‑free activity model, graph reducer and output parsers |
| [process/ClaudeSession.kt](src/main/kotlin/io/mp/sightline/process/ClaudeSession.kt) | CLI process + stream‑json plumbing |
| [process/ClaudePathResolver.kt](src/main/kotlin/io/mp/sightline/process/ClaudePathResolver.kt) | Finds the `claude` binary in GUI‑launched IDEs |
| [ide/IdeServer.kt](src/main/kotlin/io/mp/sightline/ide/IdeServer.kt) | The `ide` MCP WebSocket server |
| [ide/PathAccessPolicy.kt](src/main/kotlin/io/mp/sightline/ide/PathAccessPolicy.kt) | Path guard for open/diff/write targets |
| [interaction/](src/main/kotlin/io/mp/sightline/interaction/) | AskUserQuestion parsing, form state and response building |
| [health/](src/main/kotlin/io/mp/sightline/health/) | Health / preflight checks + report sanitiser |
| [settings/ClaudeSettings.kt](src/main/kotlin/io/mp/sightline/settings/ClaudeSettings.kt) | Persisted settings |

For the reverse‑engineered CLI protocol see [docs/PROTOCOL.md](docs/PROTOCOL.md); for how the
interactive flows are verified see [docs/TESTING.md](docs/TESTING.md).

## Roadmap / known limitations

- **Images** in assistant replies are not rendered (all other GFM Markdown is).
- **Deep code relationships** — full call graphs and inferred architectural links (ViewModel →
  Composable, UseCase → Repository) are deliberately deferred; the map shows evidence‑backed
  relationships only, so it never claims a link it can't justify.
- **Timeline replay & persistence** — the activity log lives in memory for the current session
  only; replaying to an earlier point and retaining past sessions are planned.
- The activity map shows **observable** activity only. It makes no claim to reveal hidden reasoning.

## Troubleshooting

- **Start with the built‑in health check** — composer **More ▸ Health check…** preflights the CLI,
  the IDE server and your settings, and states what it verified plus a concrete hint for anything
  not OK. **Copy report** yields a sanitised report safe to paste into an issue.
- **“Could not launch claude”** — set an absolute path in *Settings → Tools → Sightline for Claude Code → Claude command*. GUI apps often don't inherit your shell `PATH`; the panel shows an error you can act on.
- **Build fails with a Kotlin “internal compiler error”** — the Kotlin plugin version in `build.gradle.kts` must be ≥ the Kotlin bundled in your Android Studio (2026.1 ships **2.3.10**).
- **Build can't find Java 21** — point `org.gradle.java.home` in `gradle.properties` at your Android Studio's `Contents/jbr/Contents/Home`.

---

## Licence, privacy and security

Sightline is **source-available** software — see [LICENSE](LICENSE). The source is published so it can
be read, audited and built; it is **not** open source, and may not be redistributed or commercially
exploited. Using it as a tool in your work, including commercially, is expressly permitted and is the
point of it. Free during the beta.

Redistributed third-party components keep their own licences, reproduced in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [licenses/](licenses/).

- **[PRIVACY.md](PRIVACY.md)** — no telemetry, no conversation persistence, no credential handling, and
  exactly what does leave your machine.
- **[SECURITY.md](SECURITY.md)** — how to report a vulnerability privately, and the guards in place.
- **[docs/PERMISSIONS.md](docs/PERMISSIONS.md)** — what each permission mode allows, and the three
  guards that apply regardless of mode.
- **[docs/DATA-FLOW.md](docs/DATA-FLOW.md)** — where data goes, with the source files and runtime checks to verify it yourself.

Sightline requires a **user-managed Claude Code installation**: you install and authenticate the
`claude` CLI yourself, and pay Anthropic directly. Sightline does not provide Claude access, has no
login screen, and never handles your credentials.

Sightline is an independent project, not affiliated with or endorsed by Anthropic, JetBrains or Google.
"Claude" and "Claude Code" are trademarks of Anthropic; "Android Studio" is a trademark of Google. Both
are used only to describe compatibility.

## Reporting problems

Bug reports are very welcome while this is in beta — see [CONTRIBUTING.md](CONTRIBUTING.md). Run the
in-plugin **Health check** first and use *Copy report*: it is sanitised (no home path, username, email
or token) and safe to paste anywhere.

Security problems go to **support@cxk.co.za**, not to a public tracker — see [SECURITY.md](SECURITY.md).
