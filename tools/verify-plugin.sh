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
# Usage:
#   tools/verify-plugin.sh                 # verify against the default IDE below
#   IDE_VERSION=2025.3 tools/verify-plugin.sh
#
set -euo pipefail

# Must match `sinceBuild` in build.gradle.kts — that is the floor the listing claims to support, so it
# is the version the artifact has to be clean against.
IDE_VERSION="${IDE_VERSION:-2025.3}"
VERIFIER_VERSION="${VERIFIER_VERSION:-1.388}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${VERIFY_WORK_DIR:-${TMPDIR:-/tmp}/sightline-verify}"
mkdir -p "$WORK"

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"

VERIFIER_JAR="$WORK/verifier-cli-$VERIFIER_VERSION.jar"
IDE_ZIP="$WORK/ideaIC-$IDE_VERSION.zip"
IDE_DIR="$WORK/ideaIC-$IDE_VERSION"

echo "==> Building the plugin"
( cd "$ROOT" && ./gradlew buildPlugin -q )

ARTIFACT="$(ls -t "$ROOT"/build/distributions/*.zip | head -1)"
echo "    $ARTIFACT"

if [[ ! -f "$VERIFIER_JAR" ]]; then
  echo "==> Downloading Plugin Verifier $VERIFIER_VERSION"
  curl -fsSL -o "$VERIFIER_JAR" \
    "https://repo1.maven.org/maven2/org/jetbrains/intellij/plugins/verifier-cli/$VERIFIER_VERSION/verifier-cli-$VERIFIER_VERSION-all.jar"
fi

if [[ ! -d "$IDE_DIR" ]]; then
  if [[ ! -f "$IDE_ZIP" ]]; then
    echo "==> Downloading IntelliJ IDEA Community $IDE_VERSION (~620 MB, cached in $WORK)"
    # The coordinate the Gradle plugin gets wrong. Note the `com/jetbrains/intellij/idea` path.
    curl -fsSL -o "$IDE_ZIP" \
      "https://cache-redirector.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC/$IDE_VERSION/ideaIC-$IDE_VERSION.zip"
  fi
  echo "==> Extracting"
  mkdir -p "$IDE_DIR"
  unzip -q -o "$IDE_ZIP" -d "$IDE_DIR"
fi

echo "==> Verifying against $(cat "$IDE_DIR/build.txt" 2>/dev/null || echo "$IDE_VERSION")"
echo

# `-team-city` is deliberately NOT passed: the plain report is the readable one, and this is run by a
# human before a submission rather than by CI.
set +e
"$JAVA" -jar "$VERIFIER_JAR" check-plugin "$ARTIFACT" "$IDE_DIR" 2>&1 \
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
