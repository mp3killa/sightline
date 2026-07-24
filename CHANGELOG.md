# Changelog

All notable changes to Sightline are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.2.0-beta — 2026-07-24

Still beta. This release acts on an external UX review and adds native diagram rendering.

### Activity map
- **Graph modes.** A mode picker selects a lens over the graph — Everything, Changes, Problems, Data
  flow, Navigation, Test coverage — showing the relevant nodes *and edges* rather than filtering nodes
  alone.
- **Shapes, not just colour.** Nodes are drawn by type — files as pages, commands as terminals, tests
  as hexagons, warnings as triangles, errors as diamonds — so the map reads without relying on colour,
  and edges now carry directional arrowheads. A compact, toggleable legend key sits in the corner.

### Status and completion
- A recovered command failure (a single non-zero exit the agent then works around) no longer turns the
  whole run red and keeps it red while work continues. It is a separate **health** signal now — a
  non-sticky note and a tally — leaving the status line free to show the current operation.
- The turn's end is a structured **completion card**: a clear terminal state (Completed / Completed
  with warnings / Stopped) with the run metadata and any observed warnings, in place of one grey line.

### Composer
- A message queued behind a running turn shows as an editable **card** — with **Edit** (pull it back
  into the composer) and **Cancel** — instead of an easy-to-miss count.
- While the agent is working and the input is empty, the composer collapses to a single row and
  restores on focus, giving the transcript more room without hiding the field.

### Diagrams
- ` ```mermaid ` **flowcharts and state diagrams render as native diagrams** — shapes for decisions and
  states, directional and styled edges, start/final pseudostates — with a Source toggle and Copy. Other
  mermaid types fall back to a code block. Optional: Sightline can tell Claude it renders mermaid so it
  reaches for a diagram where one illustrates better (both on by default, in Settings).

## 0.1.0-beta — 2026-07-20

First public release. Beta: the interfaces described here may change before 1.0.

Development versions 0.1–0.6 were never published; this is the first release with a public artifact,
and the version was reset to reflect that. Sightline is **source-available** software — see
[LICENSE](LICENSE) — and free to use during the beta.

### Chat and review
- Streaming replies with token-level rendering, extended thinking, and a per-turn footer showing
  duration, turns and cost.
- Markdown rendering with syntax-highlighted code fences, GFM tables, task lists and callouts, using
  the IDE's own highlighter and colour scheme.
- Tool calls as collapsible cards; routine reads recede to a compact row while failures, denials and
  edits keep card weight.
- File edits render as a diff — unified or side-by-side by available width — before they apply.
- Structured `AskUserQuestion` support: radio, checkbox and free-text, rather than raw JSON.
- Paste an image (⌘V after a screenshot, "Copy Image" in a browser) to attach it to the message —
  shown as a removable thumbnail chip, downscaled and encoded in memory, sent as a base64 image block
  alongside your text, and never written to disk. Pasted *files* attach as `@path` chips.
- Messages sent mid-turn are queued rather than silently dropped; a message queued with images keeps
  exactly the images it was sent with.
- When the CLI exits unexpectedly, its own error text is shown alongside the exit code, rather than
  leaving a bare number and the IDE log as the only way to find out why.

### Permissions and safety
- Five permission modes, with `auto` as the default. `auto` requires a capable model, and if the CLI
  quietly falls back to a different mode Sightline says so rather than letting the mode chip claim a
  policy that is not in force.
- Inline Allow / Allow-always / Deny before a tool runs. A denial is recorded as a denial, never as
  an error, and a denied edit never renders as though it happened.
- `PathAccessPolicy` refuses credential and IDE-internal locations outright and requires explicit
  confirmation for writes outside the project.
- `AndroidActionPolicy` always confirms device actions that destroy data, whatever the permission
  mode. Anything it cannot classify is treated as destructive.

### Agent Activity Map
- Live force-directed graph of what Claude is *observably* touching. It makes no claim to reveal
  hidden reasoning.
- Every structural relationship carries its evidence, so the inspector can show *why* an edge exists.
- Progressive label density, cluster collapsing, and a lens system for filtering by nodes and edges.

### Android
- Build variant, module, applicationId, SDK levels, device and running process supplied with each
  message, each fact labelled with where it came from. A value read from a build output reads
  `(last build)` rather than passing as current.
- Typed Gradle failure diagnosis across KSP/KAPT, manifest merge, duplicate class, unresolved
  dependency, R8, version mismatches and more. An unrecognised failure says so and offers no cause.
- Crashes resolve to the deepest frame in your own code, and attach to that file in the graph.
- Test selection from changed files, reporting what was changed but *not* covered.
- Logcat capture with redaction that is on by default and fails closed.
- Device actions and accessibility recipes that capture current state so they can be reverted.
- Screen inspection, Compose source analysis, manifest audit, route and deep-link analysis.

### Privacy
- No telemetry, analytics or usage statistics.
- No conversation persistence — the transcript exists only in memory.
- Credentials are never requested, read, stored or transmitted.

### Known limitations
- Live Compose preview rendering and the visual before/after loop are not implemented; see
  `docs/ANDROID.md`.
- The Compose semantics tree requires `testTagsAsResourceId` in the app under test.
- Tested on Android Studio. Verified compatible with IntelliJ IDEA 2025.3, but not exercised there.

