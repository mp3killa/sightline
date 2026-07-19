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
| `ClaudeToolWindowFactory.kt` | Registers the right-dock "Claude" tool window |
| `ui/ClaudePanel.kt` | The whole UI: Swing transcript (per-turn block components), composer, modes popup, `/` actions menu, interactive-approval cards, event rendering. Feeds every observable tool/stream event into the activity map. |
| `ui/ActivityMapPanel.kt` | The **Agent Activity Map**: force-directed Swing graph, current-focus card, node details (open/reveal/pin/hide), timeline, filter combo, legend, and pause/reduce-motion/fit/clear controls. Renders only; theme-aware colours. |
| `activity/*.kt` | Platform-free, unit-tested core of the map: `AgentActivityEvent` (normalised event model), `ActivityModel` (graph data model — node/edge/category/state/timeline types; `RelationshipEvidence.stronger` ranks/merges evidence), `ActivityInterpreter` (raw tool events → normalised events), `ActivityGraph` (reducer → nodes/edges/clusters/focus/timeline), `ActivityClassifier` (path → cluster), `OutputParsers` (Gradle/compiler/test + adb/lint/detekt/ktlint **console** output, plus Android **device** signals — `adb install` outcome, emulator/device launch errors, and `logcat` crash/ANR extraction), `NavGraphParser` (Android nav-graph destinations → `NAVIGATES_TO`), `ClusterCollapser` (fold repeated finished command/test/gradle history into per-cluster aggregates), `ReportParsers` + `BuildReportScanner` (**structured report files** — JUnit/detekt/ktlint/lint XML + SARIF, read off-EDT after a build/test command), `SourceStructureParser` (package/type-imports/test-target from source text), `ActivityColorRole` (state → theme role), `ActivityMapRenderer` (headless PNG preview). The graph links a command/gradle/test node to the results it produced (`PRODUCED` edge, tagged with `COMMAND_OUTPUT` evidence) and touched files to imported/tested/navigated project files (`IMPORTS`/`TESTS`/`EXTENDS`/`IMPLEMENTS`/`NAVIGATES_TO` edges); error/warning→file (`AFFECTED_BY`) edges also carry `COMMAND_OUTPUT` evidence. Structural edges carry `RelationshipEvidence` (`EvidenceSource` + human explanation) so the inspector **and hover tooltip** show **why** a relationship exists. |
| `process/ClaudeSession.kt` | Owns one persistent `claude -p` process; stdin/stdout stream-json plumbing; control-protocol responses; `--mcp-config` wiring |
| `process/ClaudePathResolver.kt` | Finds the `claude` binary in GUI-launched IDEs (login-shell PATH) |
| `ide/IdeServer.kt` | The `ide` MCP **WebSocket** server (selection, open editors, `openDiff` → native diff, **scoped `getDiagnostics`**, …). Path-taking tools are gated by `PathAccessPolicy`. |
| `ide/PathAccessPolicy.kt` | Platform-free (unit-tested) path guard: canonicalises + classifies open/diff/write targets as inside-project / outside / **sensitive** (refuse); writes outside the project need an extra confirm |
| `ide/ProjectStructureEnricher.kt` | **PSI Phase 2a**: enriches files Claude touched with real project structure via **UAST** (imports + class→super/interfaces resolved package-aware for Kotlin & Java; test→prod via `FilenameIndex`; package/module metadata), plus Android **nav-graph** resources (`res/navigation/*.xml` → destination screens via `NavGraphParser` + unique-name resolution → `NAVIGATES_TO`). Off-EDT in a smart-mode `ReadAction.nonBlocking`, once per path, project files only. Emits `StructuralRelation`/`FilePackage`. Build depends on `com.intellij.java`. |
| `ide/InteractionCoordinators.kt` | Platform-free `ApprovalCoordinator` + `DiffReviewCoordinator` + `QuestionCoordinator` — decouple approval/diff/question **decisions** from Swing so the UI and the sandbox test bridge drive identical one-shot logic (no bypass). `<projectService>`s. |
| `interaction/*.kt` | Platform-free, unit-tested **AskUserQuestion** core: `AskUserQuestionModels` (provider-neutral `UserQuestionRequest`/`UserQuestion`/`UserQuestionOption` + `ParseResult`), `AskUserQuestionParser` (protocol JSON → model; refuses empty/fabricated questions), `QuestionFormState` (radio/checkbox/Other selection + validity + `resolvedAnswers`), `AskUserQuestionResponseBuilder` (answers keyed by full question text, original `questions` preserved, non-mutating). The Swing view is `ClaudePanel.AskUserQuestionBlock`. |
| `ide/SightlineTestBridge.kt` | **Sandbox-only** MCP tools (`sightline.test.*`) gated by `TestBridgeGuard` (`-Dsightline.testBridge=true`): inspect/resolve pending approvals, diffs & **questions** (incl. `simulate_question` to inject one + `respond_question` to answer via the production builder), capture the tool window. See [docs/TESTING.md](docs/TESTING.md). |
| `settings/ClaudeSettings*.kt` | Persisted settings + Settings UI |

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

**Headless preview (dev):** `activity/ActivityMapRenderer.kt` paints an `ActivityGraph` to a PNG
using plain `java.awt` (no platform deps), so the map can be eyeballed without launching the IDE.
`ActivityMapPreviewTest` drives a busy fixture and writes `build/activity-map-preview-{dark,light}.png`
— regenerate with `./gradlew test` and open/read those files. (The live panel is theme-aware and
animated, so the preview is representative, not pixel-identical.)

The transcript is a `Scrollable` `JPanel` (BoxLayout Y) of block components. Each assistant turn is
an `AssistantTurn` holding `TextBlock` (streamed markdown), `ThinkingBlock` (collapsible),
`ToolCard` (collapsible, icon + summary + diff/result), `ApprovalBlock`, and `AskUserQuestionBlock`.
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
  (visible cap, default 200), `activityMaxRetained` (session cap, default 500).
- `permissionMode` (default `auto`) — set via the composer mode chip or the Settings dropdown.
  `auto` (⚡) is **model-gated** (Sonnet/Opus only; silently falls back to `default` on Haiku).
  Composes with `interactiveApproval`: Manual prompts all, acceptEdits only commands, Bypass never prompts.
- `extraArgs` — advanced: extra CLI args appended verbatim to every `claude` invocation.
