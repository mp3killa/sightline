# Testing & the sandbox test bridge

How the plugin is verified, and how to drive the **interactive** flows (permission cards, diff review)
without clicking pixels. See [../CLAUDE.md](../CLAUDE.md) for architecture and [BACKLOG.md](BACKLOG.md).

## What's covered by plain `./gradlew test`

Mostly platform-free, deterministic JUnit4 (**644 tests**, green as of 2026-07-20; no IDE fixture for
the bulk of them — but the run needs `testFramework(TestFrameworkType.Platform)` so the test JVM boots):

- `activity/*` — interpreter, graph reducer, classifier, output/report parsers, colour roles, the
  9-step sequence, `BuildReportScanner`, `ClusterCollapser`, `MapDensity`, `LabelPlacement`,
  `NavGraphParser`, `AndroidResourceParser`, `SourceStructureParser`, plus the headless
  `ActivityMapPreviewTest` (writes `build/activity-map-preview-{dark,light}.png`).
- `ui/state/*` — status/composer/workspace/responsive/transcript/permission/scroll-follow/
  timeline-dock/completion-summary presentation logic.
- `ui/markdown/*` — doc parser, file-ref detection, code-block collapse, table layout, fence
  languages, plus a Swing render smoke test.
- `interaction/*` — AskUserQuestion parser, form state, response builder.
- `health/*` — checker, report ranking, sanitiser (+ a `HealthDialog` smoke test), and the Android
  rows: that they're absent outside an Android project, and that "adb couldn't answer" stays UNKNOWN
  rather than collapsing into "no devices connected".
- `android/*` — the Android core (docs/ANDROID.md). Worth knowing what these actually pin down, because
  each guards a specific way of being confidently wrong:
  `AndroidFactsTest` — a value can never carry the UNKNOWN tier, and the ladder short-circuits;
  `AndroidSdkLocatorTest` — discovery order, and Gradle's `C\:\\Users\\…` escaping in `local.properties`;
  `VariantNameTest` — the flavour-order ambiguity (`stagingDebug` is both a valid flavour name and a
  valid flavour+buildType split), and that an unmatched name reports *no* flavours rather than a wrong one;
  `AdbOutputParsersTest` — real `adb devices -l` output, every device state kept distinct, and that an
  unrecognised line costs one line rather than the listing;
  `AndroidActionPolicyTest` — the gate. Chained commands take the **worst** segment's risk (`adb devices
  && adb uninstall …` is not a device listing), `-s <serial>` doesn't hide the sub-command, and anything
  unrecognised — including a quoted `adb shell '…'` — is confirmed rather than assumed safe;
  `AndroidStorePolicyTest` — the persistence carve-out's guardrails: paths outside the project are
  refused rather than stored absolute, and an unknown schema version is discarded rather than migrated;
  `GradleModuleParserTest` — brace-matched blocks (a regex stops at the first flavour and reports one
  where there are five), both quote styles, and `alias(libs.plugins.android.application)`;
  `OutputMetadataParserTest` — real AGP output, and that a wrong-typed field costs that field only;
  `AndroidContextFormatterTest` — that a build-output variant renders as `(last build)`, that removing a
  chip genuinely removes the fact from the prompt, and that a tight strip budget drops **whole segments**
  rather than cutting one in half.
- `ide/PathAccessPolicy`, `ide/InteractionCoordinators`, `ide/QuestionCoordinator` — path guard +
  the approval/diff/question decision logic; `SightlineTestBridgeQuestionTest` drives the bridge.

Several **do** need an IDE fixture (`BasePlatformTestCase`): `ProjectStructureEnricherTest` /
`ProjectStructureEnricherKotlinTest` (UAST enrichment for Java and Kotlin), the Markdown and health
render smoke tests, and `ChatLayoutPreviewTest` (below).

## Headless visual review (read the PNGs)

Two tests render to PNG so **layout and rendering can be reviewed without launching the IDE** — the
only automated visual channel available, and one that has already caught real defects:

| Test | Writes | Covers |
|---|---|---|
| `activity/ActivityMapPreviewTest` | `activity-map-preview-{dark,light}.png` | The force-directed graph, label placement/collision |
| ″ | `activity-map-dense-{dark,light}.png` | A **61-node** graph — past `MapDensity.IMPORTANT_ABOVE` (40), so label **thinning** is actually exercised |
| `ui/ChatLayoutPreviewTest` | `chat-layout-{narrow,medium,wide}.png` | Panel layout at each `ResponsiveLayout` width class |
| `ui/ChatGalleryPreviewTest` | `chat-gallery-{light,dark,compact,queued}.png` | Every block type — Markdown (headings/lists/task lists/tables/fences/quotes/callouts), routine vs failed tool cards, an edit diff, the **ApprovalBlock**, and **AskUserQuestion** single- and multi-select — in **both themes**, plus `compact` (details off, showing the per-turn processing summary) and `queued` (a message parked behind a running turn) |

`ChatLayoutPreviewTest` builds a real `ClaudePanel` and seeds it through the **production event path**
(`ClaudePanel.renderProtocolLineForPreview` / `addUserMessageForPreview` — `@TestOnly internal` seams),
so a preview cannot drift from what a live session renders. Two headless gotchas are handled in
`layoutTree`: the tree has no peers, so `doLayout()` is driven down manually; and the responsive pass
runs from a queued `invokeLater`/`componentResized`, so the EDT queue is pumped between passes —
**without that pump the preview silently shows a stale layout** (this is exactly how the first version
of the test passed while the image showed the bug).

The gallery drives `control_request` through the same path, so the approval and question blocks are the
**real** ones a live session builds, not hand-made lookalikes. `ChatGalleryPreviewTest.applyTheme` sets
both `JBColor.setDark` *and* the editor colour scheme, then **verifies the resulting surface luminance**
before rendering — setting only the flag leaves a dark surface under light text, which is exactly how the
first "light" render came out dark and would have been filed as verified.

Alongside the images these assert the layout invariants, so regressions fail the build rather than
waiting to be noticed in a picture. Assert on something specific: an early version checked "a
`JScrollPane` exists somewhere", which the activity pane satisfied too, so it passed while the layout
was wrong. The image caught it.

**Track record — the defects this channel found that every existing assertion missed:** a narrow
panel still rendering the split; the header reading "Activity" while showing Chat; task-list markers
truncated to "…" (the marker slot was sized by list *kind*, and a task list is unordered); and every
literal `(`/`)`/`[`/`]` being **silently deleted from assistant prose** (dropped as "delimiter tokens"
globally instead of only inside a link label). Three of those were on screen while the suite was green.
A fourth pass added two more: a `ClaudePanel` that never released its project-service registrations on
dispose, and an assertion that passed because it matched the code fence's always-visible `Copy` rather
than the hover action it meant to check. **Look at the PNG.**

> [!WARNING]
> **A cached `test` task writes no PNGs.** Gradle can restore `test` from the build cache without
> executing it (the images aren't declared task outputs, so they are not restored either) — you then
> read a stale or missing image and believe you verified the current code. When the images matter, run
> `./gradlew test --rerun-tasks`, and check the file timestamps before trusting what you see.

> [!IMPORTANT]
> **The preview runs a different Look-and-Feel from the real IDE, and that gap hides real bugs.** The
> inline actions were `JButton`s carrying `JButton.buttonType = "square"`; on the live macOS IDE LaF that
> forces a fixed square with no room for a label, so they rendered as **two empty boxes** — while the
> preview, whose LaF ignores the property, looked perfect. Anything LaF-specific (native button types,
> platform UI delegates, font metrics) can only be judged in `runIde`. Prefer platform components
> (`ActionLink`) over client-property styling tricks, and assert the invariant instead of the pixels.
>
> Layout *timing* differs too: a detached tree isn't showing, so `revalidate()` never triggers a
> validation pass and the scrollbar model doesn't update the way it does live. Logic that depends on
> "layout has run by now" can pass here and fail in the IDE — or the reverse. Prefer designs that don't
> depend on validation ordering at all.

**This does not replace the live pass.** Anything needing a *click, hover, focus, drag or a live CLI
session* still needs a human in `runIde` — see [BACKLOG.md](BACKLOG.md).

**The report scanner is also verified against real Gradle output** (see below) — do that after touching
`ReportParsers`/`BuildReportScanner`, since real lint/JUnit XML diverges from synthetic fixtures.

## The coordinator seam (why interactive flows are testable)

Approval and diff decisions are **decoupled from their Swing widgets** so the same production logic can
be driven by a human *or* by automation — never a bypass path:

- `ide/ApprovalCoordinator` — `ClaudePanel` registers a `PendingApproval` (whose handler does the real
  CLI `control_response` + activity update + card refresh). The Allow/Deny buttons and the test bridge
  both call `coordinator.respond(id, decision)`, which runs that one handler.
- `ide/DiffReviewCoordinator` — `IdeServer.openDiff` registers a `PendingDiffReview` and blocks on its
  `CompletableFuture`. The Accept/Reject dialog **or** the bridge completes it; whichever is first wins,
  and an externally-resolved review auto-closes the dialog.

Both are plain classes (no platform imports) registered as `<projectService>`s in plugin.xml, so their
dispatch is unit-tested (`InteractionCoordinatorsTest`).

## The sandbox test bridge (`sightline.test.*`)

`ide/SightlineTestBridge` adds MCP tools to the plugin's own `ide` server, **only** when
`TestBridgeGuard.isEnabled()` → `-Dsightline.testBridge=true`. Never set in a normal install or the
Marketplace build, so the tools are absent from production `tools/list`.

| Tool | Purpose |
|---|---|
| `sightline.test.get_ui_state` | `{toolWindowVisible, workspace, sessionState, pendingApprovals, pendingDiffs}` |
| `sightline.test.list_pending_interactions` | pending approvals + diffs + **questions** with **opaque ids** + `availableActions` |
| `sightline.test.respond_permission` | `{interactionId, decision: ALLOW\|ALLOW_ALWAYS\|DENY}` → `ApprovalCoordinator.respond` |
| `sightline.test.respond_diff` | `{interactionId, decision: ACCEPT\|REJECT}` → `DiffReviewCoordinator.respond` |
| `sightline.test.simulate_question` | `{input: <AskUserQuestion input>}` → renders a synthetic question through the **real** UI path (so the flow is drivable without a live Claude session) |
| `sightline.test.respond_question` | `{interactionId, answers: {"<question>": ["<label>"]}}` or `{interactionId, cancel: true}` → builds the response via the production `AskUserQuestionResponseBuilder` and resolves `QuestionCoordinator` |
| `sightline.test.capture_tool_window` | renders the root component to PNG (MCP image block + dimensions) |

Every simulated decision is audit-logged (ids/decisions only — never prompt/source content). `openDiff`
waits up to `DIFF_TIMEOUT_MINUTES` (10) for a decision.

### Who can call these tools — important

The `ide` MCP server is connected to by the **`claude` CLI the plugin spawns**, *not* by an outside
agent. So the bridge is reachable by:

1. A **UI driver / integration test** running inside the sandbox (the intended path), or
2. A **separate MCP server** registered in an agent's own config that connects to the sandbox (future).

It is **not** reachable directly from an outside Claude session over the `studio` JetBrains MCP — that
MCP drives the IDE's editors/terminal, and **cannot see or click this plugin's tool window**. That limit
is why the bridge exists. (What an outside session *can* do today: run Gradle via `studio`'s
`execute_terminal_command`, and run/read the report-scanner + coordinator tests.)

## Accessible names

Controls a driver must find carry stable, semantic accessible names from `ui/A11yNames` (`sightline.*`),
**not** visible text (which changes during UX work): `approval.allow/allowAlways/deny`,
`diff.accept/reject`, `question.continue/cancel` plus the indexed `question.option.<q>.<o>` and
`question.other.<q>`, `composer.send`, `workspace.chat/activity`, `toolWindow.root`,
`activity.graph`, `transcript.jumpToLatest`. Set via the getter
explicitly (`getAccessibleContext()`), because a raw `JComponent`'s inherited `accessibleContext` field
is null-until-lazy.

## Running things

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew test                 # the 644 unit tests
./gradlew test --rerun-tasks   # same, and actually regenerates the preview PNGs (a cached run does not)
./gradlew buildPlugin          # the distributable zip
./gradlew runIde               # sandbox AS with the plugin, bridge OFF (production-like)
./gradlew runIde -PtestBridge  # sandbox AS with the test bridge ENABLED (sets SIGHTLINE_TEST_BRIDGE=true)
./gradlew verifyPlugin         # Marketplace API-compat check (downloads IDEs; slow)
```

**runIde launch fix (baked into build.gradle):** gradle-plugin 2.6.0 puts AS 2026.1's boot-classpath jar
at the wrong path for a macOS `.app` bundle — `<app>/lib/nio-fs.jar` instead of
`<app>/Contents/lib/nio-fs.jar` — so `-Djava.nio.file.spi.DefaultFileSystemProvider=…MultiRouting…`
can't resolve and the VM dies at init ("Failure when starting JFR on_create_vm"). `build.gradle.kts`
adds the correct `-Xbootclasspath/a:…/Contents/lib/nio-fs.jar` to every `RunIdeTask`. `-PtestBridge`
passes the bridge flag as the env var `SIGHTLINE_TEST_BRIDGE=true` (not a JVM arg, though either works).
Verified: sandbox boots, `Loaded custom plugins: Sightline for Claude Code`, `TestBridgeGuard` live.

### Verifying the report scanner against real Gradle output (do this after parser changes)

1. In a real Android project run `./gradlew testDebugUnitTest lintDebug` (via the `studio` MCP terminal
   or a shell).
2. Point `BuildReportScanner().scan(File(projectRoot), "<cmd>", 0L)` at it (a throwaway test) and check
   the emitted events. This caught two real bugs fixtures missed (lint's XML+SARIF double-report; the
   two formats disagree on relative vs absolute paths → normalise to absolute). Delete the throwaway.

### Deterministic interactive scenario (once a driver/external MCP is wired to the sandbox)

```
list_pending_interactions        # find the pending approval's opaque id
respond_permission id DENY       # drives the real handler
get_ui_state                     # sessionState back to non-WAITING; card shows "Denied"
capture_tool_window              # PNG proof
```
Use deterministic fixtures rather than depending on live Claude to produce every state.

## Deferred (documented, not built)

- **JetBrains Starter + Driver / Remote-Robot UI tests** that click the *real* buttons by accessible
  name and screenshot. Flaky + macOS-accessibility-gated (`java.awt.Robot` needs the permission), so
  it's the last layer. The accessible names above make it drop-in when wanted.
- **A dedicated external MCP server** wrapping the sandbox (`sandbox.launch/stop`, `ui.*`,
  `test.run_scenario`) so an agent can drive verification autonomously across sessions. Only useful once
  registered in that agent's MCP config.
- A11y names on the `openDiff` `DialogWrapper` OK/Cancel buttons (currently the diff is driven via the
  coordinator, which is enough for automation).
