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

**Activity map, also confirmed live 2026-07-20** (Activity tab, same session, 18 nodes / 21 events): the
force-directed graph renders with category cluster nodes (Documentation, Errors / Warnings, Shell / Command,
Gradle / Build, Android Framework, Unknown / Unclassified) and file/command nodes; the **focus card**
("COMPLETED · Added three self-contained de…"); the **filter combo** ("All activity"), **Fit** and overflow
controls; the node counter ("18 nodes"); the **collapsed timeline dock** with its summary ("Activity log ·
21 events · Latest: …"); and the **inspector drawer** showing Category / Interactions / Last active plus
pin and hide actions. Two behaviours worth calling out as confirmed end-to-end:

- **Exit-status correlation** — a non-zero exit with no parseable failure line still produced a real
  `Exit code 1` error node (`Error · failed`), drawn with the error ring.
- **Evidence provenance with `COMMAND_OUTPUT`** — the inspector's *Related* list reads
  "cd /Users/devuser/AndroidStudioProjects… **produced an error · command output**", i.e. the `PRODUCED`
  edge carrying its evidence source and human explanation, exactly as designed.

> [!NOTE]
> Neither session tells us anything about the 2026-07-20 chat-polish or map-density commits. The chat
> screenshot showed no code fence or table; the map screenshot has **18 nodes**, which is below
> `MapDensity.IMPORTANT_ABOVE` (40), so the tier is `ALL` — which renders *identically* to the old
> behaviour. Both are still unverified.

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
- **Activity map — chrome confirmed** (see above); the *features* still need eyes: a touched **resource**
  linking to its referencing sources; the inspector **"Find usages"** action adding usage edges (the
  confirmed shot had an error node selected, where that action correctly does not appear — select a
  **source** node); and **"Collapse finished history"** folding clusters with the "N commands" chips
  expanding/collapsing in place.
- **Label collision (needs eyes):** the fix is verified in the headless preview (regenerate with
  `./gradlew test`, then open `build/activity-map-preview-{dark,light}.png` — no label overprints another).
  Live, confirm the same on a busy graph, that a label withheld in a crowded neighbourhood **comes back on
  hover**, and that labels don't visibly flip sides or flicker while the layout is still settling.
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
- **Health panel (needs eyes):** **More ▸ Health check…** opens the dialog; it shows a brief "Checking…"
  then a row per check with the right colour/glyph; **Recheck** re-runs (try it mid-indexing → diagnostics
  should WARN, then OK once indexed); **Open settings** opens Sightline settings; **Copy report** puts a
  **sanitised** report on the clipboard — paste it and confirm no home path, username, email or token
  survives. Sanitiser logic is heavily unit-tested; this pass is for the render + the copy round-trip.

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
