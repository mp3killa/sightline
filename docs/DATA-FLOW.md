# Data flow

Where your data goes, and where it doesn't. This is the technical companion to
[PRIVACY.md](../PRIVACY.md) — that one is the policy, this one is the mechanism.

## The shape of it

```
   ┌──────────────────────────── your machine ───────────────────────────┐
   │                                                                     │
   │   Android Studio                                                    │
   │   ┌──────────────────────────┐                                      │
   │   │  Sightline (this plugin) │                                      │
   │   │                          │                                      │
   │   │  composer ──┐            │                                      │
   │   │  chips ─────┤            │                                      │
   │   │  attachments┘            │                                      │
   │   └────────┬─────────────────┘                                      │
   │            │ stdin: one JSON line per message                       │
   │            ▼                                                        │
   │   ┌──────────────────────────┐                                      │
   │   │  claude CLI              │ ← you installed and authenticated    │
   │   │  (separate process)      │   this, separately                   │
   │   └────────┬─────────────────┘                                      │
   │            │ ▲ ws://127.0.0.1:<port>  ── the `ide` bridge           │
   │            │ └───────────────────────────── loopback + token       │
   └────────────┼────────────────────────────────────────────────────────┘
                │ HTTPS
                ▼
        Anthropic (or your configured provider)
        under YOUR account, YOUR agreement
```

**Sightline is never on the path between the CLI and Anthropic.** It writes to a local process's
stdin and reads its stdout. It has no backend, no proxy, and no network egress of its own.

## What Sightline puts on that stdin

Exactly four things, all of them yours:

1. **Your typed message.**
2. **Attachments**, as `@project/relative/path` — the CLI reads the file, Sightline doesn't send contents.
3. **The Android context block**, when enabled. Rendered by `AndroidContextFormatter.promptBlock` from
   the chips that are switched on:

   ```
   <android-context>
   Module: app (:app)
   Application ID: com.example.app.staging
   SDK: min 24, target 36, compile 36
   Build variant: exampleStagingDebug (last build)
   Device: Pixel 8 (emulator-5554) · ready · API 35 · app running
   Open file: app/src/main/java/com/example/ui/HomeScreen.kt
   </android-context>
   ```

   Paths are **project-relative**. Removing a chip removes its lines — the chips are the control, not a
   description of one.
4. **What you explicitly asked for**: a logcat capture (redacted), a diagnostic, a command's output.

## What the CLI adds, which Sightline does not control

Claude Code then reads files, runs commands, and searches — and sends what it finds to Anthropic. That
is the CLI's behaviour, governed by your permission mode. Sightline's role is to *show* it (tool cards,
approval prompts, the Activity Map) and to let you stop it.

**So: if you ask Claude about a file, that file leaves your machine.** No plugin setting changes that.
It is what an AI coding assistant is.

## The `ide` bridge, in the other direction

The CLI connects back to Sightline to ask about your editor. It can request:

| Tool | Returns |
|---|---|
| `getCurrentSelection` / `getLatestSelection` | Selected text + file path |
| `getOpenEditors` | Open tab paths |
| `getWorkspaceFolders` | Project roots |
| `getDiagnostics` | Errors/warnings for a file, scoped |
| `openFile` / `openDiff` | Opens in the IDE — path-guarded |
| `checkDocumentDirty` / `saveDocument` | Unsaved state — path-guarded |
| `android.*` | Context, tasks, tests, devices, logcat, screen, audits |

All of it is **pull, not push**: the CLI asks, Sightline answers. Sightline pushes only one
notification — `selection_changed` — and only to a connection that authenticated.

Every path-taking tool goes through `PathAccessPolicy` first. Every logcat response goes through
`LogcatRedactor` first, inside `AndroidDeviceTools`, before the text exists as a return value.

## Where redaction happens

Two places, both before data can leave:

- **`LogcatRedactor`** — on every logcat capture and every crash report, in `AndroidDeviceTools`. Not
  optional. Fails closed: an over-long line is dropped whole rather than partly scrubbed.
- **`HealthSanitizer`** — on the Health dialog's *Copy report*, so a report is safe to paste publicly.

## Where data rests

| | Location | Lifetime |
|---|---|---|
| Transcript, Activity Map | Memory | Until the project closes or **New** |
| Android context | Memory, ~15s cache | Same |
| Settings | `claudeCodePanel.xml` | Until uninstall |
| Android cache *(opt-in)* | `.sightline/` | Until deleted; capped and versioned |
| Bridge lock | `~/.claude/ide/<port>.lock` | Until the IDE closes |
| Diagnostics | `idea.log` | IDE log rotation |

Nothing in the first two rows is ever written to disk. That is a
[standing decision](../CLAUDE.md#standing-decisions-dont-re-litigate), not a gap.

## Verifying this yourself

You don't have to take the diagram's word for it:

```bash
# Everything the plugin sends is one JSON line per message on the CLI's stdin.
# Watch the process it starts:
ps aux | grep '[c]laude -p'

# The bridge is loopback-only. Nothing should be listening on an external interface:
lsof -nP -iTCP -sTCP:LISTEN | grep -i java

# The source of the prompt block, and its tests:
src/main/kotlin/io/mp/claudecodepanel/android/AndroidContextFormatter.kt
src/test/kotlin/io/mp/claudecodepanel/android/AndroidContextFormatterTest.kt

# The redactor, and its corpus test:
src/main/kotlin/io/mp/claudecodepanel/android/LogcatRedactor.kt
src/test/kotlin/io/mp/claudecodepanel/android/LogcatRedactorTest.kt
```
