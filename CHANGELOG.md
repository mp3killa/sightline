# Changelog

All notable changes to Sightline are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.5.0 — 2026-07-27

**First release on the stable channel.** Same code as 0.5.0-beta, promoted after a human pass over the
interactive approval and diff paths in a live IDE. Until now every release went to the opt-in beta
channel, which the plugin browser and the listing page do not read — so the listing reported itself as
incompatible with every IDE, having nothing published where those two look.

The channel is still derived from the version and never chosen by hand: a `-beta` suffix publishes to
beta, a bare version to stable. Shipping stable stays a deliberate edit, and a change to how approvals,
denials or writes behave puts the next release back on beta until someone has driven it by hand again.

## 0.5.0-beta — 2026-07-27

Supported platform narrowed to Android Studio Quail 2 and later.

- **Requires Android Studio Quail 2 (2026.1.2) or later.** The previous floor was IntelliJ platform 253
  (2025.3), chosen for reach. Reach was costing correctness: 253 has no `ReadAction.computeBlocking`, so
  the plugin had to keep calling `ReadAction.compute`, which is deprecated on every build people actually
  run — the Marketplace's own report against Android Studio 261 flagged all three uses. Supporting one
  platform properly beats claiming two and being deprecated on the current one.
- **No deprecated platform API left.** The three `ReadAction.compute` calls now use `computeBlocking`.
  The remaining verifier findings are `ToolWindowFactory` interface members that Kotlin materialises for
  any implementor — informational, and not avoidable without abandoning the interface.
- This is a floor, not a product restriction: IntelliJ IDEA 2026.1+ is the same platform build and can
  still install Sightline, with the Android features degrading as they always have. Android Studio
  remains an upgrade, never a prerequisite.
- The pre-release verifier now runs against **Android Studio** at that floor rather than IntelliJ IDEA
  Community 2025.3. Verifying against an IDE the plugin no longer claims is not a weaker check — it is a
  check of the wrong thing, and it is why the deprecations above shipped reported as "0 problems".

## 0.4.0-beta — 2026-07-26

Mid-turn follow-ups.

- **A message sent while Claude is working now reaches the work in progress.** Pressing Enter mid-turn
  used to park the message until the turn was over, by which time the agent had usually finished going
  the way you were trying to redirect it. It is now written straight into the running session, and the
  CLI folds it into the current turn at the agent's next step — so "also update the tests" or "stop, wrong
  file" arrives while it still matters. The bubble is captioned *"Sent while Claude was working"* so the
  transcript still reads in the order things happened.
- Queuing remains for the one case where nothing can receive a message — a Stop in flight, or a session
  that has exited — and those messages still go out with the next turn. If the session dies in the instant
  between the check and the write, the text stays in the composer and you're told, rather than a message
  that appears sent reaching nobody.

## 0.3.1-beta — 2026-07-26

Compatibility fix.

- **Installs on Android Studio 2026.1 (build 261) again.** The previous build stamped an upper build
  limit into its descriptor and the Marketplace marked it incompatible with 261. The upper limit is
  removed, so Sightline installs on build 253 and every later build.

## 0.3.0-beta — 2026-07-24

Still beta.

### Commit messages
- **Generate a commit message from your changes.** A new button in the commit tool window's message
  toolbar reads the diff of the changes you're committing and drafts a message into the field. It runs a
  **fast, low-effort model by default** for a near-instant draft; the model — and optional style guidance
  like "Use Conventional Commits" — are configurable in Settings → Tools → Sightline → *Commit messages*.
  It runs as a one-shot, tool-free CLI call (no session, no repo access beyond the diff it's given), and a
  failure is reported as a balloon rather than silently doing nothing.
- **Honours your project's stated commit style.** If the project's own docs describe a commit convention
  — a "Commit messages" section in CONTRIBUTING, a `.gitmessage` template, a commitlint config, or the
  same in CLAUDE.md / AGENTS.md — the draft follows it automatically, without your having to repeat it.
- The button **disables while a draft is generating** and re-enables once the message lands, so a second
  click can't race the first.

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

