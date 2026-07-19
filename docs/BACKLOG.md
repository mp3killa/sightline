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
own tool window. Verify:

- CLI missing / unauthenticated / outdated. New & existing Android projects. Indexing. Kotlin & Java.
- Read-only files, unsaved docs, file create / delete. Diff accepted / rejected. Tool denied.
- Claude stopped mid-tool. AS closed mid-session. Multiple projects open. Light & dark themes.
- All five permission modes. Stale IDE lock-file cleanup + reconnection after unexpected shutdown.
- Whether `can_use_tool`'s ApprovalBlock **and** `openDiff`'s Accept/Reject both fire for one edit (the
  double-prompt question — reproduce before any approval-flow refactor).
- `IdeServer.onEdt` uses `invokeAndWait` from the WS thread while a **modal** diff dialog is open — check
  for deadlock / UI-block.
- **Markdown rendering (needs eyes):** headings (no `#`), real tables, lists (incl. task lists), fenced
  code (Copy + horizontal scroll), quotes, `> [!WARNING]` callouts, inline styles, and clickable project
  file links all render in light + dark; the turn footer shows cost/duration/turns and the status strip
  never echoes the response; malformed/half-streamed Markdown stays readable; auto-scroll follows only at
  the bottom (scrolling up pauses it, sending re-follows).
- **Keyboard a11y (needs eyes):** Tab reaches the Chat/Split/Map switch (`SegmentedControl` arrows + split
  `JButton`), the activity-map canvas (arrow to move, Enter to open, Esc to clear) and the inspector (Esc
  clears from anywhere in the drawer). Confirm nothing traps focus. Logic is in place; only the live pass remains.
- **AskUserQuestion visual click-through:** render (radio / checkbox / Other), Continue-gating,
  Cancel-denies, "Skip"-as-answer, and the returned `answers` object. Logic is unit-tested and the bridge
  can drive it (`runIde -PtestBridge` + `sightline.test.simulate_question` → `respond_question`); only the
  **visual** render still needs eyes.

## Marketplace listing submission

Naming (Sightline), plugin icon, `<vendor>`, `<description>`, `<change-notes>`, and the "requires the
Claude CLI" wording are all in place — the remaining step is actually submitting the listing.

---

# P1 differentiators — remaining

The bulk of the P1 tail has shipped (see [../CLAUDE.md](../CLAUDE.md)); only these remain:

## PSI enrichment

- Android **resource → referencing source** (the reverse lookup, via the resource/reference index — live
  only; the forward nav-graph → destination direction already ships).
- Lazy tiers: references/usages on select → call relationships only on explicit "Calls" / blast-radius.
- A Kotlin `BasePlatformTestCase` (needs the Kotlin plugin on the test classpath) — Kotlin uses the same
  UAST code path, currently only exercised live in the sandbox. Java is covered (imports, hierarchy, and
  nav-destination resolution).

## Cluster collapsing (renderer polish)

- The fold logic + toggle ship and the header "X of Y" count reflects folded history. Remaining is
  renderer polish: draw the aggregates as **clickable chips** that expand in place (`Plan.expanded`
  already supports it) and the fuller tier progression (hide low-value labels → collapse historical by
  category → "Show more" → better Fit). Minimap only if navigation is still hard — Fit + node caps + the
  new fold mean it isn't the bottleneck.

---

# Chat transcript rendering — remaining polish

The Markdown renderer overhaul shipped (phases A–3, see [../CLAUDE.md](../CLAUDE.md)): the status-echo fix
+ turn footer, the platform-free `ui/markdown/` parser → model → component pipeline
(headings/lists/tables/code/quotes/callouts + inline styles), project-file links, and the scroll-follow
guard. What's left is optional polish:

- **Syntax highlighting** in fenced code — only if it can reuse IntelliJ editor/highlighter APIs safely;
  don't block on it.
- **Code block** height cap + Expand/Collapse for very long blocks (today: full height, horizontal scroll).
- **Table** horizontal scroll for very wide tables (today: cells wrap to the available width).
- **"Jump to latest"** affordance when auto-follow is paused (the scroll guard ships; the button doesn't).

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
