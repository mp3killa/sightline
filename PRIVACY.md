# Privacy

**Short version:** Sightline collects no analytics and operates no server. It launches the Claude Code
CLI you installed and authenticated yourself, and passes it what you ask it to. Anything you send to
Claude reaches Anthropic under *your* account and *your* agreement with them — Sightline is not a party
to that and never sees your credentials.

This document describes what Sightline itself does. Last updated 2026-07-20, for 0.1.0-beta.

---

## What Sightline collects about you

**Nothing.** No analytics, no telemetry, no usage statistics, no advertising or device identifiers, no
crash reporting, no "anonymous" counters. There is no opt-out because there is nothing to opt out of,
and no server for such data to reach — Sightline has no backend.

If that ever changes it will be opt-in, announced in the changelog, and off until you turn it on.

## What Sightline sends to Claude

Sightline writes to the standard input of a `claude` process running on your machine. What goes into
that stream:

- **Your message**, as typed.
- **Files you attach**, as `@path` references the CLI then reads.
- **The Android context block**, when the feature is on and the chips are enabled — module, build
  variant, applicationId, SDK levels, the connected device, and the open file's project-relative path.
  Every chip is removable before you send, and removing one genuinely removes that fact from the
  message rather than hiding a label. See [docs/DATA-FLOW.md](docs/DATA-FLOW.md).
- **What you explicitly ask for**: a logcat capture, a screenshot, a diagnostic, a command's output.

Claude Code then decides what else to read — files, command output, search results — and sends it to
Anthropic. **That traffic is between the CLI and Anthropic; it does not pass through Sightline.** What
Anthropic does with it is governed by your agreement with them, not by this document.

### The consequence worth stating plainly

An AI coding assistant works by reading your code. If you ask Claude about a file, that file's contents
leave your machine. Sightline's job is to make that *visible* — through the tool cards, the approval
prompts, and the Activity Map — not to prevent it. Choose your permission mode accordingly; see
[docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## What Sightline redacts before sending

Logcat is the one place Sightline actively scrubs, because a device log is a running record of the app
handling real data — auth tokens on every request, an email at sign-in, coordinates on every location
update, device identifiers.

`LogcatRedactor` removes bearer tokens, JWTs, `Authorization` headers, API keys and secrets in
`key=value` or JSON form, email addresses, IP addresses, phone numbers, latitude/longitude pairs,
device and advertising identifiers, and home-directory paths. It **fails closed**: a line too long to
reason about is dropped whole rather than passed through partly scrubbed.

It reports what it removed, so you can tell a redacted value from an absent one. It is on by default
and is not a setting.

**It is not a guarantee.** It is pattern-based, and a sufficiently unusual secret in an unusual format
can survive. Treat a logcat attachment as something you are choosing to share.

## Credentials

Sightline **never** requests, reads, stores, transmits, or proxies your Claude credentials.

There is no login screen. Authentication belongs entirely to the Claude Code CLI, which you install and
sign in to separately, and which stores its own credentials in its own location under your control.
Sightline runs `claude` as a subprocess and communicates over its stdin/stdout — it has no access to
that session beyond what the CLI chooses to print.

Sightline does not resell, broker, or provide access to any model service. You pay Anthropic directly,
under your own account.

## What is stored on your machine

| What | Where | Cleared by |
|---|---|---|
| Settings (CLI path, permission mode, feature toggles) | IDE config, `claudeCodePanel.xml` | Settings → Tools → Sightline, or uninstalling |
| Conversation transcript | **Memory only** — never written to disk | Closing the project, or **New** |
| Activity Map nodes and edges | **Memory only** | Same |
| Android context | **Memory only**, cached ~15 seconds | Same |
| Android cache (**off by default**) | `.sightline/` in the project | Turning it off, or deleting the directory |
| IDE bridge lock file | `~/.claude/ide/<port>.lock` | Removed when the IDE closes |
| IDE logs | `idea.log` | The IDE's own log rotation |

**There is no session or transcript persistence.** Closing the project discards the conversation. This
is a deliberate standing decision, not an unimplemented feature.

The optional `.sightline/` cache (flaky-test history, screenshot baselines, artifact sizes) stores
**workspace-relative paths only** — never absolute paths, source contents, prompts, or model output.
That is enforced in code: a path outside the project is refused rather than stored absolute, because an
absolute path carries your username into a file that outlives the session.

## Logs

Sightline logs to the IDE's `idea.log`. It records event types, tool names, error classes, and plugin
and IDE versions. It deliberately does **not** log source contents, prompt text, model responses, or
credentials — failures are logged as metadata, and command lines that could carry a package name or
device serial are logged by exception class rather than verbatim.

The **Health check** dialog's *Copy report* action runs its output through a sanitiser that removes
tokens, keys, your home path, username, email addresses and IP addresses, so a report is safe to paste
into a public issue. It is heavily tested, for the obvious reason.

## Network

Sightline opens exactly one socket: a WebSocket server bound to `127.0.0.1` on an ephemeral port,
which the CLI connects to for editor context. It is **loopback only** — never reachable from another
machine — and authenticated with a random per-session token. Nothing else listens, and Sightline makes
no outbound network requests of its own.

## Removing everything

1. Settings → Plugins → Sightline → Uninstall.
2. Delete `.sightline/` from any project where you enabled the cache.
3. Settings are removed with the plugin; the transcript never existed on disk.

Removing Sightline does not touch your Claude Code CLI installation, its credentials, or its own
history. Uninstall that separately if you want it gone.

## Children

Sightline is a developer tool, not directed at children, and collects nothing from anyone.

## Contact

Privacy questions and corrections: open an issue on the public tracker, or email the address on the
Marketplace listing. If you believe Sightline leaks something it shouldn't, please treat it as a
security report — see [SECURITY.md](SECURITY.md).
