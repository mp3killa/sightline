# Testing & the sandbox test bridge

How the plugin is verified, and how to drive the **interactive** flows (permission cards, diff review)
without clicking pixels. See [../CLAUDE.md](../CLAUDE.md) for architecture and [BACKLOG.md](BACKLOG.md).

## What's covered by plain `./gradlew test`

Platform-free, deterministic JUnit4 (~130 tests, no IDE fixture — needs
`testFramework(TestFrameworkType.Platform)` so the test JVM boots):

- `activity/*` — interpreter, graph reducer, classifier, output/report parsers, colour roles, the
  9-step sequence, `BuildReportScanner`.
- `ui/state/*` — status/composer/workspace/responsive/transcript/permission presentation logic.
- `ide/PathAccessPolicy`, `ide/InteractionCoordinators` — path guard + the approval/diff decision logic.

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
| `sightline.test.list_pending_interactions` | pending approvals + diffs with **opaque ids** + `availableActions` |
| `sightline.test.respond_permission` | `{interactionId, decision: ALLOW\|ALLOW_ALWAYS\|DENY}` → `ApprovalCoordinator.respond` |
| `sightline.test.respond_diff` | `{interactionId, decision: ACCEPT\|REJECT}` → `DiffReviewCoordinator.respond` |
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
`diff.accept/reject`, `composer.send`, `workspace.chat/activity`, `toolWindow.root`. Set via the getter
explicitly (`getAccessibleContext()`), because a raw `JComponent`'s inherited `accessibleContext` field
is null-until-lazy.

## Running things

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew test                 # the ~130 headless unit tests
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
