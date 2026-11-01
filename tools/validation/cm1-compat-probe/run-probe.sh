#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
LOCAL="$ROOT/.local"
PROBE_DIR="$ROOT/tools/validation/cm1-compat-probe"
PROBE_HOME="$PROBE_DIR/.local/validation/cm1/probe-home"
PROBE_TMP="$PROBE_DIR/.local/tmp"
OUT_DIR="$ROOT/.local/validation/cm1"

fail() { echo "ERROR: $*" >&2; exit 1; }

# Recognizable toolchain platforms: macOS arm64, Linux x64, Linux arm64 (verified on macOS arm64).
PLATFORM="$(uname -s)/$(uname -m)"
case "$PLATFORM" in
    Darwin/arm64) JDK_OS=mac; JDK_ARCH=arm64 ;;
    Linux/x86_64 | Linux/amd64) JDK_OS=linux; JDK_ARCH=x64 ;;
    Linux/aarch64 | Linux/arm64) JDK_OS=linux; JDK_ARCH=arm64 ;;
    *) fail "Unsupported platform: $PLATFORM" ;;
esac

if command -v sha256sum >/dev/null 2>&1; then SHA=(sha256sum); else SHA=(shasum -a 256); fi

VERSIONS_FILE="$ROOT/gradle/toolchain.versions"
[[ -f "$VERSIONS_FILE" ]] || fail "Missing $VERSIONS_FILE"

JDK_VERSION="$(sed -n 's/^jdk.version=\(.*\)$/\1/p' "$VERSIONS_FILE" | head -1)"
JDK_DIR="$LOCAL/jdk/jdk-$JDK_VERSION"
if [[ "$JDK_OS" == mac ]]; then JDK_HOME="$JDK_DIR/Contents/Home"; else JDK_HOME="$JDK_DIR"; fi

check_install() {
    local dir="$1" manifest="$2"
    [[ -d "$dir" && -s "$manifest" ]] || fail "Missing installation/manifest: $dir; run scripts/dev bootstrap first."
    (cd "$dir" && "${SHA[@]}" -c "$manifest" >/dev/null) || fail "Damaged installation: $dir; remove it and rerun scripts/dev bootstrap."
}

[[ -x "$JDK_HOME/bin/java" && -f "$LOCAL/jdk/install.sha256" ]] || fail 'Run scripts/dev bootstrap first.'
check_install "$JDK_DIR" "$LOCAL/jdk/install.sha256"

GRADLE_VERSION="$(sed -n 's/^gradle.version=\(.*\)$/\1/p' "$VERSIONS_FILE" | head -1)"
GRADLE_DIST="$LOCAL/gradle-dist/gradle-$GRADLE_VERSION"
check_install "$GRADLE_DIST" "$LOCAL/gradle-dist/install.sha256"

export JAVA_HOME="$JDK_HOME"
export GRADLE_USER_HOME="$PROBE_HOME"
export TMPDIR="$PROBE_TMP"
# Kotlin daemon run files stay inside the probe directory (never the user-global directory).
export KOTLIN_DAEMON_RUN_FILES_PATH="$PROBE_TMP/kotlin-daemon"

mkdir -p "$PROBE_HOME" "$TMPDIR" "$KOTLIN_DAEMON_RUN_FILES_PATH" "$OUT_DIR"

cd "$PROBE_DIR"
"$GRADLE_DIST/bin/gradle" \
    "--project-dir=$PROBE_DIR" \
    "-Dorg.gradle.java.home=$JAVA_HOME" \
    "-Djava.io.tmpdir=$TMPDIR" \
    "-Dkotlin.daemon.runFilesPath=$KOTLIN_DAEMON_RUN_FILES_PATH" \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" \
    "-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR" \
    clean printResolvedVersions run \
    "--args=$OUT_DIR/probe-tmp $OUT_DIR/probe-report.txt $GRADLE_VERSION" \
    2>&1 | tee "$OUT_DIR/probe-run.log"
