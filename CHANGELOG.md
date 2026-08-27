# Changelog

All notable changes to Sightline are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.8.1 — 2026-08-27

**Stable.** The 0.8.x line reaches everyone, after a pass in a live Android Studio. Same code as
0.8.1-beta plus the default below.

### Changed

- **The panel opens on the conversation, not a split.** `activityViewMode` now defaults to `chat`.
  SPLIT is honoured from 520px up and gives the conversation 62% of the panel, so at a typical docked
  width the chat column landed at roughly 320px — under `ResponsiveLayout.MIN_CONTENT_WIDTH`, the floor
  this codebase itself sets for a readable conversation. Opening below your own stated minimum to show a
  graph nobody has asked for yet is the wrong trade. The Activity Map is unchanged and one click away on
  the header switch; whichever view you choose is remembered, and an explicit existing choice is not
  touched.

  This is a default, not a removal. The map is the only place a session's *relationships* — imports,
  tests, navigation — are visible at all, and it keeps earning its place there. What it had not earned
  was half the panel before you asked for it.

## 0.8.1-beta — 2026-08-27

No feature changes. One fix, two gates closed, and the "What's new" that 0.8.0-beta should have shipped
with — the Marketplace will not let a version's notes be replaced after upload, so tidying them costs a
version number.

### Fixed

- **A mid-turn follow-up could silently cost another message its revert action.** With file
  checkpointing on, an interjected message goes to the CLI as an ordinary user message and is replayed
  like one — but it was never added to the queue that matches replays to messages. Its replay then
  arrived with nothing of its own to match and popped somebody else's entry instead, so an unrelated
  message lost its "Revert Claude's file changes to here" with nothing on screen to say so. The queue now
  mirrors exactly what is written to the CLI's stdin, interjections included.

### Added (tests only)

Two of the three release gates this release owed a human are now closed automatically instead:

- `IdeServerFacesTest` starts the **real** `IdeServer` and drives both faces over real WebSockets:
  the two servers answer differently on one port and one token, no `android_*` tool is served where the
  CLI would filter it, no editor RPC is exposed to the model, an unknown path falls back to the safe
  face, and a bad token is refused on both. The other half — that the CLI's own ws client preserves the
  path — was probed against 2.1.235 and is recorded in docs/PROTOCOL.md §6.
- `StopFlowTest` drives a real panel through the production event path: a running `Bash` makes Stop say
  a command keeps running, a finished one does not, a live `Task` counts (its subagent may have a shell
  we cannot see the end of), a `Read` does not, and the in-flight set does not leak across a turn.
- `CheckpointQueueTest` pins the queue invariant above, including the two ways it was already broken.

## 0.8.0-beta — 2026-08-27

**Beta channel.** This release changes how Stop behaves, changes how the permission mode is applied,
adds an action that deletes work (file revert), and rewires the IDE bridge onto two MCP servers — the
standing rule is that a change to the approval, permission or write paths goes to beta until a human
has driven it in a live IDE. That pass is owed; see [docs/BACKLOG.md](docs/BACKLOG.md). Stable stays on
0.7.0 until then.

Built and verified against Claude Code CLI **2.1.235**. The previous release was built against ~2.1.215,
and the CLI's control protocol had moved a long way in between; the probes are recorded in
[docs/PROTOCOL.md](docs/PROTOCOL.md) §6 so the next person does not have to repeat them.

### Fixed

- **Claude could not see a single one of Sightline's Android tools.** All twelve — `getContext`,
  `auditManifest`, `investigateCrash`, `captureLogcat`, `analyzeRoutes` and the rest — were registered on
  the MCP server named `ide`, chosen at the time because `--mcp-config` was one hardcoded string and a
  second server meant editing it. The CLI carries a hardcoded allowlist of exactly two `ide` tools and
  filters everything else out **before the tool list reaches the model**. So the Android tool surface had
  never been reachable, and no amount of use would have produced evidence either way.

  The name `ide` cannot be given up — it is what makes the CLI route edits through the IDE's diff viewer
  and treat the connection as editor context — so the bridge now answers to two names on one socket, one
  port and one token, and tells them apart by the path the CLI connects to. Tools meant for the model
  live on `sightline`; the editor RPC stays on `ide` where the CLI expects it. The tools also lost the
  dots in their names, because the CLI rewrites those anyway and a name we do not control is a name that
  can drift.

- **The permission-mode chip was a no-op mid-conversation.** Choosing a different mode wrote the setting
  and repainted the chip, and that was all — the running session kept whatever policy it started with.
  The chip claiming a policy that is not in force is the exact failure the launch-time fallback notice
  exists to prevent; this was the same bug in a different place. The mode now switches in the running
  conversation, and a refusal — `Auto` needs Sonnet or Opus — is reported in the CLI's own words.

- **A `Task` rendered as a raw JSON dump** of the subagent's prompt. Subagents are now the most common
  tool there is, so the busiest part of a turn was also its least readable.

- **PRIVACY.md was misleading about where your conversation lives.** It said the transcript is "memory
  only — never written to disk" and "never existed on disk". True of Sightline, which persists nothing —
  but the `claude` CLI that Sightline runs writes every session in full to
  `~/.claude/projects/<project>/<session>.jsonl`, which is what makes `--resume` work. A reader took that
  table to mean their conversation was not on disk. It now names the CLI's copy, says who clears it, and
  says plainly that uninstalling Sightline does not remove it. `SECURITY.md` and `README.md` were
  corrected to match.

### Added

- **Stop interrupts the turn instead of killing the CLI.** The old Stop destroyed the process and relied
  on `--resume` to recover, which cost a relaunch, a re-read of every config file, and a reconnect of
  every MCP server — and left a window where nothing was reading stdin, which is why messages sent during
  a Stop had to be parked. Stop now sends an `interrupt`: the turn ends in about a third of a second and
  the process, the session and its servers all survive. Pressing Stop again escalates to ending the
  process, and a CLI too old to know the request falls back to that by itself, once, saying so.

  **A command Claude already started keeps running.** Neither an interrupt nor a kill stops it — verified,
  not assumed — so the notice says which is which. "Stopped", unqualified, would tell a developer their
  Gradle build had stopped when it had not.

- **Subagent activity, inside the card that spawned it.** A `Task` shows what was delegated and to which
  agent, then the steps the subagent takes as it takes them, then what it concluded. Its reasoning and
  its tools' raw output are deliberately left out — a subagent produces far more than its caller, and the
  card exists to answer "what did it do and what did it find". Long runs list the first dozen steps and
  then count the rest, which is stated rather than silently truncated. Off with a setting if you preferred
  the silence.

- **`Skill`, `SlashCommand`, `ExitPlanMode`, `BashOutput`, `KillShell` and `NotebookEdit`** are rendered
  as themselves instead of falling through to a JSON dump.

- **Claude Code's own commands, in the `/` actions menu.** Whatever your setup actually has — built-ins,
  your project's commands, your plugins' skills — with the descriptions and argument hints the CLI
  reports. Nothing is hardcoded, for the same reason the model list is not: the set depends on your
  project. Picking one fills the composer rather than sending, so a command that takes arguments gets
  them. Several, `/context` and `/cost` among them, run locally and cost no tokens at all.

- **You are told when the conversation is compacted.** Compaction replaces earlier messages with a
  summary — so Claude "forgetting" something from an hour ago is expected behaviour, not a fault, and it
  is worth knowing which one you are looking at. The notice says how much was compacted, and warns that
  detail from before that point may need repeating.

- **Rate limits are reported.** Approaching one, hitting one, and coming back from one. Only when the
  *status* changes, never on every tick, and the reset time is stated only when the CLI's own value is
  unambiguous.

- **"Revert Claude's file changes to here" — new, and off by default.** Right-click any message you sent
  and the files Claude edited since it are restored. Verified end to end against the CLI: edited, then
  restored byte-for-byte.

  Two things are said plainly at every click, because a revert that quietly covers only part of what
  happened is worse than none: it restores edits made with **Edit, Write and NotebookEdit only** — never
  changes made by a command Claude ran, and never a subagent's edits inside a `Task` — and a partial
  restore is reported as a problem, not a success. It also rides CLI behaviour that is not part of the
  documented interface, which is why it ships off, and why a CLI that drops it costs you a menu item
  rather than a revert you believed in.

## 0.7.0 — 2026-08-12

### Added
- **MCP servers you add now appear in the conversation you already have open.** Run
  `claude mcp add playwright …` while you are mid-conversation and its tools become available where you
  are — no restart, no lost context, no writing yourself a handoff note. Sightline notices the change,
  tells the running CLI about it, and reports what happened: which servers were added, how many tools
  each brought, and, if one could not start, the CLI's own reason (`Executable not found in $PATH: "npx"`
  says more than "playwright failed").

  This had been believed impossible — the standing advice, including from Claude Code itself, is that a
  session's tools are fixed at startup and only a full restart will do. That is true of the `/mcp`
  reconnect, but not of the control protocol Sightline is already speaking: a running session can be
  told to load a server, and the model can then call its tools in the same conversation. The behaviour
  is recorded, with the probes it came from, in `docs/PROTOCOL.md` §5.

  Details worth knowing:
  - It is **off the send path entirely**. The request can take 30 seconds to answer when a server hangs
    on connect, so nothing waits on it — you keep typing and sending throughout, and the result arrives
    as a notice when it arrives. A change noticed mid-turn is applied once that turn is finished, never
    into the middle of it.
  - Servers from the project's own checked-in **`.mcp.json` are reported but never started for you**.
    It is the same file format as a server you added yourself and a very different thing: it can arrive
    in your working tree from a `git pull` you skim-read. Those load when you next start a conversation.
  - A server's configuration can hold a credential in its `env`, so it goes to the CLI's stdin and
    nowhere else — never a log, never the transcript, never the activity map. A test enforces that no
    message this feature can produce contains anything but server names, tool counts and the CLI's own
    words.
  - On a CLI too old to support it, you are told exactly that — that the feature is unavailable here,
    not that something broke.
  - Turn it off with **Settings → Tools → Sightline → "Load newly added MCP servers into the
    conversation in progress"**, which leaves it observing and reporting without ever acting.

## 0.6.0 — 2026-07-29

### Added
- **Switch models from the composer.** The `/` actions menu now has a **Model** submenu: the CLI's
  aliases (Opus / Sonnet / Haiku / Fable), any full model id you pin via *Custom model…*, and a line
  reporting what the CLI says it is actually running. Switching during a conversation takes effect
  **in place** — the session and the conversation are both kept — and anything that can't switch that
  way is saved for the next conversation, with the transcript saying which of the two happened.
  Note the list is not fetched: the CLI has no command that enumerates models, and Sightline never
  holds an API key, so it offers what is knowable rather than inventing a catalogue.

### Fixed
- **A wide table no longer renders as an empty box.** A Markdown table whose cells were wider than the
  chat column collapsed to a bordered frame with nothing in it — the cells were laid out at a negative
  size and painted nothing. Such a table now scrolls horizontally, as a table too wide for its column
  always should have.
- **The activity map's toolbar and log bar match its graph.** They rendered as dark strips around a
  correctly light graph: the canvas paints a shifted variant of the panel background while the chrome
  painted the raw one, so the two disagreed. The map now derives both from the same colour and paints
  as a single surface.
- **The panel follows a theme you switch while it is open.** Colours were resolved once, when a
  component was built, so switching between light and dark left parts of the panel painting the theme
  that happened to be current when the tool window opened. They are now live values that re-resolve as
  they are read, and an editor colour-scheme change repaints the panel as a LaF change already did.

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

