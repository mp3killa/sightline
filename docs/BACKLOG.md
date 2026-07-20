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

## M8 — File-reference context menu

Hover actions landed for assistant messages (Copy), commands (Copy command / Copy output) and edits
(Open file / Copy diff), but **not** for an inline file reference in prose. Clicking one already opens
the file, so this is additive: a right-click menu offering *Open* and *Reveal in Project*. Deferred from
M4 rather than dropped — inline links are styled ranges inside a text pane, not components, so they
can't host a hover row the way a block can, and `BlockRenderer` only receives an open-link callback.
Needs a popup-trigger listener plus a widened renderer callback.

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
