# Reporting problems and contributing

Sightline is **source-available** ([LICENSE](LICENSE)): you can read it, build it, audit it and send
improvements. It is not open source — you may not redistribute it or sell it — but **using it at work is
expressly permitted**, contributions are genuinely welcome, and so are bug reports.

## Reporting a bug

Include the plugin version, your IDE version and build, and what you expected versus what happened.

**Run the Health check first** — composer overflow → *More ▸ Health check…* → **Copy report**. It runs
through a sanitiser that removes tokens, your home path, username, email addresses and IP addresses, so
it is safe to paste anywhere. It frequently identifies the problem on its own: a missing CLI, stale
authentication, indexing still running, an unauthorised device.

Send it to **support@cxk.co.za**, or to the issue tracker if one is linked from the Marketplace listing.

### What makes a report useful

- **What you asked Claude to do**, roughly — the failure often depends on the tool it chose.
- **The permission mode** you were in. Behaviour differs sharply between `default` and `auto`.
- **Whether it reproduces.** An intermittent failure is still worth reporting; just say it's intermittent.
- For anything Android: the **build variant** and whether a device was connected. The context strip
  above the composer shows both.

Please **do not** paste raw logcat or a full transcript into a public tracker. Sightline redacts logcat
before it reaches a prompt, but a report you assemble by hand has no such protection.

## Security problems

**Do not open a public issue.** Email **support@cxk.co.za** with "Sightline security" in the subject.
See [SECURITY.md](SECURITY.md) for what is especially worth reporting and what to expect.

## Feature requests and feedback

Very welcome, to the same address. Note clause 14 of the [licence](LICENSE): feedback you send may be
used and incorporated without compensation. That is standard, and it exists so a good suggestion can
simply be acted on — it does not affect ownership of anything you have built yourself.

## Code contributions

Pull requests are welcome. Before you invest real time in one, open an issue describing the change —
Sightline has some firm opinions (see *The principle behind most review comments* below), and it is
better to find out we disagree before you write it than after.

### The contribution terms, stated plainly

Clause 5 of the [licence](LICENSE) is the part to read. **By submitting a contribution you grant a
broad, irrevocable licence to use it — including commercially and under proprietary terms — while
keeping your own copyright.** You can still use your code anywhere else you like.

That is deliberate and it is the standard arrangement for a project licensed this way. Without it, a
merged patch would leave a piece of the codebase that could not be relicensed or shipped commercially
without tracking down its author, which is a real obstacle projects hit years later. If you are not
comfortable with that grant, please open an issue describing the fix instead — a well-described bug is
often more useful than a patch anyway.

Contributions are not obligated to be accepted, and small focused changes fare much better than large
ones.

---

# Working on the source

## Building

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test          # 902 unit tests
./gradlew buildPlugin   # -> build/distributions/sightline-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin loaded
tools/verify-plugin.sh  # Plugin Verifier — NOT ./gradlew verifyPlugin, see the script header
```

Build with Android Studio's bundled **JBR 21**, not the PATH's Java. That and several other non-obvious
build constraints are in [CLAUDE.md](CLAUDE.md) under *Build gotchas* — each cost real debugging, so
read them before fighting the build.

## The one architectural rule

**Correctness logic goes in platform-free, unit-tested classes. Swing is a thin layer on top.**

That is why `activity/`, `ui/state/`, `interaction/`, `health/` and `android/` have no IntelliJ imports
and are covered by plain JUnit. A decision that ends up inside a Swing component probably belongs in one
of those packages, with a test.

## The principle behind most review comments

Sightline tells a developer things they will act on. The bar throughout is:

> **It is better to say "I don't know" than to be confidently wrong.**

Concretely, and all enforced by existing tests:

- An unrecognised build failure gets no invented cause.
- A fact carries where it came from; a stale value says it is stale.
- A crash with no frame in your code says so rather than blaming a framework frame.
- The graph never draws a relationship it cannot evidence — naming may colour a node's cluster, but
  may never create an edge.
- A device action that cannot be classified is treated as destructive.
- Changed code with no matching test is reported, not silently skipped.

A change that makes something *look* more capable by guessing gets pushed back on. One that makes it
admit a limit more clearly is usually right.

## Tests

Every behavioural change needs a test, named so it states the claim
(`` `an unrecognised failure keeps the raw output and offers no cause` ``) rather than the mechanism.

For UI there is a headless PNG harness — `./gradlew test --rerun-tasks` writes previews to `build/`,
including the Marketplace screenshots in `build/marketplace/`. **Look at the images.** That channel has
caught defects every assertion missed. See [docs/TESTING.md](docs/TESTING.md), including the two gotchas
that will otherwise waste an afternoon.

## Style

Match the surrounding code. Kotlin official style, 4 spaces, ~110 columns. Comments explain *why*,
especially where the code looks odd — most of the strange-looking code here is strange because of a bug
that is now covered by a test, and the comment is what stops someone undoing it.

Two hazards specific to this codebase:

- **Kotlin nests block comments.** A `/*` inside a KDoc opens a nested comment. This has broken the
  build twice. Keep glob patterns out of KDoc; `//` is safe.
- **A NUL byte makes a source file invisible to `grep`.** It happened here, and a package-wide rename
  then silently skipped two files. If a text tool seems to be ignoring a file, run `file <path>`.
