# Sightline for Claude Code — internal notes

**Sightline** (formerly "Claude Code Panel") is a native **Android Studio / IntelliJ plugin** that
wraps the `claude` CLI in a graphical chat panel (message bubbles, collapsible tool cards, diffs,
interactive approval, IDE integration). It drives the CLI over its streaming-JSON protocol and renders
everything in **native Swing**.

**Identity (settled 2026-07-20, pre-publication — the only free moment to change it):** plugin `<id>`
`io.mp.sightline`, Kotlin package `io.mp.sightline`, Marketplace name **Sightline**, artifact
`sightline-<version>.zip`, settings `sightline.xml`, version **0.1.0-beta**. Changing an `<id>` after
publication orphans every install, which is why it was done now. Identifiers the **CLI** consumes are
deliberately untouched — `CLAUDE_CODE_ENTRYPOINT`, the `ide` MCP server name, and its `serverInfo`
name are an integration contract with an external tool, not our branding.

**Licensing (settled 2026-07-20): source-available.** `LICENSE` is the *Sightline Source-Available
Licence v1.0* — SA law, Michael Carroll, support@cxk.co.za. Apache-2.0 was considered and dropped. The
distinctions that matter, because they are easy to blur:
- **Source-available is NOT open source.** Never describe it as open source anywhere — listing, README,
  docs. The licence says so in a banner at the top for that reason.
- **Two different "commercial" questions, and conflating them breaks the licence.** *Using* Sightline at
  work, commercially, on client code is **expressly permitted** — that is the entire audience. What is
  barred is *Commercial Exploitation of the Software or its source*: selling it, redistributing it,
  building a competing plugin from it, running it as a service. Clause 1 defines the difference and
  clause 3 states the permission in bold. Do not let a future edit collapse them.
- **Contributions carry an inbound grant (clause 5)** — broad, irrevocable, sublicensable, contributor
  keeps copyright. Without it a merged patch could not be relicensed or shipped commercially without
  tracing its author. `CONTRIBUTING.md` explains it in plain words.
- **A GitHub fork is carved out (clause 6)** — the platform's own ToS grants view/fork, and a licence
  purporting to forbid what the host permits would be incoherent. A fork grants nothing beyond that.
- **Third-party obligations are unchanged by any of this.** Gson (Apache-2.0) and Java-WebSocket (MIT)
  must have their notices reproduced wherever redistributed; none of the jars ships one, so
  `THIRD_PARTY_NOTICES.md` + `licenses/` are the *only* place that is discharged. Update both in the
  same commit as any dependency change.
- **No `NOTICE` file** — that is an Apache convention; trademark/independence statements live in
  `LICENSE` §18 and the README.
- **Source-readability claims are now legitimate again.** `PRIVACY.md`, `SECURITY.md` and
  `docs/DATA-FLOW.md` point at named files (`LogcatRedactor`, `PathAccessPolicy`, `AndroidActionPolicy`,
  `HealthSanitizer`) and invite the reader to build and check. That is the main *point* of publishing
  the source, and it is only honest while the repo is actually readable — if it ever goes private again,
  those sections must revert to behavioural checks only.

Trust scaffolding at the repo root: `LICENSE`, `THIRD_PARTY_NOTICES.md`, `licenses/`, `PRIVACY.md`,
`SECURITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, plus `docs/DATA-FLOW.md` and
`docs/PERMISSIONS.md`. **Keep them true** — they describe specific code, and a privacy or security claim
that drifts from the implementation is worse than no claim.

## Architecture

| File | Role |
|---|---|
| `ClaudeToolWindowFactory.kt` | Registers the right-dock "Sightline" tool window (`<toolWindow id="Sightline">`) |
| `ui/ClaudePanel.kt` | The whole UI: Swing transcript (per-turn block components), composer, modes popup, `/` actions menu, interactive-approval cards, event rendering. Feeds every observable tool/stream event into the activity map. Assistant text renders through `ui/markdown/` (below). Auto-scroll follows the bottom only while the user is near it (`ScrollFollow`); scrolling up pauses following and floats a **"Jump to latest ↓"** button over the transcript (a `JLayeredPane` overlay, so showing/hiding it never shifts the text being read) which scrolls to the end and re-arms follow. |
| `ui/markdown/*.kt` | Platform-free, unit-tested **Markdown rendering** for assistant messages: `MarkdownModel` (block/inline model), `MarkdownDocParser` (adapter over the platform-bundled `org.intellij.markdown` GFM parser → model; no added dependency; graceful plain-text fallback; explicit `> [!WARNING]` callouts, never inferred), `FileRefDetector` (conservative file-reference detection + a pure `linkify` transform gated by an injected resolver — never guesses; a resolved `file:` link also offers **Open / Reveal in Project** on right-click, and the menu is built only when the host supplied a reveal callback so there is never a dead item), `CodeBlockLayout` (collapse rules for long fences), `TableLayout` (column-width floor → when a wide table scrolls), `CodeLanguages` (fence tag → file extension; a fixed table, so an unknown tag renders plain rather than confidently wrong), `StreamingMarkdown` (live-streaming core: `stablePrefix` — how many leading blocks of a re-parse are structurally identical and keep their components — plus the coalescing tick constants; equality on the data-class model, never position or guesswork). `CodeHighlighting` is the one platform-touching helper: it lexes a fence with the IDE's own `SyntaxHighlighter` + colour scheme (read-only, best-effort — an unlexable fragment degrades to plain monospace). `BlockRenderer` is the thin Swing half: theme-aware components for headings/paragraphs/lists (incl. task lists)/GFM tables (h-scroll when wide)/fenced code (**syntax-highlighted**, Copy, h-scroll, Expand/Collapse past 24 lines)/quotes/callouts, with inline bold/italic/strikethrough/code/links. Replaces the old regex renderer. |
| `ui/ActivityMapPanel.kt` | The **Agent Activity Map**: force-directed Swing graph, current-focus card, node details (open/reveal/pin/hide, plus **Find usages** for a source node → `ProjectStructureEnricher.findUsages`), timeline, filter combo, legend, and pause/reduce-motion/fit/clear controls. Selecting a node fires `onNodeSelected`, which `ClaudePanel` uses to **reveal the transcript row that produced it** (turning details on if they were hidden, then flashing the row); `selectByPath`/`selectByLabel` drive the reverse, from a tool card's **Show in map** hover action. A "Collapse finished history" toggle folds finished command/test/gradle clusters (`ClusterCollapser`) into clickable **"N commands" chips** that expand in place. Density is staged by `MapDensity`: labels thin out as the graph grows (anchors/errors and whatever the user is pointing at always keep theirs), the node counter becomes a clickable **"N of M · Show more"** that lifts the visible cap for the session only, and **Fit** pads for labels and trims far-flung outliers so a couple of stragglers can't shrink the graph to a speck. Renders only; theme-aware colours. |
| `activity/GraphLens.kt` | Graph **modes** as a pair of predicates over existing nodes **and edges** — the old filter was a private Swing enum that filtered nodes only, which cannot express "show me imports". A lens can only *select* from what the graph established; it has no way to construct a node or edge, so it cannot breach the no-unevidenced-relationships rule by accident. |
| `activity/*.kt` | Platform-free, unit-tested core of the map: `AgentActivityEvent` (normalised event model), `ActivityModel` (graph data model — node/edge/category/state/timeline types; `RelationshipEvidence.stronger` ranks/merges evidence), `ActivityInterpreter` (raw tool events → normalised events), `ActivityGraph` (reducer → nodes/edges/clusters/focus/timeline), `ActivityClassifier` (path → cluster), `OutputParsers` (Gradle/compiler/test + adb/lint/detekt/ktlint **console** output, plus Android **device** signals — `adb install` outcome, emulator/device launch errors, and `logcat` crash/ANR extraction), `NavGraphParser` (Android nav-graph destinations → `NAVIGATES_TO`), `ClusterCollapser` (fold repeated finished command/test/gradle history into per-cluster aggregates), `MapDensity` (progressive disclosure *before* collapsing: label tiers by node count, the "Show more" cap step, outlier-trimmed Fit bounds, and the label priority/x-bias constants — it hides decoration, never nodes), `LabelPlacement` (collision-aware label placement: `slotsAround` offers four positions — right, left, then above and below, the vertical pair offset far enough to clear the band a *side* label occupies — and a label is withheld only when all four are taken; the node always stays, and hover restores the text), `ReportParsers` + `BuildReportScanner` (**structured report files** — JUnit/detekt/ktlint/lint XML + SARIF, read off-EDT after a build/test command), `SourceStructureParser` (package/type-imports/test-target from source text), `ActivityColorRole` (state → theme role), `ActivityMapRenderer` (headless PNG preview). The graph links a command/gradle/test node to the results it produced (`PRODUCED` edge, tagged with `COMMAND_OUTPUT` evidence) and touched files to imported/tested/navigated project files (`IMPORTS`/`TESTS`/`EXTENDS`/`IMPLEMENTS`/`NAVIGATES_TO` edges); error/warning→file (`AFFECTED_BY`) edges also carry `COMMAND_OUTPUT` evidence. Structural edges carry `RelationshipEvidence` (`EvidenceSource` + human explanation) so the inspector **and hover tooltip** show **why** a relationship exists. |
| `process/UserMessageJson.kt` | Platform-free, unit-tested builder of the `{"type":"user",...}` stream-json line (and the one copy of the JSON string-escape, which `ClaudeSession` delegates to). Text-only messages keep the historical byte-identical shape — a wire contract with the CLI, asserted by test; pasted images turn `content` into a Messages-API block array, images before text, with blank-text image-only messages valid. |
| `process/ClaudeSession.kt` | Owns one persistent `claude -p` process; stdin/stdout stream-json plumbing; control-protocol responses; `--mcp-config` wiring. `sendUserMessage(text, images)` rides pasted images as base64 `image` blocks on the same stdin line — never via a temp file. The session id is read **only from the top level** of a parsed event — a regex scrape matched it anywhere in the line, so a `tool_result` echoing a log that contains a `session_id` key would silently retarget a later `--resume`. stdout reassembly is locked (reader thread vs. termination flush) but dispatches outside the lock, since `onLine` hops to the EDT. **stderr is not part of the protocol**: it's logged and the last 20 lines ride along with the exit event, so a non-zero exit can say *why* (expired login, bad `extraArgs` flag) instead of just its code. `--mcp-config` is passed as an **owner-only file path**, never the inline literal — the payload holds the bridge auth token, and process arguments are readable by other local users. Permissions are narrowed *before* the token is written, and if owner-only can't be achieved the bridge is disabled rather than falling back to the command line. |
| `process/ClaudePathResolver.kt` | Finds the `claude` binary in GUI-launched IDEs (login-shell PATH). The login shell is **time-boxed** and the result is cached **even when nothing was found** — this runs on the send path, and without negative caching a machine with no `claude` re-ran a full login shell on every message. `invalidate()` re-probes, and settings-apply and the Health check both call it, since those are exactly when a user has just installed it. |
| `process/ArgTokenizer.kt` | Platform-free, unit-tested shell-style tokeniser for the `extraArgs` setting. Quoting only — no expansion or globbing, because the value becomes process arguments directly and never reaches a shell. Splitting on whitespace tore any value containing a space (a system prompt, an `Application Support` path) into arguments the user never wrote. |
| `ide/IdeServer.kt` | The `ide` MCP **WebSocket** server (selection, open editors, `openDiff` → native diff, **scoped `getDiagnostics`**, …). Path-taking tools are gated by `PathAccessPolicy`. A bad token both closes the connection **and** leaves it unmarked, and the message path checks that mark — `close()` only *asks* the peer to leave, so frames already queued still arrive and closing alone was never a gate. The port is whatever the server **actually bound** (bind 0, read it back); probing with a throwaway `ServerSocket` and re-binding the number left a window for another process to take it. `close_tab` returns `TAB_CLOSED` only when a tab really closed — it used to return it unconditionally, telling the model an action had happened that never did. |
| `ide/PathAccessPolicy.kt` | Platform-free (unit-tested) path guard: canonicalises + classifies open/diff/write targets as inside-project / outside / **sensitive** (refuse); writes outside the project need an extra confirm |
| `ide/ProjectStructureEnricher.kt` | **PSI Phase 2a**: enriches files Claude touched with real project structure via **UAST** (imports + class→super/interfaces resolved package-aware for Kotlin & Java; test→prod via `FilenameIndex`; package/module metadata), plus Android resources: **nav-graph** → destination screens (`NavGraphParser` → `NAVIGATES_TO`) and any file-based **resource → referencing sources** (reverse word-index lookup via `AndroidResourceParser`, confirmed → `REFERENCED_BY`). Also an on-demand **`findUsages`** (precise `ReferencesSearch`) for the inspector's "Find usages" action — never run eagerly. Off-EDT in a smart-mode `ReadAction.nonBlocking`, once per path, project files only. Emits `StructuralRelation`/`FilePackage`. Build depends on `com.intellij.java`; the Kotlin plugin is on the **test** classpath (Kotlin `BasePlatformTestCase` coverage) with no runtime `<depends>`. |
| `ide/InteractionCoordinators.kt` | Platform-free `ApprovalCoordinator` + `DiffReviewCoordinator` + `QuestionCoordinator` — decouple approval/diff/question **decisions** from Swing so the UI and the sandbox test bridge drive identical one-shot logic (no bypass). `<projectService>`s. |
| `interaction/*.kt` | Platform-free, unit-tested **AskUserQuestion** core: `AskUserQuestionModels` (provider-neutral `UserQuestionRequest`/`UserQuestion`/`UserQuestionOption` + `ParseResult`), `AskUserQuestionParser` (protocol JSON → model; refuses empty/fabricated questions), `QuestionFormState` (radio/checkbox/Other selection + validity + `resolvedAnswers`), `AskUserQuestionResponseBuilder` (answers keyed by full question text, original `questions` preserved, non-mutating). The Swing view is `ClaudePanel.AskUserQuestionBlock`. |
| `ide/SightlineTestBridge.kt` | **Sandbox-only** MCP tools (`sightline.test.*`) gated by `TestBridgeGuard` (`-Dsightline.testBridge=true`): inspect/resolve pending approvals, diffs & **questions** (incl. `simulate_question` to inject one + `respond_question` to answer via the production builder), capture the tool window. See [docs/TESTING.md](docs/TESTING.md). |
| `health/*.kt` | Platform-free, unit-tested **Health / preflight** core: `HealthModel` (`HealthStatus` OK/WARN/**UNKNOWN**/FAIL with UNKNOWN ranked between WARN and FAIL — not-knowing never masquerades as OK; `HealthCheck`/`HealthReport` + headline/overall/actionable), `HealthChecker` (pure `HealthInputs` → `HealthReport`; every check states what it verified and carries a concrete hint when not OK), `HealthSanitizer` (scrubs a report for the "Copy report" action — tokens/keys/bearer creds, home path → `~`, foreign user paths → tail, username/email/IP; allow-nothing-sensitive, idempotent), `HealthGatherer` (the one platform-touching part: resolves the CLI, shells `claude --version` off-EDT, reads the IDE-server port + settings into `HealthInputs`). The Swing view is `ui/HealthDialog` (modal, non-blocking; **Recheck / Open settings / Copy report / Close**), opened from the composer overflow **More ▸ Health check…**. |
| `ui/ClaudeToolHeader.kt`, `ui/ClaudeComposerPanel.kt`, `ui/ClaudeStatusStrip.kt` | The three Swing chrome pieces around the transcript: header (wordmark, state dot, Chat/Split/Map switch), composer (textarea, attach, `/` actions, mode chip, Send/Stop, **More ▸ Health check…**), and the status strip (concise session state only — never the response text, never cost/duration; those live in the turn footer). The composer wraps the input's `TransferHandler` so pasting an **image** attaches it as a thumbnail chip (routing decided by `PasteRouting`; encoding off-EDT via `ImageAttachmentEncoder`) and pasting **files** attaches `@path` chips — the export half (copy/cut) is forwarded untouched, so intercepting paste never costs copy. A refused paste is *said* (via the host's transcript notice), never silently swallowed. |
| `ui/ImageAttachmentEncoder.kt` | AWT+ImageIO half of pasted images (headless-testable, no platform types): force-loads the clipboard image, downscales to the model ceiling (`MAX_EDGE` 2576px, repeated-halving bilinear so screenshot text survives), encodes lossless PNG, falls back to JPEG-on-white only when the PNG comes out photographically large, and produces the small thumbnail that outlives the send. All in memory — never a temp file. |
| `ui/SightlineUiState.kt` | `<projectService>` holding cross-component UI state (tool-window visibility, workspace mode, session state) — also what the test bridge's `get_ui_state` reads. |
| `ui/A11yNames.kt` | Stable `sightline.*` accessible-name constants for UI automation, kept separate from visible text. See [docs/TESTING.md](docs/TESTING.md). |
| `ui/components/*.kt` | Small reusable Swing widgets: `SegmentedControl` (the Chat/Activity switch, arrow-key navigable), `IconActionButton`, `ContextChip`, `EmptyStatePanel`, `WrapLayout` (a `FlowLayout` that reports the height it will actually occupy once wrapped — plain `FlowLayout` always reports one row, which silently clipped the fourth context chip on a narrow panel). |
| `ui/state/*.kt` | Platform-free, unit-tested **presentation logic** — no Swing: `StatusModel` (session state → status text), `ComposerModel` (input/send enablement plus the **message queue**: submitting mid-turn parks the message rather than silently doing nothing — send stays enabled while running, truly empty input — no text, no image, no attachment — is never queued, an image or attached file alone *is* sendable ("look at this" needs no prose), the placeholder says whether Enter will send or queue, and the host drains one message per finished turn so their output can't interleave; a queued entry captures its pending images at Enter-time — an image is *content*, frozen when submitted, unlike the Android context, which is framing and re-gathered at send time), `ImageAttachments` (**pasted images**: `EncodedImage`/`PendingImage` + `ImageAttachmentPolicy` — per-message cap, the 2576px model-ceiling downscale rule, PNG→JPEG budget, every chip/tooltip/refusal wording, with refusals always *stated*, never a silent no-op paste; ordinals are monotonic so removing "Image 1" never renames "Image 2" — plus `PasteRouting`, the pure paste-precedence decision: files beat text beats image, so a copied file never pastes as its path, Excel cells paste as text not a surprise screenshot, and a macOS screenshot — which carries no text flavor — attaches), `PermissionModes` (the five modes + display names; `auto` is the default), `WorkspaceModes` (chat/split/map, plus `effectiveMode` — SPLIT is honoured wherever the split *button* is offered (MEDIUM and up; a visible control that silently does nothing reads as a bug) and demoted to **CHAT** only on a NARROW panel, without rewriting the user's preference, so a cramped panel never leaves you with a graph and no transcript), `ToolEventPresentation` (**compact row vs card** for a tool event, decided from structured metadata — tool name + outcome — never a display string: routine successful reads/commands recede to a borderless row, while failures, denials and edits keep card weight), `TranscriptRetention` (caps live turns at 150 and **really releases** the oldest — components dropped, id maps pruned — with a notice worded so it never promises a "load earlier" that has nothing to load from), `ProcessingSummary` (per-turn tally — *"17 operations · 4 files edited · 3 checks passed"* — shown in place of the hidden tool cards when `showDetails` is off, which is the default; files edited are counted **distinctly**, so three edits to one file is one file), `DiffPresentation` (added/removed **line counts** + header text with correct singulars, unified vs **side-by-side** by column width, collapse past 24 rows and a hard `MAX_ROWS` ceiling so a whole-file `Write` can't bury the transcript — any capped remainder is *stated*, never silently dropped), `ResponsiveLayout` (width → layout decisions, incl. `readablePadding` — the reading cap is measured against the **chat column**, never the whole panel, with a `MIN_CONTENT_WIDTH` floor so the conversation can't be squeezed into a sliver), `ScrollFollow` (follow-the-bottom + jump-to-latest arming), `PathDisplay` (a file path as a tool row should show it: project-relative where possible — the absolute prefix is identical on every row and crowds out the filename — then shortened **at segment boundaries**, since a plain character cut lands mid-segment and renders as damage; the filename is never cut), `TranscriptPresenter`, `TimelineDockState`, `CompletionSummary` (the per-turn `Completed · 51.6s · 13 turns · $0.404` footer), `LineDiff` (the LCS row diff every edit is drawn from, plus the `sign` gutter marker — extracted from `ClaudePanel`, where it was the one real algorithm with no test and the marker mapping was written out four separate times; ties go to *deletion* so a replaced line reads `- old` then `+ new`, and past `MAX_CELLS` it degrades to a whole-file replacement rather than losing lines). |
| `theme/ClaudeUiTokens.kt`, `theme/ClaudeIcons.kt` | Theme-aware colour/spacing tokens and painted vector icons, resolved from the IDE's own scheme so both themes work. **`accent()` is Sightline's teal**, matching the sight line in the plugin icon — it was a warm orange until 2026-07-20, which was close enough to Anthropic's palette to read as their branding on a plugin that is explicitly not theirs. `ClaudeIcons.brand` deliberately loads the *registered tool-window SVG* rather than a separate asset, so the stripe icon and header wordmark cannot drift apart. **`statusColor(StatusKind)`** lives here too: the header dot and the status strip each carried a byte-identical copy, one edit away from disagreeing about what PERMISSION looks like while both still compiled. |
| `android/*.kt` | Platform-free, unit-tested **Android core** (docs/ANDROID.md): `AndroidFacts` (`FactTier` IDE→BUILD_OUTPUT→STATIC_PARSE→DEVICE→UNKNOWN + `Fact<T>`, whose constructor makes a value-without-provenance unrepresentable; `Fact.ladder` short-circuits so a cheap IDE hit never pays for a device probe), `AndroidSdkLocator` (where the SDK is, in a deliberate order — setting → env → `local.properties` → per-OS default — with an injected `exists` probe so the ordering is tested with no filesystem; hand-rolls `sdk.dir` unescaping because Gradle writes `C\:\\Users\\…` on Windows), `VariantName` (`demoStagingDebug` → flavours + build type; needs **dimension order** because the name alone is genuinely ambiguous, and reports no flavours rather than forcing a wrong split), `AdbOutputParsers` (`adb devices -l`, AVD names, adb version — skips what it doesn't recognise so one future field costs one line, not the listing), `AndroidActionPolicy` (risk gate, below), `AndroidStorePolicy` (persistence guardrails, below), `AndroidContext` (the model: `ModuleContext`/`DeviceContext`/`EditorContext` + `ContextChipKind`), `AndroidContextFormatter` (one model → **two documents**: the glanceable strip, which sheds whole segments to fit rather than clipping mid-word, and the prompt block, which states provenance on every line), `OutputMetadataParser` (AGP's `output-metadata.json` — tier 2, and the reason CLI-first works: applicationId **with the flavour suffix applied**, variant, version code/name), `GradleModuleParser` (tier 3 — brace-matched blocks because a regex truncates `productFlavors` at the first flavour; both quote styles; understands `alias(libs.plugins.android.application)`, since a catalogue-based module never contains the literal plugin id), `VersionCatalogParser`, `GradleTasks` (intent + module + variant → task name; **refuses** when the variant is unknown rather than degrading to the aggregate `assemble`, which on two flavour dimensions builds twenty variants), `BuildFailureClassifier` (raw Gradle output → typed cause: KSP/KAPT, Compose/Kotlin mismatch, JVM target, unresolved dependency, duplicate class, manifest merge, duplicate resource, R8, version catalogue, configuration cache, SDK, OOM. **An unrecognised failure is `UNKNOWN` with the raw excerpt and no suggestion** — a build failure is when a developer is least able to check what they're told), `StackTraceResolver` (frames → `file:line`; the *blame frame* is the deepest one in the app's own packages, since a crash's top frame is nearly always framework code; `appPrefixes` covers applicationId **and** namespace because a flavour suffix makes them differ), `TestSelection` (changed files → tests to run, grouped one Gradle invocation per module/kind, and `uncovered` for changed code with no test — a green run that skipped the relevant test is worse than no run), `LogcatRedactor` (**the privacy gate**: tokens/JWTs/emails/coordinates/device ids/phone numbers/home paths, on by default and not a setting. Mirrors `HealthSanitizer`'s allow-nothing-sensitive posture and adds one rule it doesn't need — it **fails closed**, dropping an over-long line whole rather than passing it through partly scrubbed. Counts what it removed, because silently altering evidence a developer is debugging from is its own harm), `LogcatParser` (threadtime + brief formats, level/pid filtering that **keeps continuation lines** so a stack trace survives a filter, repeat folding, framework-noise suppression, and crash/ANR/StrictMode/OOM/process-death signals), `DeviceActions` (adb argv per action — **classified through `AndroidActionPolicy` at construction**, so an action cannot be added that skips the gate), `DeviceRecipes` (accessibility/large-font/dark/landscape conditions **with a revert built from the values read beforehand** — restoring font scale to 1.0 is not a revert if the user runs at 1.15; a plan whose prior state couldn't be read reports `revertible: false` and must be refused), `CrashInvestigator` (findings grouped **Confirmed / Likely / Contributing / Missing** — a Finding cannot exist without a confidence, so nothing is stated without saying how sure it is), `DumpsysParser` (`dumpsys activity/window/config` → resumed activity, back stack ordered by the explicit `Hist #N` index rather than print order, size/density/orientation/night/font/locale; returns **null rather than a guess** for anything unrecognised), `ComposeSourceAnalyzer` (composables, `@Preview` coverage incl. **merged stacked annotations**, and only findings the text settles beyond doubt — missing preview on a *screen-level* composable, `mutableStateOf` without `remember`, `Image` with no `contentDescription`. **Recomposition and stability are deliberately not analysed**: they aren't decidable from source text, and a regex approximating them is exactly the confidently-wrong output this codebase avoids), `ManifestAudit` (parses the **merged** manifest — where library-contributed exports and permissions actually appear — and audits exports, missing `android:exported`, cleartext, backup rules, foreground-service types, plus a code-vs-manifest permission cross-check in **both** directions. Hand-rolled rather than an XML parser so every finding carries a **line**), `RouteExtractor` (Compose Navigation routes, arguments, deep links, dangling `navigate()` calls and unreachable routes — routes are strings, so nothing catches a rename at compile time. Reads the *argument list*, not the line, and refuses to resolve a concatenated route rather than inventing one). |
| `ide/android/*.kt` | Thin platform layer: `AndroidEnvironment` (`<projectService>` — resolves the SDK, runs `adb`/`emulator` **off-EDT with mandatory timeouts**, detects whether the project is Android at all; deliberately has **no** Android Studio dependency), `AndroidContextResolver` (`<projectService>` — walks the ladder; caches the expensive rungs for 15s but **always re-reads the open file**, which changes far too often to cache and is one `ReadAction` against a build-tree walk), `AndroidMcpTools` (`android.getContext` / `resolveTask` / `selectTests` / `diagnoseBuild` on the **existing** `ide` server — `--mcp-config` is one hardcoded string, so a second server means editing it), `AndroidDeviceTools` (`listDevices` / `captureLogcat` / `investigateCrash` / `deviceRecipe`; logcat is redacted **before it leaves the class**, unconditionally, and destructive actions are deliberately not exposed here at all — they go through the UI's `ApprovalCoordinator`), `AndroidAuditTools` (`auditManifest` / `analyzeRoutes` — file-based, so they work with no device and during indexing; `auditManifest` warns when it had to fall back to the *source* manifest, because that hides every library-contributed finding), `AndroidScreenTools` (`inspectScreen` / `analyzeCompose`; both responses carry an explicit `limits` field, because the Activity level is all dumpsys can see — the Compose route and semantics tree live in the app's memory — and a model that infers a route from an activity name would state it as observed), `AndroidStudioFactProvider` (the `androidFactProvider` EP — tier 1, and the only thing that knows the *currently selected* variant). `ide/android/studio/StudioFactProvider` implements it and **loads only via `META-INF/sightline-android.xml`**, so its `com.android.tools.idea.*` imports never resolve in a plain IDEA. Any failure there degrades to null → tier 2. |
| `ui/android/AndroidContextStrip.kt` | The one-line Android summary above the composer (`app · demoStagingDebug \| Pixel 8 · API 35 · ready \| RouteDetailsScreen.kt`), hidden entirely outside an Android project. Clicking it opens the toggle menu — which facts go out with each message, plus Refresh. Fits its text from `doLayout`, **not** a `componentResized` listener: a resize event is only delivered in a realised hierarchy, so the listener version did nothing headlessly and nothing on the first real layout pass. |
| `settings/ClaudeSettings*.kt` | Persisted settings + Settings UI (Settings → Tools → **Sightline for Claude Code**) |

### Agent Activity Map

Replaces the generic "Thinking…" indicator with a live graph of **observable** agent activity
(never the model's hidden reasoning). Pipeline: `ClaudePanel` hooks — `renderToolBody` (tool_use),
`onUser` (tool_result), `onSystem` (status), `taskStarted`/`taskDone` — call `ActivityInterpreter`,
which emits `AgentActivityEvent`s that `ActivityGraph` reduces into nodes/edges/clusters. The
`activity/` package has **no IntelliJ-platform imports**, so it's covered by plain JUnit4 tests in
`src/test` (interpreter, reducer, classifier, parsers, colour roles, and a full 9-step sequence).
Denied/cancelled tools are reconciled honestly: a `can_use_tool` deny feeds `ActivityInterpreter.toolDenied`
(correlated by `tool_use_id`), which emits `ActivityDenied`; the graph marks the node `DENIED`/`CANCELLED`
(muted **BLOCKED** colour role, dashed ring), drops the optimistic patch edge, and the transcript card
shows "Denied by user" — a denied edit never looks executed. Denial is **not** an error.
Structured tool events are high-confidence; text/heuristic guesses are lower-confidence and drawn
subtler. Prose is **never** mined for file names. The map physics run on a Swing `Timer` that only
ticks while the component is showing and auto-suspends when idle; "reduce motion" settles statically.
Header layout button switches **Chat / Split / Map** (`activityViewMode`).

**Headless visual review (dev):** two tests render to PNG so layout can be eyeballed without the IDE —
`ui/ChatLayoutPreviewTest` writes `build/chat-layout-{narrow,medium,wide}.png` (a real `ClaudePanel`,
seeded through the production event path, at each width class) and the map preview below. Read the PNGs
directly; this is the only automated visual channel (the `studio` MCP has no screenshot tool and cannot
see the plugin's tool window). See [docs/TESTING.md](docs/TESTING.md).

**Headless preview (dev):** `activity/ActivityMapRenderer.kt` paints an `ActivityGraph` to a PNG
using plain `java.awt` (no platform deps), so the map can be eyeballed without launching the IDE.
`ActivityMapPreviewTest` drives a busy fixture and writes `build/activity-map-preview-{dark,light}.png`
— regenerate with `./gradlew test` and open/read those files. (The live panel is theme-aware and
animated, so the preview is representative, not pixel-identical.)

The transcript is a `Scrollable` `JPanel` (BoxLayout Y) of block components. Each assistant turn is
an `AssistantTurn` holding `TextBlock`, `ThinkingBlock` (collapsible),
`ToolCard` (collapsible, icon + summary + result; renders as a borderless **compact row** or a
bordered **card** per `ToolEventPresentation`), `FileEditBlock` (an Edit/MultiEdit/Write hosted inside
its card: header with line counts + **Open file** / **Copy diff**, per-hunk numbering, unified or
side-by-side per `DiffPresentation`, collapsed when long; the approval preview renders the same diff as
text via `addEditOrText`, so you never approve a change you can't see), `ApprovalBlock`,
`AskUserQuestionBlock`, and a
subtle run-metadata **footer** (`Completed · 51.6s · 13 turns · $0.404` via `CompletionSummary`; this is
where cost/duration/turns live — never the status strip, which shows only concise session state).
`TextBlock` renders **Markdown live while it streams**: each delta re-parses the accumulated text on a
coalescing tick (`StreamingMarkdown.TICK_MS`, slower past `LONG_DOC_CHARS`) and rebuilds only the blocks
that changed — `StreamingMarkdown.stablePrefix` keeps every finished block's component, so a tick costs
one parse plus the growing tail block, and formatting appears with the first tokens instead of at
`content_block_stop`. Live ticks skip file-ref linkification (it queries the project index); the
finalize pass at `content_block_stop` runs the full pipeline, links included. A live parse/render
failure degrades that block to plain streamed text for the rest of the stream (retrying a failing parse
per tick would burn the EDT), and finalize retries the full render with the regex-renderer fallback
behind it — the response is never dropped.
"Details" toggle hides thinking/tool cards (compact mode); approval **and question** blocks always stay
visible (they block the turn). User turns are rounded `Bubble`s; a turn that carried pasted images
shows their **thumbnails** (the small pre-scaled renders — the full bytes went to the CLI and are
released with the send, so the transcript never retains megapixel images).

### AskUserQuestion (structured input, not permission)

`AskUserQuestion` arrives on the same `can_use_tool` control channel as a permission prompt but is a
request for **input**, not approval — so `showApproval` routes it to `showAskUserQuestion` instead of a
generic Allow/Deny `ApprovalBlock` (which would dump the raw question JSON). The `interaction/` core
parses it (`AskUserQuestionParser`), tracks selections (`QuestionFormState` — radio for single-select,
checkbox for multi-select, plus a free-text **Other**), and builds the response
(`AskUserQuestionResponseBuilder`: the original `questions` echoed back plus an `answers` object keyed by
**full question text**, labels joined by `, `). `QuestionCoordinator` resolves it exactly once (UI or
test bridge). Each option renders as a **selectable card** (`OptionCard`): the whole rounded row is the
click target, hover fills it, selection draws the accent border — the toggle button stays inside for
accessibility and keyboard state, the card is presentation around it. Continue stays disabled until every question is answered; **Cancel** denies the request to
unblock the turn (a valid option like "Skip" is a normal answer, never a denial). Status reads
"Waiting for your answer", and the streamed tool card shows `Asked · <header or N questions>`, never JSON.

## Build / run

```bash
# ALWAYS build with Android Studio's bundled JBR 21 (set in gradle.properties):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin           # -> build/distributions/sightline-<version>.zip  (rootProject.name)
./gradlew runIde                # sandbox AS with the plugin
```

**CI / releasing — see [docs/RELEASING.md](docs/RELEASING.md).** `.github/workflows/build.yml` tests +
verifies every push and PR; `release.yml` publishes on a `v*` tag. Three things about it are load-bearing:
- The platform target is **switchable** (`-PplatformType=AI -PplatformVersion=2025.3.1.1`) because a
  runner has no local Android Studio. It must be **AI, not IC**: the compile classpath needs
  `org.jetbrains.android`, and **IC does not bundle it** (it ships the unrelated `android-gradle-dsl`).
  2025.3.1.1 is build 253 — the `sinceBuild` floor — so CI compiles against the oldest platform the
  listing claims, and a newer-API slip fails the build instead of a user's IDE.
- The Marketplace **channel is derived from the version suffix**, never chosen: `-beta` → the opt-in
  beta channel, a bare version → **stable, everyone**. Shipping stable is a deliberate edit to `version`,
  not a workflow input, because the interactive safety paths are still human-untested.
- **`version` lives only in `build.gradle.kts`.** `plugin.xml` carries no `<version>` — `patchPluginXml`
  writes it in. Two copies could disagree and the descriptor would silently win.

**The first Marketplace upload cannot be automated** — JetBrains has no API for creating a *new*
listing. The pipeline publishes subsequent versions only.

Install: **Settings → Plugins → ⚙ → Install Plugin from Disk** → the zip → restart.

## Build gotchas (each cost real debugging)

- **Target = the installed Android Studio** via `local("/Applications/Android Studio.app")`. This AS
  is **2026.1 / build 261**, bundles **JBR 21** and **Kotlin 2.3.10**.
- **Kotlin Gradle plugin version must be ≥ the IDE's bundled Kotlin (2.3.10).** An older compiler
  can't read the platform's newer class metadata and dies with a FIR "Internal compiler error"
  (`FirIncompatibleClassExpressionChecker`, "source must not be null").
- **Build with JBR 21**, not the PATH's Java 17 (`org.gradle.java.home` in `gradle.properties`).
- `buildSearchableOptions = false` — the task launches a second IDE and is flaky against `local()` AS.
- **No JCEF**: this AS's bundled JBR has **no** JCEF/Chromium, so a webview panel can't render.
  The UI is therefore **native Swing**, not HTML. Verify with
  `find "/Applications/Android Studio.app/Contents/jbr" -iname "*jcef*"` (empty).
- **NEVER tell a user to change AS's boot runtime** to get JCEF. A JBR **25** rejects the Security
  Manager VM option AS passes and AS won't start. Recovery: delete/rename
  `~/Library/Application Support/Google/AndroidStudio<ver>/studio.jdk`.
- **Bundled libraries** (platform doesn't expose them): `gson` (parse stream-json) and
  `Java-WebSocket` (the ide server; `exclude group: "org.slf4j"` — platform provides slf4j).
- **`./gradlew verifyPlugin` does not work — run `tools/verify-plugin.sh`.** IPGP 2.6.0 resolves the IDE
  under `idea:ideaIC:<v>` (group `idea`); the artifact is at `com.jetbrains.intellij.idea:ideaIC:<v>`.
  Both `select { }` and `ide(...)` hit the same wrong group. The script fetches the correct coordinate
  and runs the same verifier CLI. **Last run: `io.mp.sightline:0.1.0-beta` vs `IC-253.28294.334` —
  Compatible, 0 problems.** Re-run before every release.
- **A NUL byte makes a source file invisible to `grep`.** Two files here had one (a mangled `' '` char
  literal), so `file` reported them as `data`, grep skipped them, and a package-wide rename silently
  missed both — surfacing only as unresolved references at compile time. If a text tool seems to be
  ignoring a file, run `file <path>`. Scan with Python over bytes, not grep, when correctness matters.

## Standing decisions (don't re-litigate)

These outlived the backlog items that recorded them. They are constraints on future work, not roadmap.

- **Nothing is persisted to disk except settings — and the one named Android exception.** There is no
  session/transcript persistence: `ClaudeSession.lastSessionId` is in-memory and `--resume` exists only
  to survive a user Stop. Any persistence must be **workspace-relative paths only** — **never** absolute
  paths, source contents, prompts, or reasoning — with a versioned schema, a session cap + retention
  limit, delete-one / clear-all, and **off by default**. Do not let persistence arrive as a side effect
  of satisfying some other requirement.
  **The exception** (decided 2026-07-20, docs/ANDROID.md §1.3): an opt-in `.sightline/` store under the
  project for Android results that are worthless without history — flaky-test history, screenshot
  baselines, artifact-size snapshots, saved workflows. It is `androidPersistCache`, **off by default**,
  and `android/AndroidStorePolicy` *enforces* every guardrail above in code rather than by convention:
  `toRelative` refuses anything outside the project instead of falling back to an absolute path,
  `accepts` discards an unknown schema version rather than migrating on a guess, and `looksSensitive` is
  a backstop against a credential or `$HOME` path reaching a file that outlives the session. Extending
  the store means extending that policy, not working around it.
- **Android capability is CLI-first; Android Studio is optional.** (Decided 2026-07-20, docs/ANDROID.md
  §1.1.) The spine runs on stable contracts — `adb`, the `emulator` binary, `gradlew`, and parsing AGP's
  own build output — so everything Android keeps working in a plain IntelliJ IDEA and the `sinceBuild=253`
  Marketplace reach survives. `org.jetbrains.android` is an **optional** `<depends>` whose only
  implementation lives in `ide/android/studio/`, registered from `META-INF/sightline-android.xml`; that
  file is the isolation boundary, and the class never loads where its `com.android.tools.idea.*` imports
  can't resolve. Those imports are internal API the Plugin Verifier flags and Android Studio breaks
  between releases, so the rule is: **tier 1 is an upgrade, never a prerequisite.** Wanting one more
  IDE-only fact is not a reason to make it one — put it behind the extension point or find a tier-2 source.
- **Every Android fact carries its source tier, and UNKNOWN is a valid answer.** (Decided 2026-07-20,
  docs/ANDROID.md §1.2.) `android/AndroidFacts` resolves facts down a ladder — IDE → AGP build output →
  static parse → device → UNKNOWN — and `Fact<T>`'s constructor makes "a value with no provenance"
  unrepresentable. This is the Android analogue of `HealthStatus.UNKNOWN` and exists for the same reason:
  a variant labelled `(last build)` is useful, the same string presented as current costs an hour. Resist
  "just default it to something sensible" — a plausible default is precisely the failure mode the ladder
  is built to prevent.
- **The transcript's eviction notice must not promise recovery.** `TranscriptRetention` genuinely
  releases old turns, and with no persistence there is nothing to reload them from, so the wording is
  *"N earlier turns were released…"* — never a "load earlier" control that cannot work. A test asserts
  the string never contains "load". (Decision confirmed 2026-07-20.)
- **The graph never claims a relationship it can't evidence.** Deep inference (ViewModel→Composable,
  UseCase→Repository, broad call graphs) was considered and **dropped**: it is heuristic pattern-matching
  presented as structural fact, which is exactly what `RelationshipEvidence`/`EvidenceSource` exist to
  prevent. Structural edges come from PSI/UAST or parsed command output, or they don't exist.
  **Sharpened 2026-07-20** (docs/ANDROID.md M5) for the Android architecture map: **naming may colour a
  node's cluster; it may never create an edge.** `ActivityClassifier` already routes `*Repository.kt` to
  the DATA_REPOSITORIES cluster, and that stays fine — clustering is presentation. What the rejection
  forbids is turning that same guess into a relationship. PSI-resolved constructor injection and
  Hilt/Dagger `@Inject`/`@Binds`/`@Provides` **are** real declarations, so a DI graph built from them is
  permitted and tagged `PSI_DECLARATION`; one built from filename suffixes is not, at any confidence.
- **Reading width stays ~760px.** A "1,100–1,250px content width" was proposed from a full-screen editor
  window; a docked IntelliJ tool window is typically 400–700px, so that cap would never engage.
- **The transcript is not virtualised, deliberately.** The case for it assumed the component tree is
  rebuilt per streamed token. It isn't: live Markdown streaming re-renders on a coalescing ~150ms tick,
  and `StreamingMarkdown.stablePrefix` rebuilds only the *changed tail block* — finished blocks keep
  their components, so a tick costs one parse plus one block regardless of message length (and the tick
  slows to 1s past `LONG_DOC_CHARS`). The real long-session cost was unbounded turn retention, which
  `TranscriptRetention` now caps.

## Feature flags (settings)

- `interactiveApproval` (default on) → `--permission-prompt-tool stdio` + control protocol.
- `ideIntegration` (default on) → runs `IdeServer` and passes it via `--mcp-config` (ws).
- `includePartialMessages` (default on) → `--include-partial-messages` for token-level streaming.
- `showDetails` (default off) → compact vs detailed transcript.
- `showActivityMap` (default on) → show the Agent Activity Map; `activityViewMode`
  (`chat`|`split`|`map`, default `split`), `activityReduceMotion`, `activityMaxNodes`
  (visible cap, default 200), `activityMaxRetained` (session cap, default 500),
  `activityTimelineExpanded` (default off — the dock starts as the compact collapsed summary), and
  `activityAboutDismissed` (whether the one-time "observable activity only" disclaimer was dismissed).
- `androidFeatures` (default on) → the Android control-centre features. Additionally gated on
  `AndroidEnvironment.looksLikeAndroidProject()`, so a non-Android project sees nothing either way.
  `androidSdkPath` (blank = auto-detect) overrides SDK discovery when a machine has several.
- `androidPersistCache` (**default off**) → the `.sightline/` store; `androidCacheMaxEntries`
  (default 200) caps each store. This is the one disk carve-out — see **Standing decisions**.
- `permissionMode` (default `auto`) — set via the composer mode chip or the Settings dropdown.
  `auto` (⚡) is **model-gated** (Sonnet/Opus only; silently falls back to `default` on Haiku). The CLI
  announces the fallback only by echoing `permissionMode` back in `system/init`, so `ClaudePanel`
  compares it against the requested mode and says so once per launch — an unreported fallback leaves the
  mode chip claiming a policy that isn't in force.
  Composes with `interactiveApproval`. The five modes and their chip names live in
  `ui/state/PermissionModes`: `default` "Ask" (prompts all), `acceptEdits` "Auto-edit" (only commands),
  `plan` "Plan", `auto` "Auto", `bypassPermissions` "Unrestricted" (never prompts; flagged dangerous).
- `extraArgs` — advanced: extra CLI args appended verbatim to every `claude` invocation, split by
  `process/ArgTokenizer` (quote-aware, no shell expansion — the value never reaches a shell).
