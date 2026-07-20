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
  **Found 2026-07-20 while generating the listing screenshots:** on a settled 9-node graph, **Fit
  leaves the rightmost node's label clipped** at the canvas edge. `MapDensity.fitPadding` pads for
  labels by density, but `LabelPlacement` offers the *right* slot first, so the outermost node on the
  right routinely overflows. Evidence: `build/marketplace/03-activity-map.png`. Low severity — the
  label returns on hover and the node is intact — but it is the first thing an eye lands on in a
  screenshot. Fix by padding asymmetrically for the widest right-placed label, or by biasing the
  outermost nodes' labels inward.
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

Repository scaffolding is done: proprietary `LICENSE` (EULA), `THIRD_PARTY_NOTICES.md` + `licenses/`,
`PRIVACY.md`, `SECURITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `docs/DATA-FLOW.md`,
`docs/PERMISSIONS.md`. Plugin identity is `io.mp.sightline` / **Sightline** / `0.1.0-beta`, and the
listing description, change-notes, copyright line and independence disclaimer are in `plugin.xml`.

**Licence settled 2026-07-20: source-available** (`LICENSE` = Sightline Source-Available Licence v1.0).
Not open source, so the Marketplace OSS route and its public-source-link requirement do not apply — but
a Developer EULA does, and `LICENSE` is it. Publishing the repo is now a *choice* rather than an
obligation, and the licence is written to work either way:

**Still to do, and all of it needs a human:**

1. ~~**Settle the Anthropic position.**~~ **Parked 2026-07-20 (user decision).** Their docs were
   reviewed and say nothing that restricts a wrapper of this shape; if anything they encourage
   integrations. The draft in `docs/ANTHROPIC-CLARIFICATION.md` is kept unsent in case the position
   changes or the listing draws a query. The wording rule stands regardless, because it is accurate:
   describe Sightline as *requiring a user-managed Claude Code installation*, never as *providing
   Claude access*.
2. **Vendor profile + trader status.** The Vendor ID cannot be changed later. Do not tick non-trader
   reflexively — JetBrains does not decide it on whether money changes hands, and this plugin is close
   to professional work. Trader contact details are shown publicly, so use a dedicated support address,
   not a personal inbox.
3. **Supply the licence as the Developer EULA during submission.** `LICENSE` is written to serve as
   one; JetBrains is explicitly not a party to it (clause 18). Do **not** select an open-source licence
   option on the listing — source-available is not open source, and mislabelling it is both inaccurate
   and a plausible review rejection.
4. **Decide whether to make the repository public.** The licence supports it, and several documents
   already invite the reader to audit named source files — which is only honest while the source is
   actually readable. If it stays private, `PRIVACY.md`, `SECURITY.md` and `docs/DATA-FLOW.md` must
   revert to behavioural checks only (the wording for that is in the 2026-07-20 history if needed).
4. ~~**A Marketplace icon**~~ — **done.** `META-INF/pluginIcon.svg` (+ `_dark`) is an aperture formed
   from graph nodes with one highlighted line of sight, built as geometry rather than traced so it
   holds up at 16px and at listing size. The tool-window stripe icon (`icons/sightline.svg`) is a
   deliberately reduced version — twenty nodes smudge at 13px, so it keeps only what carries the
   identity: the lens silhouette, the pupil, and the sight line. The accent token was retoned from the
   old warm orange to the icon's teal; that orange was close enough to Anthropic's palette to read as
   their branding on a plugin that is explicitly not theirs.
5. ~~**Four screenshots**~~ — **generated.** `./gradlew test --tests "*MarketplaceScreenshotTest*"
   --rerun-tasks` writes `build/marketplace/0{1..4}-*.png` at 1280×800, driven through the production
   event path so they show what the plugin actually renders. Content is a **fictional** app
   (`com.example.routes`) — a guard test asserts no real project, client, path or address appears,
   because a listing image is public permanently and cannot be quietly corrected later. Regenerate
   after any UI change; **look at them** before uploading.
6. **The compatibility claim.** The listing should say *tested on Android Studio*; it is verified
   compatible with IntelliJ IDEA 2025.3 but has not been exercised there (see the plain-IDEA item above).
