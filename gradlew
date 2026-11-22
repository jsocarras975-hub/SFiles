#!/bin/sh
# BoardMind Gradle wrapper launcher.
# Uses a system Gradle if one is installed; otherwise downloads the
# distribution named in gradle/wrapper/gradle-wrapper.properties
# (Gradle 8.11.1) into ~/.gradle/wrapper/dists and runs it.
# Requires: java (JDK 17+), curl or wget, unzip.
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
DIST_URL="$(grep '^distributionUrl=' "$WRAPPER_PROPS" | cut -d= -f2- | sed 's/\\:/:/g')"
DIST_NAME="$(basename "$DIST_URL" .zip)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/$DIST_NAME"

if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

# The zip unpacks to gradle-8.11.1/ (no "-bin" suffix), so locate the
# launcher instead of assuming $DIST_NAME/bin/gradle.
GRADLE_BIN="$(find "$DIST_DIR" -maxdepth 3 -path '*/bin/gradle' -type f 2>/dev/null | head -1)"
if [ -z "$GRADLE_BIN" ]; then
    echo "Downloading Gradle $DIST_NAME ..." >&2
    mkdir -p "$DIST_DIR"
    TMP_ZIP="$(mktemp /tmp/gradle-XXXXXX.zip)"
    if command -v curl >/dev/null 2>&1; then
        curl -L -o "$TMP_ZIP" "$DIST_URL"
    else
        wget -O "$TMP_ZIP" "$DIST_URL"
    fi
    unzip -q -o "$TMP_ZIP" -d "$DIST_DIR"
    rm -f "$TMP_ZIP"
    GRADLE_BIN="$(find "$DIST_DIR" -maxdepth 3 -path '*/bin/gradle' -type f 2>/dev/null | head -1)"
fi

exec "$GRADLE_BIN" "$@"
