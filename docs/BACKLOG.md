# Backlog

Deferred work, ordered for **Marketplace publication** — trust/correctness first, differentiators later.
The Agent Activity Map already demonstrates the core promise (showing what the agent touches while it
works), so publication is **not** gated on PSI enrichment or replay. See [PROTOCOL.md](PROTOCOL.md) for
CLI facts and [../CLAUDE.md](../CLAUDE.md) for architecture.

Guiding principle: correctness logic lands as **platform-free, unit-tested** classes (mirroring
`activity/` and `ui/state/`), with thin Swing on top — so P0 work is verifiable without launching an IDE.

## Already shipped (was stale in the old backlog)

These were listed as deferred but already exist; don't re-scope them:

- Node colour/state **legend** (`ActivityMapPanel.showLegend`).
- **Filter / fit / clear / reduce-motion**, and a layout-animation pause (button reads
  "Pause layout animation" — note it freezes *layout*, not event recording; see the Freeze/Follow split below).
- Node **inspector**: relationships, confidence, state, timestamps, open/reveal, pin, hide.
- **Timeline** jump-to-node (centres the associated node).
- Composer **permission-mode names** (Ask / Auto-edit / Plan / Auto / Unrestricted) — `ui/state/PermissionModes`.
- **Status priority reducer** (`ui/state/StatusModel`) — a late "Thinking" no longer clobbers "Editing X.kt".
  (Interpreter still *emits* a Thinking status; the reducer demotes it. Verify, don't rebuild.)

---

# P0 — before external testers (Release Candidate 0.1)

Ordered cheapest-first within the release. Items 1–3 are pure code; 4 gates on a real IDE; 5 follows.

## 1. `getDiagnostics` honesty (cheap, high trust)

`IdeServer.getDiagnostics` returns a literal `"[]"` while still being advertised in `tools/list`. Claude
can read "no diagnostics" as "code is clean." Return a structured, scoped result instead:

```json
{ "available": true,
  "files": [ { "path": "…/ClaudePanel.kt",
    "problems": [ { "severity": "ERROR", "message": "Unresolved reference", "line": 214, "column": 17, "source": "Kotlin" } ] } ] }
```

- Scope: current file, open files, recently edited, or an explicitly requested path — **never** a
  project-wide sweep (thousands of low-value nodes on a large Android project).
- During indexing return `{ "available": false, "reason": "IDE indexing is in progress" }`. Never `[]`
  when data couldn't be collected.
- Retrieval: background read action, only after highlighting is available, cancellable, cached by
  document modification stamp.

## 2. Stop swallowing protocol errors (cheap)

`ClaudePanel.handleEvent` has an **empty** `catch`, and the parse guard silently `return`s; a malformed
approval/result event can leave the UI inconsistent with no trail. Replace with:

- Structured plugin logging (`thisLogger()`): event type/subtype + safely-truncated payload metadata.
  **Never** log full prompts, source, tokens, or secrets.
- A local malformed-event counter; non-blocking UI recovery.
- Surface a visible message only when the failure affects the session
  ("Claude response could not be processed. See plugin logs."). Optional stream deltas can stay silent.

## 3. Workspace-boundary protection (the real safety fix)

The `ide` bridge accepts arbitrary paths for `openFile` / `openDiff` / `saveDocument`, and an accepted
diff is written straight to the requested path — `writeFile` will even `createDirectoryIfMissing`
anywhere on disk. Before any external tester:

- Put the policy in a **platform-free, unit-tested** helper (canonicalise → resolve symlinks →
  classify: inside-root / outside-root / sensitive).
- Policy: inside project → follow the permission mode; outside project → always ask regardless of mode;
  sensitive locations (IDE config, `.ssh`, credentials, unrelated home files) → reject by default.
- Reads outside the project only when explicitly intended; writes outside require an extra clear
  confirmation showing the **full external path** prominently.

## 4. Live Android Studio verification (release gate, not "polish")

Validates 1–3 and settles the open **double-approval** question (does `can_use_tool`'s ApprovalBlock
*and* `openDiff`'s Accept/Reject both fire for one edit? — reproduce before refactoring). Verify:

- CLI missing / unauthenticated / outdated. New & existing Android projects. Indexing. Kotlin & Java.
- Read-only files, unsaved docs, file create, file delete. Diff accepted / rejected. Tool denied.
- Claude stopped mid-tool. AS closed mid-session. Multiple projects open. Light & dark themes.
- All five permission modes. Stale IDE lock-file cleanup + reconnection after unexpected shutdown.
- `IdeServer.onEdt` uses `invokeAndWait` from the WS thread while a **modal** diff dialog is open —
  check for deadlock/UI-block under this pass.

## 5. Correct denied / cancelled activity

Nodes are created from `tool_use` before the deny control-response arrives, so a denied tool currently
looks executed. `ActivityNodeState` has no denial/cancellation states. Add an explicit lifecycle:

```
REQUESTED → AWAITING_APPROVAL → APPROVED → RUNNING → SUCCEEDED | FAILED
                              ↘ DENIED (user decision — NOT an error)   ↘ CANCELLED
```

- Visuals: pending = dotted amber; approved/running = active; denied = muted + blocked icon;
  failed = red; cancelled = grey.
- Correlate `request_id` / `tool_use_id` / tool name / path so protocol events for one operation reconcile.
- Transcript: `Edit blocked · ClaudePanel.kt` / "Denied by user". Timeline keeps the attempt, but the
  map must **not** add a success edge or mark the file modified.
- If item 4 confirms the double-prompt: define one lifecycle so Ask mode uses the diff review *as* the
  approval (no duplicate generic card for the same edit); Auto-edit applies then offers View diff + Undo;
  Plan = no writes; Unrestricted = no prompt but still records activity. Commands keep the normal card.

---

# Marketplace mechanics (parallel track — start now)

Independent of features; gates the listing. Missed until now.

- **Trademark / naming.** A third-party plugin on Anthropic's "Claude" mark driving their CLI can draw
  Marketplace-review or vendor objection. Decide early (e.g. "Panel for Claude Code", clearly *unofficial*,
  "requires the separately-installed Claude CLI + subscription"). Highest-leverage — can invalidate the listing.
- **Compatibility range + `verifyPlugin`.** The build targets `local(Android Studio)` (build 261) — fine
  for dev, wrong for a portable artifact. Set real `sinceBuild`/`untilBuild`; pass the IntelliJ Plugin
  Verifier (no internal-API usage, binary compat across the range).
- **Plugin metadata.** `<vendor>`, real `<description>` + `<change-notes>`, plugin icon, honest
  "requires Claude CLI" wording.

---

# P1 — before public Marketplace release (0.2)

## 6. Android-first command interpreter

Skip Maven/Bazel/npm/yarn/pnpm unless targeting IntelliJ products beyond Android Studio.

**Done (console parsing):** Gradle & wrapper, unit + instrumented/connected tests, `adb`
(install/uninstall/shell-launch/logcat with human labels), Android lint / detekt / ktlint recognised
as static-analysis runs whose output is parsed into **file-attributed** errors/warnings
(`OutputParsers.parseAnalysisDiagnostics`, `analysisTool`, `adbAction`) — all unit-tested.

**Done (graph edges):** command/gradle/test nodes now link to the results they produced via a
`PRODUCED` edge (`ActivityGraph.linkProduced`, tracked by `lastCommandNodeId`, only advanced by
command-type events so interleaved reads don't steal it) — so the map shows command → build outcome /
test report / diagnostic, and diagnostics still link on to the file they name (`AFFECTED_BY`),
completing the command → … → referenced-files chain. Unit-tested.

**Done (structured report files):** `activity/ReportParsers` (JUnit XML, detekt/ktlint Checkstyle XML,
Android lint XML, SARIF — secure XML, no XXE) + `activity/BuildReportScanner` (platform-free, off-EDT:
bounded walk of `build/test-results` + `build/reports`, only files newer than the command start).
`ClaudePanel` records build/test/analysis Bash commands and, on their result, scans the reports on a
pooled thread and feeds `TestReported`/`ErrorObserved`/`WarningObserved` — richer and more reliable
than console scraping. All unit-tested (temp-dir fixtures, stale-file + prune cases).

Live-verified against real Gradle output (Android lint XML + SARIF, JUnit XML) — which surfaced and
fixed two real-world issues fixtures missed: lint emits the **same run as both XML and SARIF** (prefer
the XML sibling), and the two formats disagree on paths (relative vs absolute) and line numbers, so
report paths are now **normalised to absolute** before dedup — a finding attaches to the same file node
an edit created.

**Remaining:**
- Task exit-status correlation (map non-zero exit → build failure even without a parseable summary).
- emulator/device launch outcomes; `logcat` crash extraction.
- `PRODUCED` correlation is sequential (best-effort); parallel tool_use in one turn could mis-link —
  revisit if/when the CLI emits parallel results, ideally threading the tool_use_id into result events.

## 7. PSI Phase 2a — cheap, reliable relationships only

**Done (first slice):** `ide/ProjectStructureEnricher` enriches files Claude touches, off the EDT in a
smart-mode (post-indexing) `ReadAction.nonBlocking`, once per path, only for touched files — never a
project sweep. It emits **file → imported project file** (`IMPORTS`) and **test file → production file**
(`TESTS`) edges, plus **package/module** as node metadata (`FilePackage`). Parsing is the platform-free,
unit-tested `SourceStructureParser` (package + PascalCase type imports + test-target); resolution is
index-based (`FilenameIndex`) and links only on a **unique** project-file match, so an edge always points
at a file that really exists. The graph reduction (`StructuralRelation`/`FilePackage`) is unit-tested and
background-only (no focus/trail/status change). Note: chosen over UAST/Kotlin-PSI to avoid a language-
plugin dependency; relationships resolve to real files but by short name, not full type resolution.

**Remaining:**
- **Class → superclass/interfaces** (`EXTENDS`/`IMPLEMENTS`) — deferred: needs real PSI/UAST to be
  reliable across Kotlin/Java (regex supertype parsing risks wrong hierarchy edges). Requires adding the
  Kotlin/Java plugin dependency + a `BasePlatformTestCase` fixture; edge types already exist.
- Import precision: resolve to the exact declaration (package-aware) instead of unique-short-name, so
  same-named types in different packages don't get skipped/mislinked.
- Android resource → referencing source; navigation destination → screen/composable.
- Cache by modification stamp (currently once-per-path-per-session; re-enrich on edit).
- Lazy tiers: references/usages on select → call relationships only on explicit "Calls" / blast-radius.
- A `BasePlatformTestCase` covering the enricher's real index resolution (only the parser + graph
  reduction are unit-tested today; the platform IO/index layer is verified via sandbox load).

## 8. Evidence provenance (before any pattern detection)

Attach evidence to relationships so "architecture detection" is trustworthy, not decorative:

```kotlin
data class RelationshipEvidence(val source: EvidenceSource, val confidence: Float,
    val explanation: String, val sourcePath: String?, val sourceSymbol: String?)
enum class EvidenceSource { STRUCTURED_TOOL_EVENT, PSI_DECLARATION, PSI_REFERENCE, IMPORT,
    NAMING_HEURISTIC, PATH_HEURISTIC, COMMAND_OUTPUT }
```

Inspector then shows *why* (e.g. "DriverRepositoryImpl implements DriverRepository").

## 9. Cluster collapsing & basic keyboard access

- Collapse: hide low-value labels → collapse historical nodes by category → aggregate repeated
  command/test nodes → "Show more" → better Fit. (Minimap only if navigation is still hard — the panel
  already has Fit + node caps, so it isn't the bottleneck.)
- Keyboard (pre-public): Tab into graph, Esc closes inspector, Enter opens selected file, no accidental
  delete, keyboard access to Chat/Activity switch and approval actions.

---

# P2 — after launch

## 10. Phase 2b — on-demand deep relationships (0.3)

Deferred because false claims / graph explosions concentrate here: full call graphs, broad
`ReferencesSearch`, ViewModel→Composable / Repository→service / UseCase→Repository inference, automated
pattern detection (with the evidence model above). Focus-follows-agent (default on; suspend on any user
pan/zoom/drag/select/inspector; small "Resume following"). Advanced keyboard: arrow-key spatial nav,
search-driven selection, next/prev active event.

## 11. Timeline replay & persistence (0.4)

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

## 12. Health / preflight panel

Small diagnostic screen: Claude CLI found + version, auth, IDE bridge, workspace, permission mode, AS
version, activity events, diagnostics availability. Actions: Recheck, Open settings, Copy sanitised
report, Open logs. High support-cost saver for early users.
