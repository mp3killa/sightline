# Backlog

**Only remaining work** — anything already built has been removed to keep this list honest. See
[../CLAUDE.md](../CLAUDE.md) for what exists and [PROTOCOL.md](PROTOCOL.md) for CLI facts.

Guiding principle: correctness logic lands as **platform-free, unit-tested** classes (mirroring
`activity/`, `ui/state/`, `interaction/`), with thin Swing on top.

---

# Release gates (before the Marketplace listing)

## Run `verifyPlugin` on CI

Config is correct (`sinceBuild=253`, targets IntelliJ IDEA Community `253.*`), but it can't run in the dev
env: IPGP 2.6.0 mis-resolves the IC distribution coordinate (looks up `idea:ideaIC:<v>` while the ZIP
lives at `com.jetbrains.intellij.idea:ideaIC:<v>` — the ZIP exists + downloads), and `local(AndroidStudio)`
collides with the compile-time platform dependency. Run it where the IC distribution resolves (CI / a
newer IPGP) and fix anything it flags (internal/experimental API usage, binary compat across the range).

## Live Android Studio verification (manual)

The interactive flows need a human pass in `./gradlew runIde` — the studio MCP can't click the plugin's
own tool window.

**Confirmed live 2026-07-20** from a real dark-theme session (installed build, "My Application"), so these
need no second pass: headings render without `#`; bullet lists; inline **bold** and `code`; **clickable
project-file links** resolving real docs (`CLAUDE.md`, `docs/ARCHITECTURE.md`, …); the per-turn **footer**
carrying cost/duration/turns (`Completed · 32.6s · 9 turns · $0.2229`); the **status strip never echoing
the response** (it read "Waiting for your answer" while a question was pending); user turns as rounded
bubbles; the collapsed **thinking** row; the header (wordmark, state dot, Chat/Activity segmented control,
split toggle); the composer (attach, slash, `Auto` mode chip, stop button). For AskUserQuestion: the
**radio** single-select render with per-option descriptions, the **Other…** row, **Continue disabled until
answered**, Cancel present, and the streamed tool card reading **`Asked · Demo location`** rather than raw
JSON.

> [!NOTE]
> That session predates (or may predate) the 2026-07-20 chat-polish and map-density commits, and showed no
> code fence, table or activity map — so it says nothing either way about that work. Everything below still
> needs the pass.

Verify:

- CLI missing / unauthenticated / outdated. New & existing Android projects. Indexing. Kotlin & Java.
- Read-only files, unsaved docs, file create / delete. Diff accepted / rejected. Tool denied.
- Claude stopped mid-tool. AS closed mid-session. Multiple projects open. Light & dark themes.
- All five permission modes. Stale IDE lock-file cleanup + reconnection after unexpected shutdown.
- Whether `can_use_tool`'s ApprovalBlock **and** `openDiff`'s Accept/Reject both fire for one edit (the
  double-prompt question — reproduce before any approval-flow refactor).
- `IdeServer.onEdt` uses `invokeAndWait` from the WS thread while a **modal** diff dialog is open — check
  for deadlock / UI-block.
- **Markdown rendering — partly confirmed** (dark theme, live session 2026-07-20; see the confirmed list
  above). Still needs eyes: real **tables**, **fenced code** (Copy + horizontal scroll), **quotes**,
  `> [!WARNING]` **callouts**, **task lists**, the whole set again in **light theme**,
  malformed/half-streamed Markdown staying readable, and auto-scroll following only at the bottom
  (scrolling up pauses it, sending re-follows).
- **Chat polish (needs eyes):** fenced code is **syntax-highlighted** in the IDE's own colours and stays
  legible in both themes (an unknown/unlexable fence must fall back to plain monospace, not garble);
  a >24-line block renders capped with a working **Expand/Collapse** and Copy still yields the whole text;
  a wide table **scrolls horizontally** rather than squeezing its columns; the **"Jump to latest ↓"** overlay
  appears only when follow is paused, doesn't cover the last line of text, and re-arms follow when clicked.
- **Keyboard a11y (needs eyes):** Tab reaches the Chat/Split/Map switch (`SegmentedControl` arrows + split
  `JButton`), the activity-map canvas (arrow to move, Enter to open, Esc to clear) and the inspector (Esc
  clears from anywhere in the drawer). Confirm nothing traps focus. Logic is in place; only the live pass remains.
- **Activity map (needs eyes):** a touched resource links to its referencing sources; the inspector
  "Find usages" action adds usage edges; "Collapse finished history" folds clusters and the "N commands"
  chips expand/collapse in place. Logic is unit-tested; only the visual behaviour needs eyes.
- **Map density (needs eyes):** on a genuinely busy session, labels thin out as the graph grows without
  the map flickering between tiers as nodes arrive; errors, anchors and the hovered/selected node keep
  their labels at every density; zooming in restores detail; the **"N of M · Show more"** counter is
  clickable and actually reveals more nodes; **Fit** frames the bulk of the graph (a single stray node
  must not shrink everything to a speck) and leaves room for edge labels.
- **AskUserQuestion — render partly confirmed** (dark theme, live session 2026-07-20). Still needs eyes:
  the **multi-select checkbox** variant, **Other…** free-text actually accepting input, Continue
  **enabling** once every question is answered, **Cancel** genuinely denying and unblocking the turn,
  a "Skip"-style option coming back as a normal answer, and the returned `answers` object being keyed by
  full question text. The bridge can drive the non-visual half
  (`runIde -PtestBridge` + `sightline.test.simulate_question` → `respond_question`).

## Marketplace listing submission

Naming (Sightline), plugin icon, `<vendor>`, `<description>`, `<change-notes>`, and the "requires the
Claude CLI" wording are all in place — the remaining step is actually submitting the listing.

---

# P2 — after launch

## Phase 2b — on-demand deep relationships (0.3)

Deferred because false claims / graph explosions concentrate here: full call graphs, broad
`ReferencesSearch`, ViewModel→Composable / Repository→service / UseCase→Repository inference, automated
pattern detection (built on the shipped evidence model). Focus-follows-agent (default on; suspend on any
user pan/zoom/drag/select/inspector; small "Resume following"). Advanced keyboard: arrow-key spatial nav,
search-driven selection, next/prev active event.

## Timeline replay & persistence (0.4)

Append-only event log; replay builds a **separate** graph state up to a selected sequence — never
mutates the live graph ("N new events · Return to live"). Introduce the Freeze / Stop-following / Replay
distinction the pause button hints at.

```kotlin
data class RecordedActivityEvent(val schemaVersion: Int, val sequence: Long,
    val timestamp: Instant, val sessionId: String, val event: AgentActivityEvent)
```

Persistence: workspace-relative paths only; **never** absolute paths, source contents, prompts, or
reasoning; versioned schema; max session count + retention; delete-one / clear-all. Default **off** in
beta (current session in memory); opt-in retention of the last ~10 sessions.

## Health / preflight panel

Small diagnostic screen: Claude CLI found + version, auth, IDE bridge, workspace, permission mode, AS
version, activity events, diagnostics availability. Actions: Recheck, Open settings, Copy sanitised
report, Open logs. High support-cost saver for early users.
