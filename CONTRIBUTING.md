# Contributing

Thanks for looking. Sightline is Apache-2.0 and contributions are welcome — bug reports especially,
since it is a beta and I am one person.

## Reporting a bug

Open an issue with the plugin version, your IDE version, and what you expected versus what happened.

**Run the Health check first** (composer overflow → *More ▸ Health check…*) and use **Copy report**. It
runs through a sanitiser that removes tokens, your home path, username, email and IP addresses, so it
is safe to paste publicly. It usually identifies the problem on its own.

**Security problems go to the address in [SECURITY.md](SECURITY.md), not to a public issue.**

## Building

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test          # 887 unit tests
./gradlew buildPlugin   # -> build/distributions/sightline-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin loaded
tools/verify-plugin.sh  # Plugin Verifier — NOT ./gradlew verifyPlugin, see the script header
```

Build with Android Studio's bundled **JBR 21**, not the PATH's Java. The reasons for that and several
other non-obvious build constraints are in [CLAUDE.md](CLAUDE.md) under *Build gotchas* — each one cost
real debugging, so it is worth reading before you fight the build.

## The one architectural rule

**Correctness logic goes in platform-free, unit-tested classes. Swing is a thin layer on top.**

That is why `activity/`, `ui/state/`, `interaction/`, `health/` and `android/` have no IntelliJ imports
and are covered by plain JUnit. If you find yourself putting a decision inside a Swing component, that
decision probably belongs in one of those packages with a test.

## The principle behind most review comments

Sightline tells a developer things they will act on. So the bar throughout is:

> **It is better to say "I don't know" than to be confidently wrong.**

Concretely, and these are all enforced by existing tests:

- An unrecognised build failure gets no invented cause.
- A fact carries where it came from; a stale value says it is stale.
- A crash with no frame in your code says so rather than blaming a framework frame.
- The graph never draws a relationship it cannot evidence — naming may colour a node's cluster, but
  may never create an edge.
- A device action that cannot be classified is treated as destructive.
- Changed code with no matching test is reported, not silently skipped.

A PR that makes something *look* more capable by guessing will get pushed back on. One that makes it
admit a limit more clearly is usually right.

## Tests

Every behavioural change needs a test. Write them so the name states the claim
(`` `an unrecognised failure keeps the raw output and offers no cause` ``) rather than the mechanism.

For UI, there is a headless PNG harness — `./gradlew test --rerun-tasks` writes previews to `build/`.
**Look at the images.** That channel has caught defects every assertion missed. See
[docs/TESTING.md](docs/TESTING.md), including the two gotchas that will otherwise waste your afternoon.

## Style

Match the surrounding code. Kotlin official style, 4 spaces, ~110 columns.

Comments should explain *why*, especially where the code looks odd — most of the strange-looking code
here is strange because of a bug that is now covered by a test, and the comment is what stops someone
undoing it.

Two hazards specific to this codebase:

- **Kotlin nests block comments.** A `/*` inside a KDoc opens a nested comment. This has broken the
  build twice. Keep glob patterns out of KDoc; `//` is safe.
- **A NUL byte makes a source file invisible to grep.** It happened here, and a package rename then
  silently skipped two files. If a text tool seems to be ignoring a file, check `file <path>`.

## Pull requests

Small and focused beats large and complete. Run `./gradlew test` and `tools/verify-plugin.sh` before
opening one. Explain *why* in the description — the what is in the diff.

By contributing you agree your contribution is licensed under Apache-2.0.
