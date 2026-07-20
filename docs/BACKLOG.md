# Backlog

**Only remaining work** — anything already built has been removed to keep this list honest. See
[../CLAUDE.md](../CLAUDE.md) for what exists and [PROTOCOL.md](PROTOCOL.md) for CLI facts.

Guiding principle: correctness logic lands as **platform-free, unit-tested** classes (mirroring
`activity/`, `ui/state/`, `interaction/`), with thin Swing on top.

---

# Chat experience — VS Code-parity polish

From a 2026-07-20 team/GPT review: the Chat view reads as an *execution dashboard* rather than a coding
conversation, because execution telemetry competes with the reply for visual weight. Milestones are
ordered so each is independently shippable and can be **deleted from this file on completion**.

Three asks from that review were assessed and **rejected** — do not re-import them:

- *"Existing stored sessions must still open"* — there is **no session persistence**; `lastSessionId`
  is in-memory and `--resume` exists only to survive a user Stop. Building persistence to satisfy an
  acceptance criterion would pre-empt the P2 design below (which has deliberate privacy constraints).
- *"1,100–1,250px max content width"* — that figure is for a full-screen editor window. A docked tool
  window is typically 400–700px, so the cap would never engage. The existing 760 stays.
- *"Virtualise the transcript"* — justified by the premise that the tree is rebuilt per streamed token.
  It is not: streaming is a single `insertString` into one reused pane, and the tree rebuilds once per
  block. The real cost is unbounded turn retention → **M6**.

## M2 — Compact tool-activity rows

The root cause of "equally weighted panels" is that `showDetails` is binary: either **no** tool cards
or **every** tool call at full card weight, where `ToolCard.paintComponent` draws a filled rounded rect
plus border unconditionally — the same recipe as the user message bubble. A successful `Read` is as
loud as your own prompt; success vs failure differ only by a 13px icon.

Add the missing middle tier. Routine successful operations (Read/Grep/Glob, and commands that exited 0)
render as a single compact row — icon, verb, target, state, optional duration, disclosure chevron — with
no border or fill. Failures and warnings keep stronger treatment. Expanded output stays behind disclosure.

Land the decision as a platform-free `ui/state` class (e.g. `ToolEventPresentation`) taking **structured**
metadata (tool name, error flag, exit status) — never a display string — and returning a presentation
tier. Unit-test the tiering; keep the Swing half thin.

## M3 — File edits as first-class blocks

Edits deserve more weight than reads, and today have less structure than either:

- No edit header, no added/removed line counts (nothing consumes `lineDiff`'s output beyond the loop).
- `MultiEdit` concatenates every edit into one document with **no separator or per-edit header**.
- **Diffs are never truncated** — `truncate()` applies only to results, so `Write` renders an entire new
  file inline as all-adds. Fix this first; it is a real perf/usability defect, not polish.
- No collapse, no "Open file", no "Copy diff".
- Unified only, with no width-responsive selection (side-by-side where wide, unified when narrow).
- Diff colours are fixed `JBColor` pairs (`ClaudePanel.kt` ~line 402) rather than the editor's own
  `DiffColors`/`EditorColors` scheme keys, so they don't track a custom theme. The scheme is already
  consulted for fonts and syntax highlighting — extend that.

Responsive diff selection is pure logic → `ui/state`, unit-tested.

## M4 — Processing summary + hover actions

- Collapse "Processing details" into a compact expandable summary once meaningful content begins —
  e.g. *"17 operations · 4 files edited · 3 checks passed"*. Nothing counts operations today;
  `CompletionSummary` covers only the cost/duration/turns footer. Extend it or add a sibling.
- Hover/focus-revealed actions, which currently do not exist anywhere in the transcript (the only Copy
  is the always-visible one on code fences): Copy on an assistant message; Copy command / Copy output on
  a command; Open file / Reveal in Project on a file reference.

## M5 — Activity map ↔ chat linking

`ActivityMapPanel` has no outbound callback at all — selecting a node opens the file in the editor and
never touches the transcript, and there is no chat→node path. Add a bidirectional link (select a node →
reveal the originating transcript event, and optionally the reverse). This is what earns the graph its
place as an *inspection surface* rather than a second, competing view. Note that with details on, tool
activity is currently rendered twice (transcript cards **and** the timeline dock) — resolve that overlap
here.

## M6 — Long-session cost

- **Unbounded turn retention**: `turns` is appended to and never pruned (contrast the graph, which has
  `activityMaxRetained`). Add an eviction cap with a "load earlier" affordance.
- **Paragraph wrap clipping — reproduces headlessly, not yet confirmed live.** In
  `chat-layout-medium.png` (720px) an assistant paragraph renders as a single line with the trailing
  text cut (`…the guard existed but` — "was dead code." missing) instead of wrapping. Consistent across
  regenerations, and the same text wraps fine at 1400px. Suspicious rather than proven: a wrapping
  `JTextPane`'s preferred height depends on a width the detached tree may not have settled — though the
  diff pane in the same image *does* wrap, which argues against a pure harness artifact. Reproduce in
  `runIde` at a ~720px tool window before chasing it.

## M7 — Composer: queue while running

Today `doSend` hard-returns while running and `sendEnabled = !running && ...`, but the input is never
disabled — so a user can type a full message, press Enter, and have **nothing happen, with no feedback**.
Fix the silent failure first (cheap), then decide whether to queue the message or explicitly block it.
stdin is writable mid-turn (it already carries `control_response`), so queueing is feasible; it needs a
deliberate "send now" vs "queue next" distinction.

---

# Release gates (before the Marketplace listing)

## Run `verifyPlugin` on CI

Config is correct (`sinceBuild=253`, targets IntelliJ IDEA Community `253.*`), but it can't run in the dev
env: IPGP 2.6.0 mis-resolves the IC distribution coordinate (looks up `idea:ideaIC:<v>` while the ZIP
lives at `com.jetbrains.intellij.idea:ideaIC:<v>` — the ZIP exists + downloads), and `local(AndroidStudio)`
collides with the compile-time platform dependency. Run it where the IC distribution resolves (CI / a
newer IPGP) and fix anything it flags (internal/experimental API usage, binary compat across the range).

## Live Android Studio verification (manual)

Only what genuinely needs a human is listed. Static rendering — every Markdown block type, tool cards,
diffs, the approval card, both AskUserQuestion variants, panel layout at each width class, and map label
density — is covered by the headless PNG harnesses described in [TESTING.md](TESTING.md); read those
images instead of re-checking any of it by hand. What remains needs a **click, hover, focus traversal,
drag, scroll, clipboard round-trip, or a live CLI session** — none of which the `studio` MCP can drive,
since it has no screenshot tool and cannot see this plugin's tool window.

Verify:

- CLI missing / unauthenticated / outdated. New & existing Android projects. Indexing. Kotlin & Java.
- Read-only files, unsaved docs, file create / delete. Diff accepted / rejected. Tool denied.
- Claude stopped mid-tool. AS closed mid-session. Multiple projects open. Light & dark themes.
- All five permission modes. Stale IDE lock-file cleanup + reconnection after unexpected shutdown.
- Whether `can_use_tool`'s ApprovalBlock **and** `openDiff`'s Accept/Reject both fire for one edit (the
  double-prompt question — reproduce before any approval-flow refactor).
- `IdeServer.onEdt` uses `invokeAndWait` from the WS thread while a **modal** diff dialog is open — check
  for deadlock / UI-block.
- **Streaming Markdown**: malformed / half-streamed Markdown staying readable mid-stream, and auto-scroll
  following only at the bottom (scrolling up pauses it, sending re-follows).
- **"Jump to latest ↓"**: appears only when follow is paused, doesn't cover the last line of text, and
  re-arms follow when clicked. Plus the **Copy** clipboard round-trip on a code fence.
- **Keyboard a11y**: Tab reaches the Chat/Split/Map switch (`SegmentedControl` arrows + split `JButton`),
  the activity-map canvas (arrow to move, Enter to open, Esc to clear) and the inspector (Esc clears from
  anywhere in the drawer). Confirm nothing traps focus.
- **Activity map features**: a touched **resource** linking to its referencing sources; the inspector
  **"Find usages"** action adding usage edges (select a **source** node — it correctly does not appear for
  an error node); and **"Collapse finished history"** folding clusters with the "N commands" chips
  expanding/collapsing in place.
- **Label behaviour in motion**: a label withheld in a crowded neighbourhood **comes back on hover**, and
  labels don't visibly flip sides or flicker while the layout is still settling.
- **Map density in motion**: no **flicker** between tiers as nodes arrive live; **zoom** restoring detail;
  the hovered/selected node keeping its label; the **"N of M · Show more"** counter actually revealing more
  nodes when clicked; **Fit** framing the bulk of the graph rather than shrinking it to a speck around a
  stray node.
  Open design question: failed **command/test** nodes (`build`, `test suite`) lose their labels at the
  IMPORTANT tier while `ERROR`-type nodes keep theirs. That follows the tier rules as written, but a
  *failed* node arguably deserves anchor status — decide before M5.
- **AskUserQuestion interaction**: **Other…** free-text actually accepting input, Continue **enabling**
  once every question is answered, **Cancel** genuinely denying and unblocking the turn, and a
  "Skip"-style option coming back as a normal answer. The `answers`-keyed-by-full-question-text contract
  is already unit-tested (`AskUserQuestionResponseBuilderTest`); the bridge drives the non-visual half
  (`runIde -PtestBridge` + `sightline.test.simulate_question` → `respond_question`).
- **Health panel**: **More ▸ Health check…** opens the dialog; a brief "Checking…" then a row per check;
  **Recheck** re-runs (try it mid-indexing → diagnostics should WARN, then OK once indexed); **Open
  settings** opens Sightline settings; **Copy report** puts a **sanitised** report on the clipboard —
  paste it and confirm no home path, username, email or token survives. The sanitiser is heavily
  unit-tested; this pass is the copy round-trip.

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
