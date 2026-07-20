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

## ~~Run `verifyPlugin` on CI~~ — DONE (2026-07-20)

**Result: `io.mp.sightline:0.1.0-beta` against `IC-253.28294.334` — Compatible. 0 compatibility problems.**

Run it with **`tools/verify-plugin.sh`**, not `./gradlew verifyPlugin`. IPGP 2.6.0 resolves the IDE
under `idea:ideaIC:<v>` (group `idea`), which does not exist — the artifact is at
`com.jetbrains.intellij.idea:ideaIC:<v>`. Both `select { }` and `ide(...)` hit the same wrong group, so
the Gradle task fails before the verifier starts. The script downloads from the correct coordinate and
runs the same verifier CLI. Re-run it before every release; it caches the IDE, so only the first is slow.

One real finding, fixed: `StudioFactProvider` referenced `com.android.sdklib.AndroidVersion` (via
`AndroidModel.getMinSdkVersion()`), which is unresolvable against a plain IC. The code path never runs
there, but a reported problem on a submission is a real cost — and tier 3 already parses min/target/
compile SDK out of the build file. Dropping those three reads removed the finding *and* shrank the
internal-API surface, which is what the narrow-interface rule asks for anyway.

The 10 remaining findings are all `ToolWindowFactory` interface members Kotlin materialises for any
implementor (`isApplicable`, `isDoNotActivateOnStart`, `getIcon`, `getAnchor`, `manage`). Informational,
and not avoidable without abandoning the interface. `ProcessAdapter` → `ProcessListener` was fixed.

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

- **First-run disclosure**: on a fresh install, the notice appears **before** the first message is sent
  and before **Catch up on this project** (both send paths are gated). Closing it without acknowledging
  must *cancel* the send, not proceed. Confirm the mode-specific sentence changes with the mode chip,
  and that switching to **Unrestricted** shows the blunt wording in the warning colour. Then confirm it
  never appears again, including after an upgrade.

## Marketplace listing submission

Repository scaffolding is done: Apache-2.0 `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`,
`SECURITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `docs/DATA-FLOW.md`,
`docs/PERMISSIONS.md`. Plugin identity is `io.mp.sightline` / **Sightline** / `0.1.0-beta`, and the
listing description, change-notes and independence disclaimer are in `plugin.xml`.

**Still to do, and all of it needs a human:**

1. **Settle the Anthropic position.** This is the one blocker worth resolving before a *public* listing.
   Sightline launches a user-installed, user-authenticated CLI — it has no login screen, receives no
   OAuth tokens, proxies no API traffic and runs no backend — but Anthropic's third-party guidance is
   written around API-key integrations, so the case is adjacent rather than identical. Ask them directly
   and get it in writing. A draft is in `docs/ANTHROPIC-CLARIFICATION.md`. Until it is answered, describe
   Sightline as *requiring a user-managed Claude Code installation*, never as *providing Claude access*.
2. **Vendor profile + trader status.** The Vendor ID cannot be changed later. Do not tick non-trader
   reflexively — JetBrains does not decide it on whether money changes hands, and this plugin is close
   to professional work. Trader contact details are shown publicly, so use a dedicated support address,
   not a personal inbox.
3. **A public repository**, with the issue tracker enabled. An OSS-licensed listing must link to real
   source. Replace the `OWNER/sightline` placeholders in `CHANGELOG.md` and `README.md` once it exists.
4. **A Marketplace icon** — 40×40 SVG, distinct from JetBrains, Anthropic and Google marks. Concept:
   an aperture or eye formed from graph nodes with one highlighted line of sight.
5. **Four screenshots**, ≥1200×760: chat with Markdown, an AskUserQuestion card, the Activity Map with
   real relationships, and a diff/approval. Scrub secrets, private repo names, emails and machine paths
   before uploading — the screenshots are the one place where a leak is permanent and public.
6. **The compatibility claim.** The listing should say *tested on Android Studio*; it is verified
   compatible with IntelliJ IDEA 2025.3 but has not been exercised there (see the plain-IDEA item above).
