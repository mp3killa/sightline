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

Exactly five things, all of them yours:

1. **Your typed message.**
2. **Attachments**, as `@project/relative/path` — the CLI reads the file, Sightline doesn't send contents.
3. **Pasted images**, as base64 `image` blocks on the same JSON line. Before sending, the image is
   downscaled to at most 2576px on its long edge and re-encoded (PNG, or JPEG when photographically
   large) **entirely in memory** — nothing is written to disk, and the full-size bytes are released
   once the message leaves. The chip and the transcript thumbnail show exactly what was sent.
4. **The Android context block**, when enabled. Rendered by `AndroidContextFormatter.promptBlock` from
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
5. **What you explicitly asked for**: a logcat capture (redacted), a diagnostic, a command's output.

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
| Settings | `sightline.xml` | Until uninstall |
| Android cache *(opt-in)* | `.sightline/` | Until deleted; capped and versioned |
| Bridge lock | `~/.claude/ide/<port>.lock` | Until the IDE closes |
| Bridge MCP config *(owner-only)* | A temp file, `rw-------` | Deleted when the CLI process exits |
| Diagnostics | `idea.log` | IDE log rotation |

Nothing in the first two rows is ever written to disk. That is a
[standing decision](../CLAUDE.md#standing-decisions-dont-re-litigate), not a gap.

## Verifying this yourself

Sightline is **source-available**: the source is published so that claims like the ones on this page can
be checked rather than taken on trust. That is most of the reason it is published at all. (Source-available
is not open source — see [LICENSE](../LICENSE) — but reading and building it to verify these claims is a
right the licence grants explicitly.)

### In the source

```bash
# The exact text prepended to a message, and the tests pinning its behaviour:
src/main/kotlin/io/mp/sightline/android/AndroidContextFormatter.kt
src/test/kotlin/io/mp/sightline/android/AndroidContextFormatterTest.kt

# The redactor, and its corpus test — asserts both that secrets don't survive
# and that ordinary log lines are untouched:
src/main/kotlin/io/mp/sightline/android/LogcatRedactor.kt
src/test/kotlin/io/mp/sightline/android/LogcatRedactorTest.kt

# The path guard, and the device-action gate:
src/main/kotlin/io/mp/sightline/ide/PathAccessPolicy.kt
src/main/kotlin/io/mp/sightline/android/AndroidActionPolicy.kt

# Build it and run the suite yourself:
./gradlew test
```

### In the running plugin

Source tells you what it intends; behaviour tells you what it does. These take a minute and are worth
more than either alone:

```bash
# 1. Sightline starts exactly one CLI process and talks to it over stdin/stdout.
#    Another child process would be a red flag.
ps aux | grep '[c]laude -p'

# 2. The bridge is loopback-only — 127.0.0.1, never 0.0.0.0 or an external interface.
lsof -nP -iTCP -sTCP:LISTEN | grep -i java

# 3. Sightline makes no outbound connections of its own. Established remote
#    connections should belong to `claude`, not to the IDE.
lsof -nP -iTCP -sTCP:ESTABLISHED | grep -iE 'java|claude'

# 4. Nothing is written outside settings and the opt-in cache.
ls -la .sightline/ 2>/dev/null        # absent unless you enabled it
```

Two more you can see without any tooling:

- **Every logcat capture reports what it removed** — "8 values redacted (3 tokens, 2 emails …)". A
  capture that redacted nothing says so too. A zero count on a log you know contains a token is a bug
  worth reporting.
- **The Activity Map shows what was actually touched.** A file appearing there that you did not expect
  Claude to read is visible at the time, not after the fact.
