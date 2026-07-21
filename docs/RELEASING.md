# Releasing

How a version of Sightline reaches the JetBrains Marketplace. Companion to
[TESTING.md](TESTING.md) (what CI runs) and [BACKLOG.md](BACKLOG.md) (what still needs a human).

> [!IMPORTANT]
> **The first upload cannot be automated.** JetBrains provides no API for creating a *new* plugin
> listing — it has to go through the web form at <https://plugins.jetbrains.com/plugin/add#intellij>
> once, by hand. The release workflow publishes *subsequent* versions of a listing that already
> exists. Running it before that first manual upload fails with a plugin-not-found error from the
> Marketplace; it does not create the listing.

---

## The pipeline

| Workflow | Trigger | Does |
|---|---|---|
| `.github/workflows/build.yml` | push to `main`, every PR | Tests, builds, runs the Plugin Verifier, uploads the artifact and the preview PNGs |
| `.github/workflows/release.yml` | a `v*` tag | Re-runs all of the above against the tagged commit, signs, publishes, and cuts a GitHub release |

`release.yml` also accepts a manual run with **dry run** ticked (the default), which does everything
except publish. Use it to prove a tag is releasable without releasing it.

## Why CI builds against Android Studio

`build.gradle.kts` defaults to `local("/Applications/Android Studio.app")` — exact API match, no
multi-GB download, and `runIde` launches that same build. A CI runner has no IDE installed, so the
target is switchable:

```bash
./gradlew build -PplatformType=AI -PplatformVersion=2025.3.1.1
```

Passing both properties selects a downloadable platform; passing neither uses the local install.

**It has to be Android Studio, not IntelliJ IDEA Community.** The compile classpath needs
`org.jetbrains.android` for `ide/android/studio/StudioFactProvider`, and **IC does not bundle it** — it
ships `android-gradle-dsl` and `android-gradle-declarative-lang-ide`, which are different plugins.
An IC target fails to resolve before compiling. (This does not weaken the CLI-first design in
[ANDROID.md](ANDROID.md) §1.1: that is about what the plugin *needs at runtime*, and the optional
`<depends>` still degrades correctly. It is only the build that needs the classes present.)

`2025.3.1.1` is build **253**, which is also the `sinceBuild` floor. CI therefore compiles against the
oldest platform the listing claims to support, so an accidental use of a newer API fails the build
rather than shipping and failing on a user's IDE.

## The channel is derived, never chosen

`build.gradle.kts` maps the version suffix to a Marketplace channel:

| Version | Channel | Who sees it |
|---|---|---|
| `0.1.0-beta` | `beta` | Only users who added the beta repository URL |
| `0.1.0-eap`, `-alpha`, `-rc` | that name | Same — opt-in |
| `0.1.0` | `default` | **Everyone browsing the Marketplace** |

This is a safety property, not a convenience. Sightline runs commands and edits code, and the
interactive paths that stop it doing so are not yet human-tested (see [BACKLOG.md](BACKLOG.md)). A
release must not reach stable because someone forgot a flag. Shipping stable means deleting the suffix
from `version` in `build.gradle.kts` — a deliberate, reviewable edit. The release workflow prints the
channel before publishing and warns loudly when it is `default`.

## Required secrets

Settings → Secrets and variables → Actions.

| Secret | Required | What |
|---|---|---|
| `PUBLISH_TOKEN` | **yes, to publish** | Marketplace permanent token: <https://plugins.jetbrains.com/author/me/tokens> |
| `CERTIFICATE_CHAIN` | no | Signing certificate chain, PEM |
| `PRIVATE_KEY` | no | Signing private key, PEM |
| `PRIVATE_KEY_PASSWORD` | no | Passphrase for that key |

Signing is optional and `signPlugin` is **skipped** when the key material is absent, so a fork or a
local build needs no secrets at all. It is worth setting up: JetBrains recommends it and the listing
shows a signed badge. To generate a chain and key, follow
<https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>.

`PUBLISH_TOKEN` has no default and no fallback — an absent token fails the publish rather than
producing a silent unauthenticated attempt.

## Cutting a release

1. **Update the version** in `build.gradle.kts`. It is the single source: `plugin.xml` deliberately
   carries no `<version>`, and `patchPluginXml` writes it into the built descriptor. Two copies could
   disagree, and the descriptor would win.
2. **Update `CHANGELOG.md`** and the `<change-notes>` block in `plugin.xml`.
3. **Regenerate the listing screenshots** if the UI changed, and *look at them*:
   ```bash
   ./gradlew test --tests "*MarketplaceScreenshotTest*" --rerun-tasks
   ```
   A cached `test` task writes no PNGs — see [TESTING.md](TESTING.md).
4. **Push a tag matching the version:**
   ```bash
   git tag v0.1.1-beta && git push origin v0.1.1-beta
   ```
   The workflow fails if the tag and `build.gradle.kts` disagree. That check exists because the
   Marketplace does not allow re-uploading over an existing version number — publishing the wrong one
   is not correctable afterwards.

## What the pipeline does not cover

Everything in [BACKLOG.md](BACKLOG.md) under *Live Android Studio verification*. CI runs 927 unit
tests and the Plugin Verifier; neither can click a button, hover, drag, or run a live CLI session.
The permission cards, diff accept/reject, `AskUserQuestion`, and the first-run disclosure have never
been exercised by a human. A green pipeline is not evidence that they work.

## Local equivalents

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew test                                   # against the local Android Studio
./gradlew test -PplatformType=AI -PplatformVersion=2025.3.1.1   # as CI does
./gradlew buildPlugin                            # -> build/distributions/sightline-<version>.zip
tools/verify-plugin.sh                           # the Plugin Verifier; must say Compatible
./gradlew -q printVersion printReleaseChannel    # what a release would publish, and where
```
