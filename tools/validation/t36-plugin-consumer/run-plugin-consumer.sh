#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
LOCAL="$ROOT/.local"
CONSUMER_DIR="$ROOT/tools/validation/t36-plugin-consumer"

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

version_value() {
    local key="$1"
    sed -n "s/^${key}=\(.*\)$/\1/p" "$VERSIONS_FILE" | head -1
}

JDK_VERSION="$(version_value jdk.version)"
JDK_DIR="$LOCAL/jdk/jdk-$JDK_VERSION"
if [[ "$JDK_OS" == mac ]]; then JDK_HOME="$JDK_DIR/Contents/Home"; else JDK_HOME="$JDK_DIR"; fi

check_install() {
    local dir="$1" manifest="$2"
    [[ -d "$dir" && -s "$manifest" ]] || fail "Missing installation/manifest: $dir; run scripts/dev bootstrap first."
    (cd "$dir" && "${SHA[@]}" -c "$manifest" >/dev/null) || fail "Damaged installation: $dir; remove it and rerun scripts/dev bootstrap."
}

[[ -x "$JDK_HOME/bin/java" && -f "$LOCAL/jdk/install.sha256" ]] || fail 'Run scripts/dev bootstrap first.'
check_install "$JDK_DIR" "$LOCAL/jdk/install.sha256"

export JAVA_HOME="$JDK_HOME"
export GRADLE_USER_HOME="$LOCAL/gradle-user-home"
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR="$LOCAL/tmp/"

mkdir -p "$LOCAL/tmp" "$LOCAL/tmp/kotlin-daemon"

cd "$CONSUMER_DIR"
exec "$ROOT/gradlew" "--project-dir=$CONSUMER_DIR" \
    "-Dorg.gradle.java.home=$JAVA_HOME" \
    "-Djava.io.tmpdir=$LOCAL/tmp" \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" \
    "-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$LOCAL/tmp" \
    "$@"
