# Backlog

Deferred work, roughly in priority order. See [PROTOCOL.md](PROTOCOL.md) for CLI facts and
[../CLAUDE.md](../CLAUDE.md) for architecture.

## Agent Activity Map — Phase 2: IntelliJ / PSI enrichment

Currently the map's relationships are **path-heuristic only** (cluster classification, patch→file,
error→file, sequential trail). Phase 2 replaces guesses with real project structure, off the EDT:

- Package/module membership from PSI (`file belongs to package`, `module`).
- Class relationships: `implements` / `extends` / `references` / `calls` via PSI + `ReferencesSearch`.
- Import edges between files.
- Test → production-class targeting (`tests` / `tested-by`).
- ViewModel → Composable state consumption; Repository → service; UseCase → Repository.
- Architectural-pattern detection with **evidence** (MVVM, repository, DI, navigation graph, …),
  shown in the details panel ("why this pattern was identified").
- Rules: run in read actions / background tasks, respect indexing (DumbService), lazy-expand +
  cache classifications, cancel on session end. Never index the whole project per prompt.

## Structured stream gaps (interpreter robustness)

Improve fidelity where the CLI's stream lacks structure:

- Real diagnostics: wire `ide` `getDiagnostics` (currently a stub) and/or `mcp__studio__get_file_problems`
  so errors/warnings attach to files without parsing Bash output.
- A denied tool still renders as attempted activity — reconcile with `can_use_tool` deny so denied
  actions are marked distinctly (or not shown).
- Broaden command parsing: Maven, Bazel, npm/yarn/pnpm scripts, `adb`, lint/detekt/ktlint output.
- Correlate `mcp__studio__build_project` / `execute_run_configuration` results to build/test state.
- Confidence tuning + a legend for node colours/states.

## Agent Activity Map — Phase 3: timeline replay & persistence

- Session history: pause/resume live updates, select a past event, **replay** graph activity.
- Filter timeline by event type; jump-to-node from a timeline entry (partially done).
- Persist session metadata only (node ids/labels/paths/categories/timestamps/summaries — never
  source contents or reasoning); "keep recent sessions" option; "clear all history".

## Smaller polish / ideas

- Legend for node colours & states.
- Minimap / better fit for large graphs; group low-value nodes into collapsed clusters.
- Keyboard navigation of nodes; focus-follows-agent auto-centering toggle.
- Live verification in Android Studio (still open): IDE-side tool behaviours, openDiff dialog,
  double-prompt interaction between interactiveApproval and permission modes.
