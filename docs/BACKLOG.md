# Backlog

**Only remaining work.** Anything built is deleted from this file rather than annotated, so the list
stays honest and short. Decisions that outlive their backlog item — what was rejected and why — move to
the **Standing decisions** section of [../CLAUDE.md](../CLAUDE.md) so they can't be silently re-litigated;
see there for what exists, and [PROTOCOL.md](PROTOCOL.md) for CLI facts.

Everything below is a **release gate**: the feature work is done.

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

**This gate got more important with the Android work.** `ide/android/studio/StudioFactProvider` calls
`com.android.tools.idea.*` — Android-Studio-internal API, and exactly what the verifier is for. The
design already assumes it may break (any failure degrades to tier 2; the class only loads via the
optional `META-INF/sightline-android.xml`), but the verifier is what turns "we think it's isolated" into
a checked fact. Two things to confirm specifically:
- The verifier is **not** confused by the optional `<depends>` — a plain IC has no `org.jetbrains.android`,
  so the descriptor and its implementation class should simply be skipped rather than reported missing.
- Nothing outside `ide/android/studio/` references an Android-Studio-only class. That is the whole
  isolation claim of docs/ANDROID.md §1.1, and a stray import elsewhere would silently break plain-IDEA
  installs while still compiling here.

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
  *failed* node arguably deserves anchor status. Decide during the live pass, with a busy graph in
  front of you — it's a judgement about what the eye needs at density, not something to settle on paper.
- **AskUserQuestion interaction**: **Other…** free-text actually accepting input, Continue **enabling**
  once every question is answered, **Cancel** genuinely denying and unblocking the turn, and a
  "Skip"-style option coming back as a normal answer. The `answers`-keyed-by-full-question-text contract
  is already unit-tested (`AskUserQuestionResponseBuilderTest`); the bridge drives the non-visual half
  (`runIde -PtestBridge` + `sightline.test.simulate_question` → `respond_question`).
- **Android rows in Health** (docs/ANDROID.md M0): open **More ▸ Health check…** in a real Android project
  and confirm the SDK / Devices / Build variant rows read honestly across the states that matter —
  **no device connected** (WARN, "start an emulator"), a device present but **unauthorised** (WARN,
  pointing at the on-device prompt), and `adb` present but wedged (UNKNOWN, *not* "0 devices"; force it
  with `adb kill-server` mid-check). Then open a **non-Android** project and confirm the three rows are
  absent entirely rather than failing. The SDK path goes through the sanitiser, so **Copy report** must
  show it as `~/Library/Android/sdk`, never with the username.
- **Plain IntelliJ IDEA degradation** — the core claim of docs/ANDROID.md §1.1. Install the built zip in an
  IntelliJ IDEA **without** the Android plugin: the plugin must load with no errors, and the "Build variant"
  Health row must read WARN ("expected outside Android Studio"), never FAIL or a stack trace. This is the
  one check the dev environment structurally cannot do — the local platform *is* Android Studio.
- **Health panel**: **More ▸ Health check…** opens the dialog; a brief "Checking…" then a row per check;
  **Recheck** re-runs (try it mid-indexing → diagnostics should WARN, then OK once indexed); **Open
  settings** opens Sightline settings; **Copy report** puts a **sanitised** report on the clipboard —
  paste it and confirm no home path, username, email or token survives. The sanitiser is heavily
  unit-tested; this pass is the copy round-trip.

## Marketplace listing submission

Naming (Sightline), plugin icon, `<vendor>`, `<description>`, `<change-notes>`, and the "requires the
Claude CLI" wording are all in place — the remaining step is actually submitting the listing.
