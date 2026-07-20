# Android control centre — roadmap

Sightline today is an IDE-aware Claude Code panel that happens to run in Android Studio. This is the
plan to make it an **Android development control centre**: Claude knows the variant, device, process,
Gradle state, current screen and test environment, not just the files.

Companion documents: [PROTOCOL.md](PROTOCOL.md) (CLI facts), [TESTING.md](TESTING.md) (how interactive
flows are verified), [BACKLOG.md](BACKLOG.md) (release gates). Decisions that outlive a milestone move
to **Standing decisions** in [../CLAUDE.md](../CLAUDE.md).

Guiding principle is unchanged: **correctness logic lands as platform-free, unit-tested classes; thin
Swing on top.** Every milestone below is shaped by it.

---

## 0. The thesis — what actually differentiates

Claude Code can already run `adb`, `gradlew`, and read `build.gradle.kts`. Shipping "Sightline can run
adb" differentiates nothing. The four things it genuinely cannot do today:

1. **Resolution** — knowing *which* variant, device, module and task apply, without asking or guessing.
2. **Persistence of context** — not rediscovering the same five facts on every turn, at token cost, with
   a fresh chance of getting them wrong.
3. **Interpretation** — turning raw Gradle/logcat/adb output into typed, evidence-tagged findings
   instead of a wall of text the model re-parses each time.
4. **Affordances** — a one-click card where a paragraph of instructions used to be.

Everything in this roadmap is one of those four. Anything that is merely "shell out to a tool Claude
could already have shelled out to" is not on the list.

Corollary, and the main thing to resist: **do not rebuild Android Studio inside a chat panel.** The
profilers, Layout Inspector, Database Inspector and APK Analyzer are better than anything this plugin
will build. Where the proposal overlaps them, this roadmap takes the *static* half (which AS does not
surface to an agent) and leaves the live half alone. See [Appendix A](#appendix-a--deferred-with-reasons).

---

## 1. Decisions taken

### 1.1 Dependency strategy — CLI-first, AS optional

**Decided: option A.** The spine is built on stable contracts; Android Studio APIs are opportunistic
enrichment that degrades to UNKNOWN, never to a guess.

Rationale: `com.android.tools.idea.*` is internal API. It is present in the local install (logcat,
avdmanager, deviceprovisioner, adblib, layoutinspector, the Gradle model — all verified), but it is
flagged by the Plugin Verifier, unstable across AS releases, and absent from IntelliJ IDEA Community —
which is the platform the `sinceBuild = 253` floor was chosen for. Hard-depending would trade the
listing's reach and a stable build for facts that are, as it turns out, mostly available elsewhere.

Mechanics:

```xml
<!-- plugin.xml -->
<depends optional="true" config-file="sightline-android.xml">org.jetbrains.android</depends>
```

`sightline-android.xml` registers only the AS-backed fact providers. Everything else lives in the main
descriptor and works in plain IDEA. Build side: `bundledPlugin("org.jetbrains.android")` joins
`com.intellij.java` and the Kotlin plugin in `build.gradle.kts`.

> **Verifier note.** `verifyPlugin` is already a release gate that cannot run in this dev environment
> (see BACKLOG). Adding an optional Android dependency makes that gate *more* important, not less —
> internal-API usage inside `sightline-android.xml`'s providers is exactly what it catches. Land M0
> behind the gate being runnable on CI.

### 1.2 Fact sourcing — a ladder, not a lookup

This is the core design idea and it mirrors `EvidenceSource` in `activity/` and `HealthStatus.UNKNOWN`
in `health/`: **not-knowing is a first-class answer and never masquerades as knowing.**

Every Android fact resolves through an ordered ladder, strongest source first:

| Tier | Source | Example |
|---|---|---|
| 1 | **AS API** (optional dep, when loaded) | selected variant, deployment target, Compose preview surface |
| 2 | **AGP build-output contract** | `output-metadata.json`, `merged_manifest/<variant>/` |
| 3 | **Static parse** | `build.gradle.kts`, `libs.versions.toml`, source `AndroidManifest.xml` |
| 4 | **Live device** | `adb shell getprop`, `dumpsys`, `pm list packages` |
| 5 | **UNKNOWN** | stated plainly, with what would resolve it |

Tier 2 is the discovery that makes tier-1 optional rather than essential. AGP writes a versioned,
machine-readable descriptor next to every build output:

```jsonc
// app/build/intermediates/apk/<flavour>/<type>/output-metadata.json
{
  "version": 3,
  "applicationId": "com.example.driver.staging",   // flavour suffix already applied
  "variantName": "demoStagingDebug",
  "elements": [{ "versionCode": 1, "versionName": "1.0-staging",
                 "outputFile": "app-demo-staging-debug.apk" }],
  "minSdkVersionForDexing": 30
}
```

And the merged manifest — the *real* one, post-merge, which no amount of source parsing reproduces —
sits at a variant-keyed path that also reveals which variants have been built:

```
app/build/intermediates/merged_manifest/demoStagingDebug/…/AndroidManifest.xml
                                        brandBStagingDebug/…
                                        brandCStagingDebug/…
```

So tier 2 alone yields applicationId, variant, version code/name, min SDK, the merged manifest, and a
candidate variant list. Tier 1 upgrades "last built" to "currently selected". Tier 5 says so when
nothing has been built yet.

Each resolved fact carries its tier. The context strip and the MCP payload both surface it, so Claude
can tell "variant is `demoStagingDebug` (IDE selection)" from "variant is `demoStagingDebug` (last
build output, may be stale)". **A stale fact labelled stale is useful; a stale fact labelled current is
a bug that costs an hour.**

### 1.3 Persistence — a scoped, opt-in exception

**Decided: amend the standing decision.** Four features need disk (flaky-test history, screenshot
baselines, artifact-size snapshots, saved workflows) and are not worth half-building without it.

The carve-out reuses the guardrails the original decision already specified for hypothetical
persistence, and adds nothing beyond them:

- Location: `.sightline/` under the project, gitignore-suggested on first write.
- **Workspace-relative paths only.** Never absolute paths, source contents, prompts, or reasoning.
- Versioned schema; unknown version is discarded, not migrated blindly.
- Retention cap per store; delete-one and clear-all in settings.
- **Off by default**, one setting per store, never enabled as a side effect of another feature.
- Screenshots are the one binary payload; they are capped, and covered by the consent rule in §1.4.

`CLAUDE.md`'s "Nothing is persisted to disk except settings" needs rewording to name this exception
explicitly, so it stays a decision rather than an erosion.

### 1.4 Privacy — the part the proposal under-weights

Logcat, screenshots and network traces are the three richest sources of user data on a developer's
machine, and this plugin's job is to put them in a prompt sent to a third-party API. That is a real
disclosure surface and it needs a hard gate, not best-effort scrubbing.

- **`LogcatRedactor` is on by default and is not a suggestion.** It mirrors `HealthSanitizer`'s
  allow-nothing-sensitive, idempotent contract: bearer tokens, API keys, JWTs, email addresses, phone
  numbers, IMEI/serials, lat/long pairs, `Authorization:` headers, home paths. Tests assert the
  redactor's output over a corpus of real-shaped log lines, exactly as `HealthSanitizerTest` does.
- **Screenshots require per-capture consent.** A screenshot of a running app is a screenshot of
  whatever data is on that screen. The capture card shows the image *before* it is attached, with
  attach/discard. No silent capture, no auto-attach.
- **Redaction failures fail closed.** If the redactor cannot parse a line, the line is dropped, not
  passed through.
- Every one of these is stated in the tool description so the model knows the data is redacted and
  does not confidently reason about a placeholder.

---

## 2. Architecture

Mirrors the existing package shape exactly — platform-free core, thin platform layer, thin Swing.

```
android/                          <- NEW. platform-free, unit-tested. no IntelliJ imports.
  AndroidContext.kt               model: module, variant, flavours, SDKs, appId, device, process, screen
                                  + FactTier per field (§1.2)
  AndroidContextFormatter.kt      context -> composer strip text + the prompt block
  AndroidFactLadder.kt            pure resolution order + staleness rules
  OutputMetadata.kt               parse AGP output-metadata.json (versioned; unknown version -> UNKNOWN)
  GradleModuleParser.kt           build.gradle(.kts) -> flavours, dimensions, sdks, namespace
  VersionCatalogParser.kt         libs.versions.toml -> deps, versions, plugin aliases
  ManifestModel.kt/ManifestAudit.kt  merged manifest -> components, permissions, findings (§10)
  AdbCommands.kt                  pure: action -> argv. builds, never executes.
  AdbOutputParsers.kt             pure: devices -l, getprop, dumpsys, pm, am
  AvdCatalog.kt                   emulator -list-avds + config.ini -> device list
  GradleTasks.kt                  (module, variant, intent) -> task name
  BuildFailureClassifier.kt       raw gradle output -> typed AndroidBuildFailure (§5)
  LogcatModel.kt/LogcatParser.kt  threadtime lines -> entries, grouping, dedupe
  LogcatRedactor.kt               §1.4. allow-nothing-sensitive, idempotent.
  StackTraceResolver.kt           frame -> file:line candidates + confidence
  DeviceRecipes.kt                recipe -> ordered adb commands + the revert list
  RouteExtractor.kt               Compose NavHost + nav XML -> routes, args, deep links (§11)

ide/android/                      <- thin platform layer
  AndroidEnvironment.kt           resolve SDK/adb/emulator; run off-EDT; timeouts; cancellation
  AndroidFactProvider.kt          the ladder's tier-2..4 legs
  AndroidMcpTools.kt              registers android.* on the EXISTING IdeServer
  AndroidActionPolicy.kt          gates destructive device actions (sibling of PathAccessPolicy)

ide/android/studio/               <- ONLY loaded via sightline-android.xml (optional dep)
  StudioFactProvider.kt           tier-1: selected variant, deployment target. reflection-free but
                                  isolated, so a missing plugin is a missing class, not a crash.

ui/android/
  AndroidContextStrip.kt          the strip + chips
  AndroidActionCard.kt            device/build/verification action cards
```

Three integration seams, all already present and all cheap:

| Seam | Anchor | Use |
|---|---|---|
| Prompt injection | `ui/state/ComposerModel.kt:68` `buildMessage()` | platform-free, unit-tested, every send path funnels through it |
| Chip row | `ui/ClaudeComposerPanel.kt:53,78-80,195-206` | exists; currently attachments-only and hardwired removable |
| MCP tools | `ide/IdeServer.kt:235` `toolsList()` / `:256` `callTool()` | add `android.*` to the existing `ide` server — do **not** add a second MCP server; `--mcp-config` at `process/ClaudeSession.kt:98-104` is a single hardcoded inline JSON string |

Note on prompt injection: `buildMessage` is also re-entered when the queue drains
(`ui/ClaudePanel.kt:1561-1565`), so a queued message picks up facts **at drain time, not queue time**.
That is the right call — the device may have changed while the message waited — but it must be
deliberate and tested, not incidental.

---

## 3. Milestones

Each is independently shippable with a stated gate. Release mapping: **0.8.0** = M3,
**0.9.0** = M4–M5.

Shipped milestones are **deleted from this file**, not annotated — the same rule
[BACKLOG.md](BACKLOG.md) follows, so what's left stays honest and short. Decisions that outlive a
milestone move to **Standing decisions** in [../CLAUDE.md](../CLAUDE.md); the architecture a milestone
introduced is described in that file's architecture table. (M0 — foundations, the fact ladder, the
optional-dependency boundary, the action gate, the persistence guardrails and the Health rows — shipped
2026-07-20 and has been removed accordingly. Its four standing-decision amendments, formerly Appendix B,
are now in CLAUDE.md. **M1** — the context strip, removable chips, prompt injection, `android.getContext`
and the tier-2/3 parsers — and **M2** — variant-aware task resolution, the build-failure classifier,
stack-trace-to-source and targeted test selection — shipped 2026-07-20 too.)

### M3 — Device control & Logcat intelligence *(§2, §7, §8)*

Closing the run/observe loop — the part Claude cannot do at all today.

- **Device catalog** — `adb devices -l` ∪ `emulator -list-avds`, with an offline/booting/online state
  model. Device picker in the strip. Action card when nothing is available:
  `[Start Pixel 8 API 35] [Select another device] [Build only]`.
- **Device actions** as MCP tools + cards: start/stop emulator, install, launch, force-stop, clear data,
  uninstall, open activity, fire deep link, grant/revoke permission, dark mode, locale, font scale,
  rotate, simulate process death, screenshot. Destructive ones gated per M0.
- **Device-state recipes** with a **revert list** — the proposal's accessibility recipe is only safe if
  Sightline can put the device back. `DeviceRecipes` returns apply-commands *and* restore-commands, and
  the card offers `[Restore device state]`.
- **Logcat intelligence** — app-filtered capture, `LogcatRedactor` (§1.4), repeat grouping, framework
  noise suppression, stack-trace linking via M2's resolver, process death/restart detection, crash /
  ANR / StrictMode / OOM extraction. Attach as a chip: last 30s / 2m / session.
- **Crash & ANR investigation** (§8) — correlate trace, source, recent git diff, API level, variant,
  manifest, lifecycle. Reports under four explicit headings: **confirmed cause / likely cause /
  contributing factors / missing evidence.** That distinction is the whole point; without it this is a
  guessing machine with a nice layout.

**Gate:** redactor corpus test passes and fails closed. A recipe applied then reverted leaves the device
byte-identical on the properties it touched. Crash investigation on a real crash names its evidence
tier correctly and lists what it could not determine.

---

### M4 — Screen awareness & Compose verification *(§3, §4)*

- **Current screen** — `dumpsys activity activities` for Activity and back stack, `dumpsys window` for
  configuration, plus best-effort Compose route. Honest limits: the Compose semantics tree is reachable
  via `uiautomator dump` **only** when `testTagsAsResourceId` is enabled; when it is not, say so rather
  than reporting an empty tree as a clean one.
- **Screenshot capture** with the per-capture consent card (§1.4).
- **Compose preview integration** (tier-1, AS only) — detect previews for the current composable, run
  and refresh, generate missing previews, render light/dark/font-scale/device variants.
- **Visual verification loop** — modify, render, capture, compare, report, revert or refine. Baselines
  land in the M0 persistence store.
- **Compose migrations** (§4) — XML→Compose, Fragment→Compose, RecyclerView→`LazyColumn`,
  LiveData→StateFlow, M2→M3. Delivered as *plans* the user approves, never a blind screen rewrite.

**Gate:** preview integration degrades cleanly to "unavailable — Android Studio not detected" in plain
IDEA. Semantics-tree unavailability is reported as unavailable, never as empty.

---

### M5 — Static audits, routes & graph modes *(§10, §11, §15, §9)*

Grouped because they share one substrate: parsing the merged manifest and the navigation graph.

- **Manifest & permission audit** (§10) — over the *merged* manifest from tier 2. Exported components,
  intent filters, deep links, runtime permissions, foreground service types, `POST_NOTIFICATIONS`
  handling, network security config, cleartext, FileProvider paths, backup config, PendingIntent
  mutability, WebView settings, package visibility. Plus the code-vs-manifest cross-check (permission
  requested in code, absent from manifest). Purely static, no device, high value per line of code.
- **Routes & deep links** (§11) — `RouteExtractor` over Compose `NavHost` and nav XML. Unreachable
  routes, argument validation, duplicate deep links, `[Launch on device]` (M3), generated deep-link tests.
- **Accessibility & adaptive matrix** (§15) — the condition matrix, driven by M3's recipes and M4's
  capture. Reports per cell rather than one verdict.
- **Graph modes** (§9) — the model is closer to ready than it looks. `VIEW_MODEL`, `USE_CASE`,
  `REPOSITORY`, `COMPOSABLE` already exist as node types *and* categories. Two pieces of work:
  1. **Lift the lens.** `ActivityMapPanel.Filter` is a private Swing enum filtering nodes only
     (`ui/ActivityMapPanel.kt:122-132`). A data-flow or module-dependency mode needs **edge** predicates
     too. Move it to a platform-free `GraphLens { nodePred, edgePred, spinePolicy }` — testable, and
     usable by `ActivityMapRenderer` for the headless previews.
  2. **New id scheme for non-file nodes.** Every node is currently `file:<path>`-keyed; a route is not
     1:1 with a file. Needs a `route:` id, a `SCREEN`/`ROUTE` node type, and a new `AgentActivityEvent`
     variant — the `when` at `ActivityGraph.kt:84-118` is exhaustive over a sealed interface, so the
     compiler finds every call site.

> **Standing-decision boundary — read before implementing §9.** "The graph never claims a relationship
> it can't evidence" explicitly rejected ViewModel→UseCase→Repository inference. That rejection stands
> for *name-pattern* inference. But **constructor injection is a PSI-resolvable declaration**, and
> Hilt/Dagger `@Inject`/`@Binds`/`@Provides` are real annotations. So the architecture graph is
> permitted **only** where PSI resolves the edge, tagged `PSI_DECLARATION`. It is never permitted from a
> filename ending in `Repository`.
>
> Note the existing classifier *does* name-match — but only to assign a node's **cluster**, which is
> presentation. The rule is: **naming may colour a node; it may never create an edge.**

**Gate:** every architecture edge carries `PSI_DECLARATION` or `PSI_REFERENCE`; a test asserts no edge
is ever emitted with `NAMING_HEURISTIC`. Manifest audit findings each cite the manifest line.

---

### Cross-cutting: commands and workflows *(§18, §19)*

Delivered incrementally alongside the milestone that makes each real, not as a separate phase — a
command that exists before its capability is worse than no command.

- `/android-context` (M1) · `/build` `/test-current` `/test-changed` (M2) · `/logcat` `/screenshot`
  `/deep-link` `/clear-app-data` `/reproduce-crash` (M3) · `/inspect-screen` `/compose-preview` (M4) ·
  `/manifest-audit` `/accessibility-check` (M5).
- **Availability gating is mandatory.** `/inspect-screen` with no running app must explain *why* it is
  unavailable. The pattern exists: `ClaudePanel.kt:860-863` `diagnosticsAvailability()` returns
  `Pair<Boolean, String?>` — available plus reason. Reuse it; a silently disabled menu item is a
  support ticket.
- **Saved workflows** (§19) land in M5 on the M0 persistence store, surfaced as landing-screen buttons.

---

## Appendix A — deferred, with reasons

Not "no forever" — "not now, and here is what would change the answer."

| Proposal | Verdict | Why |
|---|---|---|
| §14 Performance & battery | **Defer** | AS profilers do this properly. Guided collection + interpretation is defensible, but it is the lowest value-per-effort item in the proposal and depends on trace formats that shift. Revisit if Perfetto's exported JSON proves stable enough to parse. |
| §13 live DB query / DataStore inspection | **Defer** | AS Database Inspector is better and already there. **The static half is kept in M5-adjacent scope**: Room migration-gap analysis from `app/schemas/*.json` is pure file parsing, genuinely valuable, and AS does *not* expose it to an agent. Do that; skip live querying. |
| §12 Network capture | **Scope down hard** | Real interception needs either app cooperation (an OkHttp interceptor Sightline cannot install) or the AS Network Inspector. What *is* achievable — correlating existing OkHttp log output to the repository call and endpoint source — is a small feature, not the section as written. Never present a correlation as a capture. |
| §2 Screen recording | **Drop** | `adb shell screenrecord` works, but the model cannot watch video. It produces a file for a human, which Android Studio already offers a button for. |
| §3 Full Compose semantics tree | **Partial (M4)** | Reachable only with `testTagsAsResourceId` enabled. Ship it with the precondition stated; do not imply general availability. |
| Phase 3 controlled UI interaction | **Defer** | Agent-driven tapping on a live device is a large safety surface — wrong tap, real data, no undo. Wants its own design pass with a confirmation model, after M3's action gating has proven itself. |
| §16 APK/AAB analysis | **Partial** | Size comparison and version validation are cheap on the M0 store plus `output-metadata.json`. Full APK content analysis duplicates APK Analyzer — skip. |

---

## Appendix B — standing-decision amendments *(done)*

All four landed in `CLAUDE.md` with M0 on 2026-07-20: the persistence carve-out named against the
"nothing but settings" decision, the "naming may colour a cluster, never create an edge" sharpening of
the no-unevidenced-relationships rule (**read this before implementing M5's graph modes**), and the two
new decisions — the fact ladder (§1.2) and CLI-first/AS-optional (§1.1). They are constraints on the
milestones below, not roadmap.
