# Sightline for Claude Code — internal notes

**Sightline** (product name; formerly "Claude Code Panel") is a native **Android Studio / IntelliJ
plugin** that wraps the `claude` CLI in a graphical chat panel (message bubbles, collapsible tool
cards, diffs, interactive approval, IDE integration). It drives the CLI over its streaming-JSON
protocol and renders everything in **native Swing**. The Kotlin package stays `io.mp.claudecodepanel`
and the plugin `<id>` is unchanged (only the user-visible brand moved to a neutral, trademark-safe name).

> Detailed, reverse-engineered CLI protocol facts live in **[docs/PROTOCOL.md](docs/PROTOCOL.md)**.
> Read that before touching `ClaudeSession` or `IdeServer`.
> How the interactive flows are verified (coordinators + the sandbox test bridge) lives in
> **[docs/TESTING.md](docs/TESTING.md)** — read it before touching approval/diff wiring.

## Architecture

| File | Role |
|---|---|
| `ClaudeToolWindowFactory.kt` | Registers the right-dock "Sightline" tool window (`<toolWindow id="Sightline">`) |
| `ui/ClaudePanel.kt` | The whole UI: Swing transcript (per-turn block components), composer, modes popup, `/` actions menu, interactive-approval cards, event rendering. Feeds every observable tool/stream event into the activity map. Assistant text renders through `ui/markdown/` (below). Auto-scroll follows the bottom only while the user is near it (`ScrollFollow`); scrolling up pauses following and floats a **"Jump to latest ↓"** button over the transcript (a `JLayeredPane` overlay, so showing/hiding it never shifts the text being read) which scrolls to the end and re-arms follow. |
| `ui/markdown/*.kt` | Platform-free, unit-tested **Markdown rendering** for assistant messages: `MarkdownModel` (block/inline model), `MarkdownDocParser` (adapter over the platform-bundled `org.intellij.markdown` GFM parser → model; no added dependency; graceful plain-text fallback; explicit `> [!WARNING]` callouts, never inferred), `FileRefDetector` (conservative file-reference detection + a pure `linkify` transform gated by an injected resolver — never guesses), `CodeBlockLayout` (collapse rules for long fences), `TableLayout` (column-width floor → when a wide table scrolls), `CodeLanguages` (fence tag → file extension; a fixed table, so an unknown tag renders plain rather than confidently wrong). `CodeHighlighting` is the one platform-touching helper: it lexes a fence with the IDE's own `SyntaxHighlighter` + colour scheme (read-only, best-effort — an unlexable fragment degrades to plain monospace). `BlockRenderer` is the thin Swing half: theme-aware components for headings/paragraphs/lists (incl. task lists)/GFM tables (h-scroll when wide)/fenced code (**syntax-highlighted**, Copy, h-scroll, Expand/Collapse past 24 lines)/quotes/callouts, with inline bold/italic/strikethrough/code/links. Replaces the old regex renderer. |
| `ui/ActivityMapPanel.kt` | The **Agent Activity Map**: force-directed Swing graph, current-focus card, node details (open/reveal/pin/hide, plus **Find usages** for a source node → `ProjectStructureEnricher.findUsages`), timeline, filter combo, legend, and pause/reduce-motion/fit/clear controls. Selecting a node fires `onNodeSelected`, which `ClaudePanel` uses to **reveal the transcript row that produced it** (turning details on if they were hidden, then flashing the row); `selectByPath`/`selectByLabel` drive the reverse, from a tool card's **Show in map** hover action. A "Collapse finished history" toggle folds finished command/test/gradle clusters (`ClusterCollapser`) into clickable **"N commands" chips** that expand in place. Density is staged by `MapDensity`: labels thin out as the graph grows (anchors/errors and whatever the user is pointing at always keep theirs), the node counter becomes a clickable **"N of M · Show more"** that lifts the visible cap for the session only, and **Fit** pads for labels and trims far-flung outliers so a couple of stragglers can't shrink the graph to a speck. Renders only; theme-aware colours. |
| `activity/*.kt` | Platform-free, unit-tested core of the map: `AgentActivityEvent` (normalised event model), `ActivityModel` (graph data model — node/edge/category/state/timeline types; `RelationshipEvidence.stronger` ranks/merges evidence), `ActivityInterpreter` (raw tool events → normalised events), `ActivityGraph` (reducer → nodes/edges/clusters/focus/timeline), `ActivityClassifier` (path → cluster), `OutputParsers` (Gradle/compiler/test + adb/lint/detekt/ktlint **console** output, plus Android **device** signals — `adb install` outcome, emulator/device launch errors, and `logcat` crash/ANR extraction), `NavGraphParser` (Android nav-graph destinations → `NAVIGATES_TO`), `ClusterCollapser` (fold repeated finished command/test/gradle history into per-cluster aggregates), `MapDensity` (progressive disclosure *before* collapsing: label tiers by node count, the "Show more" cap step, outlier-trimmed Fit bounds, and the label priority/x-bias constants — it hides decoration, never nodes), `LabelPlacement` (collision-aware label placement: a clashing label flips to the node's other side, and is withheld only if that is taken too — the node always stays, and hover restores the text), `ReportParsers` + `BuildReportScanner` (**structured report files** — JUnit/detekt/ktlint/lint XML + SARIF, read off-EDT after a build/test command), `SourceStructureParser` (package/type-imports/test-target from source text), `ActivityColorRole` (state → theme role), `ActivityMapRenderer` (headless PNG preview). The graph links a command/gradle/test node to the results it produced (`PRODUCED` edge, tagged with `COMMAND_OUTPUT` evidence) and touched files to imported/tested/navigated project files (`IMPORTS`/`TESTS`/`EXTENDS`/`IMPLEMENTS`/`NAVIGATES_TO` edges); error/warning→file (`AFFECTED_BY`) edges also carry `COMMAND_OUTPUT` evidence. Structural edges carry `RelationshipEvidence` (`EvidenceSource` + human explanation) so the inspector **and hover tooltip** show **why** a relationship exists. |
| `process/ClaudeSession.kt` | Owns one persistent `claude -p` process; stdin/stdout stream-json plumbing; control-protocol responses; `--mcp-config` wiring |
| `process/ClaudePathResolver.kt` | Finds the `claude` binary in GUI-launched IDEs (login-shell PATH) |
| `ide/IdeServer.kt` | The `ide` MCP **WebSocket** server (selection, open editors, `openDiff` → native diff, **scoped `getDiagnostics`**, …). Path-taking tools are gated by `PathAccessPolicy`. |
| `ide/PathAccessPolicy.kt` | Platform-free (unit-tested) path guard: canonicalises + classifies open/diff/write targets as inside-project / outside / **sensitive** (refuse); writes outside the project need an extra confirm |
| `ide/ProjectStructureEnricher.kt` | **PSI Phase 2a**: enriches files Claude touched with real project structure via **UAST** (imports + class→super/interfaces resolved package-aware for Kotlin & Java; test→prod via `FilenameIndex`; package/module metadata), plus Android resources: **nav-graph** → destination screens (`NavGraphParser` → `NAVIGATES_TO`) and any file-based **resource → referencing sources** (reverse word-index lookup via `AndroidResourceParser`, confirmed → `REFERENCED_BY`). Also an on-demand **`findUsages`** (precise `ReferencesSearch`) for the inspector's "Find usages" action — never run eagerly. Off-EDT in a smart-mode `ReadAction.nonBlocking`, once per path, project files only. Emits `StructuralRelation`/`FilePackage`. Build depends on `com.intellij.java`; the Kotlin plugin is on the **test** classpath (Kotlin `BasePlatformTestCase` coverage) with no runtime `<depends>`. |
| `ide/InteractionCoordinators.kt` | Platform-free `ApprovalCoordinator` + `DiffReviewCoordinator` + `QuestionCoordinator` — decouple approval/diff/question **decisions** from Swing so the UI and the sandbox test bridge drive identical one-shot logic (no bypass). `<projectService>`s. |
| `interaction/*.kt` | Platform-free, unit-tested **AskUserQuestion** core: `AskUserQuestionModels` (provider-neutral `UserQuestionRequest`/`UserQuestion`/`UserQuestionOption` + `ParseResult`), `AskUserQuestionParser` (protocol JSON → model; refuses empty/fabricated questions), `QuestionFormState` (radio/checkbox/Other selection + validity + `resolvedAnswers`), `AskUserQuestionResponseBuilder` (answers keyed by full question text, original `questions` preserved, non-mutating). The Swing view is `ClaudePanel.AskUserQuestionBlock`. |
| `ide/SightlineTestBridge.kt` | **Sandbox-only** MCP tools (`sightline.test.*`) gated by `TestBridgeGuard` (`-Dsightline.testBridge=true`): inspect/resolve pending approvals, diffs & **questions** (incl. `simulate_question` to inject one + `respond_question` to answer via the production builder), capture the tool window. See [docs/TESTING.md](docs/TESTING.md). |
| `health/*.kt` | Platform-free, unit-tested **Health / preflight** core: `HealthModel` (`HealthStatus` OK/WARN/**UNKNOWN**/FAIL with UNKNOWN ranked between WARN and FAIL — not-knowing never masquerades as OK; `HealthCheck`/`HealthReport` + headline/overall/actionable), `HealthChecker` (pure `HealthInputs` → `HealthReport`; every check states what it verified and carries a concrete hint when not OK), `HealthSanitizer` (scrubs a report for the "Copy report" action — tokens/keys/bearer creds, home path → `~`, foreign user paths → tail, username/email/IP; allow-nothing-sensitive, idempotent), `HealthGatherer` (the one platform-touching part: resolves the CLI, shells `claude --version` off-EDT, reads the IDE-server port + settings into `HealthInputs`). The Swing view is `ui/HealthDialog` (modal, non-blocking; **Recheck / Open settings / Copy report / Close**), opened from the composer overflow **More ▸ Health check…**. |
| `ui/ClaudeToolHeader.kt`, `ui/ClaudeComposerPanel.kt`, `ui/ClaudeStatusStrip.kt` | The three Swing chrome pieces around the transcript: header (wordmark, state dot, Chat/Split/Map switch), composer (textarea, attach, `/` actions, mode chip, Send/Stop, **More ▸ Health check…**), and the status strip (concise session state only — never the response text, never cost/duration; those live in the turn footer). |
| `ui/SightlineUiState.kt` | `<projectService>` holding cross-component UI state (tool-window visibility, workspace mode, session state) — also what the test bridge's `get_ui_state` reads. |
| `ui/A11yNames.kt` | Stable `sightline.*` accessible-name constants for UI automation, kept separate from visible text. See [docs/TESTING.md](docs/TESTING.md). |
| `ui/components/*.kt` | Small reusable Swing widgets: `SegmentedControl` (the Chat/Activity switch, arrow-key navigable), `IconActionButton`, `ContextChip`, `EmptyStatePanel`. |
| `ui/state/*.kt` | Platform-free, unit-tested **presentation logic** — no Swing: `StatusModel` (session state → status text), `ComposerModel` (input/send enablement), `PermissionModes` (the five modes + display names; `auto` is the default), `WorkspaceModes` (chat/split/map, plus `effectiveMode` — SPLIT is demoted to **CHAT** below the WIDE breakpoint without rewriting the user's preference, so a cramped panel never leaves you with a graph and no transcript), `ToolEventPresentation` (**compact row vs card** for a tool event, decided from structured metadata — tool name + outcome — never a display string: routine successful reads/commands recede to a borderless row, while failures, denials and edits keep card weight), `ProcessingSummary` (per-turn tally — *"17 operations · 4 files edited · 3 checks passed"* — shown in place of the hidden tool cards when `showDetails` is off, which is the default; files edited are counted **distinctly**, so three edits to one file is one file), `DiffPresentation` (added/removed **line counts** + header text with correct singulars, unified vs **side-by-side** by column width, collapse past 24 rows and a hard `MAX_ROWS` ceiling so a whole-file `Write` can't bury the transcript — any capped remainder is *stated*, never silently dropped), `ResponsiveLayout` (width → layout decisions), `ScrollFollow` (follow-the-bottom + jump-to-latest arming), `TranscriptPresenter`, `TimelineDockState`, `CompletionSummary` (the per-turn `Completed · 51.6s · 13 turns · $0.404` footer). |
| `theme/ClaudeUiTokens.kt`, `theme/ClaudeIcons.kt` | Theme-aware colour/spacing tokens and icons, resolved from the IDE's own scheme so both themes work. |
| `settings/ClaudeSettings*.kt` | Persisted settings + Settings UI (Settings → Tools → **Sightline for Claude Code**) |

### Agent Activity Map (v0.6.0)

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
`TextBlock` streams plain text token-by-token and, at `content_block_stop`, swaps to the
`ui/markdown/` component tree (falling back to plain text on any parse/render failure). Because the swap
happens once at the end of a block, syntax highlighting never re-lexes mid-stream.
"Details" toggle hides thinking/tool cards (compact mode); approval **and question** blocks always stay
visible (they block the turn). User turns are rounded `Bubble`s.

### AskUserQuestion (structured input, not permission)

`AskUserQuestion` arrives on the same `can_use_tool` control channel as a permission prompt but is a
request for **input**, not approval — so `showApproval` routes it to `showAskUserQuestion` instead of a
generic Allow/Deny `ApprovalBlock` (which would dump the raw question JSON). The `interaction/` core
parses it (`AskUserQuestionParser`), tracks selections (`QuestionFormState` — radio for single-select,
checkbox for multi-select, plus a free-text **Other**), and builds the response
(`AskUserQuestionResponseBuilder`: the original `questions` echoed back plus an `answers` object keyed by
**full question text**, labels joined by `, `). `QuestionCoordinator` resolves it exactly once (UI or
test bridge). Continue stays disabled until every question is answered; **Cancel** denies the request to
unblock the turn (a valid option like "Skip" is a normal answer, never a denial). Status reads
"Waiting for your answer", and the streamed tool card shows `Asked · <header or N questions>`, never JSON.

## Build / run

```bash
# ALWAYS build with Android Studio's bundled JBR 21 (set in gradle.properties):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin           # -> build/distributions/sightline-for-claude-code-<version>.zip
./gradlew runIde                # sandbox AS with the plugin
```

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
- `permissionMode` (default `auto`) — set via the composer mode chip or the Settings dropdown.
  `auto` (⚡) is **model-gated** (Sonnet/Opus only; silently falls back to `default` on Haiku).
  Composes with `interactiveApproval`. The five modes and their chip names live in
  `ui/state/PermissionModes`: `default` "Ask" (prompts all), `acceptEdits` "Auto-edit" (only commands),
  `plan` "Plan", `auto` "Auto", `bypassPermissions` "Unrestricted" (never prompts; flagged dangerous).
- `extraArgs` — advanced: extra CLI args appended verbatim to every `claude` invocation.
