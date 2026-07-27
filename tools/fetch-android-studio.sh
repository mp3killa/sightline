#!/usr/bin/env bash
#
# Downloads, checksum-verifies and extracts the Android Studio this project targets, then prints its
# IDE home on stdout. Everything else goes to stderr, so callers can do:
#
#   IDE_HOME="$(tools/fetch-android-studio.sh)"
#
# WHY THIS EXISTS INSTEAD OF `-PplatformType=AI -PplatformVersion=...`
# -------------------------------------------------------------------
# IntelliJ Platform Gradle Plugin 2.6.0 builds the Android Studio download URL from the version:
#
#   .../ide-zips/2025.3.1.1/android-studio-2025.3.1.1-linux.tar.gz
#
# Google changed that filename to the release *codename* from Quail onward:
#
#   .../ide-zips/2026.1.2.10/android-studio-quail2-linux.tar.gz
#
# so the plugin cannot resolve any Quail build and fails with "No IntelliJ Platform dependency found
# with 'AI-2026.1.2.10 (installer)'". Fetching it here and handing Gradle a `local()` install via
# `-PlocalIde` sidesteps the pattern entirely — and has the side benefit that CI compiles against and
# verifies against *the same* IDE, which cannot drift apart the way two coordinates can.
#
# Delete this once the Gradle plugin learns the codename URLs.
set -euo pipefail

# Keep in step with `sinceBuild` in build.gradle.kts. AS_VERSION is the release channel's version
# string; AS_PLATFORM_BUILD is the platform build it carries, which is what sinceBuild is written in.
AS_VERSION="${AS_VERSION:-2026.1.2.10}"          # Android Studio Quail 2 | 2026.1.2 (Release)
AS_PLATFORM_BUILD="${AS_PLATFORM_BUILD:-261.25134}"
# Published alongside the download on the Android Studio releases feed. Checked, not trusted: this
# fetches 1.5 GB over redirects and the result is then put on a compile classpath.
AS_SHA256="${AS_SHA256:-64445a54092e7056c6eb7f1a89ad116d0feec2ef5f965b8e594d62abdb58590f}"
AS_URL="${AS_URL:-https://edgedl.me.gvt1.com/android/studio/ide-zips/$AS_VERSION/android-studio-quail2-linux.tar.gz}"

CACHE="${AS_CACHE_DIR:-${TMPDIR:-/tmp}/sightline-verify}"
mkdir -p "$CACHE"
ARCHIVE="$CACHE/android-studio-$AS_VERSION.tar.gz"
IDE_DIR="$CACHE/android-studio-$AS_VERSION"

log() { echo "$@" >&2; }

# Presence of the directory is NOT proof of a usable IDE: an interrupted extraction, a temp-dir sweep,
# or a partially-restored CI cache all leave one behind. `build.txt` is the verifier's own requirement
# and Gradle's too, so it is the thing to test for — without it the failure surfaces from deep inside
# one of those tools rather than as the incomplete extraction it actually is.
if [[ ! -f "$IDE_DIR/build.txt" ]]; then
  [[ -d "$IDE_DIR" ]] && { log "==> $IDE_DIR is incomplete (no build.txt) — re-extracting"; rm -rf "$IDE_DIR"; }

  if [[ ! -f "$ARCHIVE" ]]; then
    log "==> Downloading Android Studio $AS_VERSION (~1.5 GB, cached in $CACHE)"
    curl -fsSL -o "$ARCHIVE" "$AS_URL"
  fi

  log "==> Checking the download against the published SHA-256"
  actual="$(shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)"
  if [[ "$actual" != "$AS_SHA256" ]]; then
    log "Checksum mismatch for $ARCHIVE"
    log "  expected $AS_SHA256"
    log "  actual   $actual"
    rm -f "$ARCHIVE"
    exit 1
  fi

  log "==> Extracting"
  mkdir -p "$IDE_DIR"
  # --strip-components=1: the tarball wraps everything in an `android-studio/` directory, and both
  # Gradle and the verifier want the IDE root itself, not its parent.
  if ! tar -xzf "$ARCHIVE" -C "$IDE_DIR" --strip-components=1; then
    log "Extraction failed — removing the archive and directory so the next run refetches."
    rm -rf "$IDE_DIR" "$ARCHIVE"
    exit 1
  fi
  if [[ ! -f "$IDE_DIR/build.txt" ]]; then
    log "Extracted $ARCHIVE but $IDE_DIR/build.txt is missing — not a usable IDE."
    exit 1
  fi
fi

log "==> Android Studio $AS_VERSION ($(cat "$IDE_DIR/build.txt")) at $IDE_DIR"
echo "$IDE_DIR"
