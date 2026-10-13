#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
LOCAL="$ROOT/.local"
CONSUMER_DIR="$ROOT/tools/validation/t31-consumer"

fail() { echo "ERROR: $*" >&2; exit 1; }

VERSIONS_FILE="$ROOT/gradle/toolchain.versions"
[[ -f "$VERSIONS_FILE" ]] || fail "Missing $VERSIONS_FILE"

version_value() {
    local key="$1"
    sed -n "s/^${key}=\(.*\)$/\1/p" "$VERSIONS_FILE" | head -1
}

JDK_VERSION="$(version_value jdk.version)"
PLATFORM="$(uname -s)/$(uname -m)"
case "$PLATFORM" in
    Darwin/arm64) JDK_OS=mac ;;
    Linux/*) JDK_OS=linux ;;
    *) fail "Unsupported platform: $PLATFORM" ;;
esac

JDK_DIR="$LOCAL/jdk/jdk-$JDK_VERSION"
if [[ "$JDK_OS" == mac ]]; then JDK_HOME="$JDK_DIR/Contents/Home"; else JDK_HOME="$JDK_DIR"; fi

[[ -x "$JDK_HOME/bin/java" ]] || fail "Project JDK not found at $JDK_HOME. Run ./scripts/dev bootstrap first."

export JAVA_HOME="$JDK_HOME"
export GRADLE_USER_HOME="$LOCAL/gradle-user-home"
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR="$LOCAL/tmp/"

mkdir -p "$LOCAL/tmp"

cd "$CONSUMER_DIR"
exec "$ROOT/gradlew" "--project-dir=$CONSUMER_DIR" \
    "-Dorg.gradle.java.home=$JAVA_HOME" \
    "-Djava.io.tmpdir=$LOCAL/tmp" \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" \
    "-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$LOCAL/tmp" \
    "$@"
