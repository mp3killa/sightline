# Claude Code Panel

A **graphical Claude Code chat panel for Android Studio** (and other IntelliJ‑based IDEs).
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

- A **Claude** tool window (right dock) whose colors follow your IDE theme.
- **Streaming** assistant replies with light markdown (bold, inline code, code blocks) and inline **extended‑thinking**.
- **Tool‑call rendering**: `Bash` commands, `Read`/`Grep`/`Glob`, `Write`, `TodoWrite`, `WebFetch`/`WebSearch`, and **colored line‑diffs** for `Edit`/`MultiEdit`, with a JSON fallback for anything else.
- **Tool results** attached under the call that produced them (truncated when long, red on error).
- **Model** and **permission‑mode** selectors, a **New** button, a **Stop** button, and a **⚙ settings** button.
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
build/distributions/claude-code-panel-0.1.0.zip
```

## Install into Android Studio

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Choose `build/distributions/claude-code-panel-0.1.0.zip`
3. Restart the IDE.
4. Open the **Claude** tool window on the right edge and start chatting.

## Develop / run a sandbox IDE

```bash
./gradlew runIde
```

Launches a throwaway Android Studio with the plugin preinstalled. Edit the Kotlin under
`src/main/kotlin`, then re‑run.

## Settings

**Settings → Tools → Claude Code Panel** (or the ⚙ button in the panel).

| Setting | Default | Notes |
|---|---|---|
| Claude command | `claude` | Auto‑detected across common install dirs and your login shell. Set an absolute path (or `npx @anthropic-ai/claude-code`) to override. |
| Model | Default | `Default` uses the CLI's configured model; or pick Opus / Sonnet / Haiku. |
| Permission mode | Auto‑accept edits | See below. |
| Stream partial messages | on | The live typing effect. |
| Extra CLI args | — | Advanced: appended to every invocation. |

### Permission modes

Passed to the CLI as `--permission-mode`, mirroring VS Code's modes:

- **Auto‑accept edits** (`acceptEdits`) — edits and safe filesystem ops apply without prompting.
- **Manual** (`default`) — tools needing interactive approval are skipped rather than blocking the panel (see *Roadmap*).
- **Plan** — read‑only exploration and planning.
- **Bypass** (`bypassPermissions`) — auto‑approves everything. Use only in trusted repos.

## How it works

```
 Android Studio  --  Claude tool window
 +-----------------------------------------------+
 | ClaudePanel.kt (Swing)                         |
 |   JTextPane (StyledDocument) transcript        |   renders events
 |   toolbar (model / mode / new / settings)      |
 |   composer (textarea + Send / Stop)            |
 |        ^ parsed stream-json events             |
 |        |                                       |
 |   ClaudeSession.kt  -- persistent process -----+--> claude -p
 |        stdin: {"type":"user",...}              |     --input-format stream-json
 |        stdout: NDJSON stream events            |     --output-format stream-json
 +-----------------------------------------------+     --verbose --include-partial-messages
```

- **`ClaudeSession`** launches one long‑lived `claude` process in bidirectional streaming‑JSON
  mode. Each user turn is one JSON line to stdin; every stdout line is a stream event
  (`system/init`, `stream_event` deltas, `assistant`/`user` blocks, `result`).
- **`ClaudePanel`** parses those events (Gson) and renders them into a single `JTextPane`
  styled document: streaming text is appended live, completed text is re‑rendered with light
  markdown, tool calls become headers + bodies (diffs for edits), and tool results are inserted
  under their call using a tracked `Position`.

Key source files:

| File | Role |
|---|---|
| [ClaudeToolWindowFactory.kt](src/main/kotlin/io/mp/claudecodepanel/ClaudeToolWindowFactory.kt) | Registers the tool window |
| [ui/ClaudePanel.kt](src/main/kotlin/io/mp/claudecodepanel/ui/ClaudePanel.kt) | Swing chat UI + event rendering |
| [process/ClaudeSession.kt](src/main/kotlin/io/mp/claudecodepanel/process/ClaudeSession.kt) | CLI process + stream‑json plumbing |
| [process/ClaudePathResolver.kt](src/main/kotlin/io/mp/claudecodepanel/process/ClaudePathResolver.kt) | Finds the `claude` binary in GUI‑launched IDEs |
| [settings/ClaudeSettings.kt](src/main/kotlin/io/mp/claudecodepanel/settings/ClaudeSettings.kt) | Persisted settings |

## Roadmap / known limitations

- **Interactive per‑tool approval** (VS Code's inline Allow/Deny with a diff) is not yet wired.
  It needs the CLI stream‑json *control protocol* (`can_use_tool`); today the panel uses
  permission **modes** instead. Natural next milestone.
- **Rich markdown** is intentionally light (bold, inline code, fenced code). Tables, nested lists,
  and images render as plain text.
- **Real IDE integration** — opening diffs in the editor's diff viewer and sharing your selection —
  isn't implemented (the official plugin does this via the `ide` MCP server). Everything renders
  in the panel.

## Troubleshooting

- **“Could not launch claude”** — set an absolute path in *Settings → Tools → Claude Code Panel → Claude command*. GUI apps often don't inherit your shell `PATH`; the panel shows an error you can act on.
- **Build fails with a Kotlin “internal compiler error”** — the Kotlin plugin version in `build.gradle.kts` must be ≥ the Kotlin bundled in your Android Studio (2026.1 ships **2.3.10**).
- **Build can't find Java 21** — point `org.gradle.java.home` in `gradle.properties` at your Android Studio's `Contents/jbr/Contents/Home`.
