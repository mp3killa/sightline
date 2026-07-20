# Security

Sightline lets an AI agent read your files, run commands, and change your code. That is the point of
it, and it is also the whole security story — an IntelliJ plugin runs with your user's full
permissions, with no sandbox between it and your machine. This document describes what Sightline does
to keep that under your control, and where the limits are.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Email **support@cxk.co.za** with a description, the affected version, and steps to reproduce. Put
"Sightline security" in the subject so it isn't triaged as an ordinary support request.

I will acknowledge within 7 days and aim to have a fix or a plan within 30. I will credit you unless
you'd rather I didn't.

This is a personal open-source project with no bug bounty. It is also a beta — if you find something
serious, telling me privately first genuinely helps the people using it.

### Especially worth reporting

- Anything that makes the IDE bridge reachable from another machine, or usable without the token.
- A path that escapes `PathAccessPolicy` — reading or writing outside the project without a prompt.
- A command that reaches the device gate (`AndroidActionPolicy`) classified as safe when it destroys data.
- A secret surviving `LogcatRedactor` or `HealthSanitizer` in a realistic log line.
- Anything writing outside `.sightline/` or the IDE's own settings.

## The trust model, stated plainly

Sightline is a **client for a CLI you already trust**. If you have installed and authenticated Claude
Code, that CLI can already read your project and run commands. Sightline does not widen that; it makes
it visible and interruptible.

What Sightline adds on top:

| Control | What it does |
|---|---|
| Permission modes | Choose whether every tool call is approved, only commands are, or nothing is. See [docs/PERMISSIONS.md](docs/PERMISSIONS.md) |
| Approval cards | Inline Allow / Allow-always / Deny before a tool runs |
| Diff review | See a change rendered as a diff before accepting it |
| Path guard | Refuses sensitive locations outright; confirms writes outside the project |
| Device action gate | Confirms anything that destroys app data, whatever the permission mode |
| Logcat redaction | Strips credentials and personal data before a log can reach a prompt |
| Activity Map | Shows what was actually touched, so a surprise is visible |

## The IDE bridge

Sightline runs a WebSocket server so the CLI can ask about your editor.

- **Loopback only.** Bound to `127.0.0.1` on an ephemeral port. Not reachable from another machine.
- **Authenticated.** A 16-byte random token is generated per session and required in the handshake;
  a connection without it is closed immediately.
- **Discoverable only locally.** The port and token go in `~/.claude/ide/<port>.lock`, which is
  created with owner-only read permission.
- **Nothing else listens.** Sightline opens no other socket and makes no outbound requests of its own.

Known limitation: any process running as **your user** can read that lock file and connect. That is the
same trust boundary the official Claude Code IDE integrations use, but it is a real boundary — a
hostile process already running as you can reach the bridge. It cannot be closed without a
fundamentally different design.

## Path access

`ide/PathAccessPolicy` guards every path-taking bridge tool (`openFile`, `openDiff`, `saveDocument`).
Paths are canonicalised first — resolving `.`, `..` and symlinks — so a traversal string cannot slip
past by spelling, and are then classified:

- **Sensitive → refused outright**, whatever the permission mode: `.ssh`, `.aws`, `.gnupg`, `.gcloud`,
  `.kube`, `.docker`, `.claude`, `.password-store`, any `.env*`, `.netrc`, `.git-credentials`, private
  keys, `.npmrc`, `.pypirc`, `.idea`, `.git`, and dotfiles sitting directly in `$HOME`.
- **Outside the project → an explicit confirmation** showing the full external path.
- **Inside the project → the permission mode applies.**

A path that cannot be resolved at all is treated as sensitive, not as safe.

## Command execution

Commands run through Claude Code, not through Sightline, so they are governed by the permission mode
you chose. Sightline's own addition is the **device gate**: `android/AndroidActionPolicy` classifies
every `adb` invocation as read-only, mutating, or destructive, and destructive actions are confirmed
regardless of mode.

Three properties are worth knowing because they are what make a gate a gate:

- A **chained** command takes the worst risk of its segments, so `adb devices && adb uninstall x` is
  not a device listing.
- A device flag cannot hide the sub-command: `adb -s <serial> shell pm clear x` still classifies on
  `pm clear`.
- **Anything unrecognised is treated as destructive**, including a quoted `adb shell '…'`. The cost of
  an extra prompt is a click; the cost of a missed wipe is your afternoon.

`bypassPermissions` mode disables the approval prompts. It does not disable the device gate, and it is
labelled dangerous in the UI for a reason.

## What is written to disk

Only settings, and — if you turn it on — a small Android cache under `.sightline/` in the project.
There is no transcript persistence. `android/AndroidStorePolicy` enforces the cache's rules in code:
workspace-relative paths only, versioned schema (an unknown version is discarded, not migrated), a
retention cap, and a backstop that refuses to write anything credential-shaped or containing a home
path. See [PRIVACY.md](PRIVACY.md).

## Logging

Failures are logged as metadata — event type, tool name, error class, versions — never source
contents, prompt text, model output, or credentials. Command lines that could carry a package name or
device serial are logged by exception class rather than verbatim.

The Health dialog's **Copy report** runs through `HealthSanitizer`, which removes tokens, keys, your
home path, username, email addresses and IP addresses so a report is safe to paste publicly. It is
heavily unit-tested, and a leak there would be a security bug worth reporting.

## Dependencies

Sightline redistributes exactly two libraries — Gson and Java-WebSocket — plus one annotations jar
that arrives transitively. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Keeping that set small
is deliberate: every bundled jar is attack surface shipped to every user.

Updates are applied when a security advisory affects them, and the bundled set is checked against the
built artifact whenever it changes.

## Supported versions

During beta, only the **latest release** receives security fixes. There are no long-term support
branches, and there won't be until there is a 1.0.

## Verification

The artifact is checked with the IntelliJ Plugin Verifier before each release — `tools/verify-plugin.sh`
(not `./gradlew verifyPlugin`; see the script's header for why). As of 0.1.0-beta it reports
**Compatible** against IC-253.28294.334 with zero compatibility problems.
