#!/usr/bin/env bash
#
# Runs the IntelliJ Plugin Verifier against the built artifact.
#
# WHY THIS EXISTS INSTEAD OF `./gradlew verifyPlugin`
# ---------------------------------------------------
# IntelliJ Platform Gradle Plugin 2.6.0 resolves the IDE distribution under the coordinate
# `idea:ideaIC:<version>` — group "idea" — which does not exist. The artifact actually lives at
# `com.jetbrains.intellij.idea:ideaIC:<version>` in the JetBrains intellij-repository. Both the
# `select { }` and the explicit `ide(...)` forms hit the same wrong group, so the Gradle task cannot
# resolve an IDE to verify against and fails before the verifier ever starts.
#
# This script skips Gradle's resolution entirely: it downloads the IDE ZIP from the correct coordinate
# and runs the Plugin Verifier CLI directly. Same verifier, same checks, same report as the Marketplace
# runs — just fetched by hand.
#
# Delete this and go back to `./gradlew verifyPlugin` once the Gradle plugin fixes the coordinate.
#
# WHY ANDROID STUDIO AND NOT IntelliJ IDEA
# ----------------------------------------
# The supported floor is Android Studio Quail 2 (2026.1.2, platform build 261.25134.95), so that is what
# the artifact has to be clean against. This script used to verify against IntelliJ IDEA Community 2025.3
# (build 253) and reported "Compatible, 0 problems" — while the Marketplace's own report, run against
# Android Studio 261, flagged three deprecated `ReadAction.compute` calls. Verifying against an IDE the
# plugin no longer claims is not a weaker check, it is a check of the wrong thing.
#
# Usage:
#   tools/verify-plugin.sh                 # local Android Studio if it matches the floor, else download
#   IDE_HOME=/path/to/ide tools/verify-plugin.sh   # verify against a specific IDE install
#   FORCE_DOWNLOAD=1 tools/verify-plugin.sh        # ignore the local install
#
#   # CI has no Android Studio installed, so the platform target must reach the build too:
#   GRADLE_ARGS="-PplatformType=AI -PplatformVersion=2026.1.2.10" tools/verify-plugin.sh
#
#   SKIP_BUILD=1 tools/verify-plugin.sh    # verify an artifact a previous step already built
#
set -euo pipefail

# Must match `sinceBuild` in build.gradle.kts — that is the floor the listing claims to support, so it
# is the build the artifact has to be clean against. AS_VERSION is the release channel's own version
# string; AS_PLATFORM_BUILD is the platform build it carries, which is what `sinceBuild` is written in.
AS_VERSION="${AS_VERSION:-2026.1.2.10}"          # Android Studio Quail 2 | 2026.1.2 (Release)
AS_PLATFORM_BUILD="${AS_PLATFORM_BUILD:-261.25134}"
# Published alongside the download on the Android Studio releases feed. Checked, not trusted: this
# fetches 1.5 GB over plain HTTP redirects into something that then gets executed as a classpath.
AS_SHA256="${AS_SHA256:-64445a54092e7056c6eb7f1a89ad116d0feec2ef5f965b8e594d62abdb58590f}"
AS_URL="${AS_URL:-https://edgedl.me.gvt1.com/android/studio/ide-zips/$AS_VERSION/android-studio-quail2-linux.tar.gz}"
VERIFIER_VERSION="${VERIFIER_VERSION:-1.388}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${VERIFY_WORK_DIR:-${TMPDIR:-/tmp}/sightline-verify}"
mkdir -p "$WORK"

# Exported, not just assigned: the verifier resolves a JDK from the JAVA_HOME *environment variable*,
# not from the java binary it was launched with. Without the export it dies with "No suitable JDK was
# found" — which reads like a missing dependency rather than a missing export.
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"

if [[ ! -x "$JAVA" ]]; then
  echo "No JDK at $JAVA — set JAVA_HOME to a JDK 21 (Android Studio's bundled JBR works)." >&2
  exit 1
fi

VERIFIER_JAR="$WORK/verifier-cli-$VERIFIER_VERSION.jar"
IDE_ARCHIVE="$WORK/android-studio-$AS_VERSION.tar.gz"
IDE_DIR="$WORK/android-studio-$AS_VERSION"

# The build number of an IDE install, from wherever that install keeps build.txt. A macOS .app puts it
# under Contents/Resources; a Linux tarball puts it at the root.
ide_build() {
  local home="$1"
  cat "$home/build.txt" 2>/dev/null || cat "$home/Resources/build.txt" 2>/dev/null || true
}

# An IDE is usable only if its build is at or above the floor. Compared on the first two components —
# the third moves with every patch, and Quail 2 Patch 1 is as valid a floor check as Quail 2 itself.
#
# Each `local` gets its own line on purpose: bash expands every word of a `local a=1 b=$a` before it
# performs any of the assignments, so `b` would read the *previous* value of `a` — empty here, which made
# this return false for every IDE including the one it was measuring against.
matches_floor() {
  local build="${1#AI-}"
  local want_major="${AS_PLATFORM_BUILD%%.*}"
  local want_minor="${AS_PLATFORM_BUILD#*.}"
  local got_major="${build%%.*}"
  local rest="${build#*.}"
  local got_minor="${rest%%.*}"
  [[ "$got_major" == "$want_major" ]] || return 1
  [[ "$got_minor" =~ ^[0-9]+$ && "$want_minor" =~ ^[0-9]+$ ]] || return 1
  (( got_minor >= want_minor ))
}

if [[ -n "${SKIP_BUILD:-}" ]]; then
  echo "==> Skipping build (SKIP_BUILD set); verifying the existing artifact"
else
  echo "==> Building the plugin"
  # GRADLE_ARGS is unquoted on purpose: it carries multiple flags that must split into separate words.
  # shellcheck disable=SC2086
  ( cd "$ROOT" && ./gradlew buildPlugin -q ${GRADLE_ARGS:-} )
fi

ARTIFACT="$(ls -t "$ROOT"/build/distributions/*.zip | head -1)"
echo "    $ARTIFACT"

if [[ ! -f "$VERIFIER_JAR" ]]; then
  echo "==> Downloading Plugin Verifier $VERIFIER_VERSION"
  curl -fsSL -o "$VERIFIER_JAR" \
    "https://repo1.maven.org/maven2/org/jetbrains/intellij/plugins/verifier-cli/$VERIFIER_VERSION/verifier-cli-$VERIFIER_VERSION-all.jar"
fi

# An Android Studio you already have beats 1.5 GB of download — but only if it is actually at or above
# the floor. A local install *below* it would verify the artifact against an IDE the listing excludes and
# report a pass that means nothing, so it is rejected rather than quietly used.
IDE_HOME="${IDE_HOME:-}"
if [[ -z "$IDE_HOME" && -z "${FORCE_DOWNLOAD:-}" ]]; then
  for candidate in "/Applications/Android Studio.app/Contents" "$HOME/Applications/Android Studio.app/Contents"; do
    build="$(ide_build "$candidate")"
    if [[ -n "$build" ]]; then
      if matches_floor "$build"; then
        IDE_HOME="$candidate"
        echo "==> Using the local Android Studio ($build)"
      else
        echo "==> Ignoring local Android Studio $build — below the $AS_PLATFORM_BUILD floor"
      fi
      break
    fi
  done
fi

if [[ -z "$IDE_HOME" ]]; then
  # Presence of the directory is NOT proof of a usable IDE: an interrupted extraction, a temp-dir sweep,
  # or a partially-restored CI cache all leave one behind. The verifier's own requirement is `build.txt`,
  # and without it the failure surfaces as "IDE ... is invalid" from deep inside the verifier rather than
  # as the incomplete extraction it actually is. Check for that file and re-extract if it is missing.
  if [[ ! -f "$IDE_DIR/build.txt" ]]; then
    if [[ -d "$IDE_DIR" ]]; then
      echo "==> $IDE_DIR is present but incomplete (no build.txt) — re-extracting"
      rm -rf "$IDE_DIR"
    fi
    if [[ ! -f "$IDE_ARCHIVE" ]]; then
      echo "==> Downloading Android Studio $AS_VERSION (~1.5 GB, cached in $WORK)"
      curl -fsSL -o "$IDE_ARCHIVE" "$AS_URL"
    fi
    echo "==> Checking the download against the published SHA-256"
    actual="$(shasum -a 256 "$IDE_ARCHIVE" | cut -d' ' -f1)"
    if [[ "$actual" != "$AS_SHA256" ]]; then
      echo "Checksum mismatch for $IDE_ARCHIVE" >&2
      echo "  expected $AS_SHA256" >&2
      echo "  actual   $actual" >&2
      rm -f "$IDE_ARCHIVE"
      exit 1
    fi
    echo "==> Extracting"
    mkdir -p "$IDE_DIR"
    # --strip-components=1: the tarball wraps everything in an `android-studio/` directory, and the
    # verifier wants the IDE root itself, not its parent.
    if ! tar -xzf "$IDE_ARCHIVE" -C "$IDE_DIR" --strip-components=1; then
      echo "Extraction failed — removing the cached archive and directory so the next run refetches." >&2
      rm -rf "$IDE_DIR" "$IDE_ARCHIVE"
      exit 1
    fi
    if [[ ! -f "$IDE_DIR/build.txt" ]]; then
      echo "Extracted $IDE_ARCHIVE but $IDE_DIR/build.txt is still missing — not a usable IDE." >&2
      exit 1
    fi
  fi
  IDE_HOME="$IDE_DIR"
fi

IDE_BUILD="$(ide_build "$IDE_HOME")"
if ! matches_floor "$IDE_BUILD"; then
  echo "IDE at $IDE_HOME is build $IDE_BUILD, below the $AS_PLATFORM_BUILD floor — refusing to verify." >&2
  exit 1
fi

echo "==> Verifying against $IDE_BUILD"
echo

# `-team-city` is deliberately NOT passed: the plain report is the readable one, and this is run by a
# human before a submission rather than by CI.
set +e
( cd "$WORK" && "$JAVA" -jar "$VERIFIER_JAR" check-plugin "$ARTIFACT" "$IDE_HOME" ) 2>&1 \
  | grep -vE "^Layout component .* has some nonexistent" \
  | tee "$WORK/last-report.txt"
STATUS=${PIPESTATUS[0]}
set -e

echo
if grep -q "Compatible\." "$WORK/last-report.txt"; then
  echo "==> PASS — no compatibility problems."
  echo "    Deprecated and experimental API usages are informational; read them, don't ignore them."
  exit 0
fi

echo "==> FAIL — compatibility problems found. Full report: $WORK/last-report.txt"
exit "${STATUS:-1}"
