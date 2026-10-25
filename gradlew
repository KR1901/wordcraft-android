#!/bin/sh
#
# Gradle startup script for POSIX generated wrapper launcher.
# This minimal launcher downloads no code itself; Gradle distribution is pinned
# by gradle/wrapper/gradle-wrapper.properties.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=9.8.2
DIST_NAME="gradle-${GRADLE_VERSION}-bin"
DIST_URL="https://services.gradle.org/distributions/${DIST_NAME}.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
INSTALL_DIR="$GRADLE_USER_HOME/wordcraft-wrapper/${DIST_NAME}"
GRADLE_BIN="$INSTALL_DIR/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$GRADLE_USER_HOME/wordcraft-wrapper"
  ZIP_PATH="$GRADLE_USER_HOME/wordcraft-wrapper/${DIST_NAME}.zip"
  if [ ! -f "$ZIP_PATH" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$DIST_URL" -o "$ZIP_PATH"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "$DIST_URL" -O "$ZIP_PATH"
    else
      echo "curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  unzip -q -o "$ZIP_PATH" -d "$INSTALL_DIR"
fi
exec "$GRADLE_BIN" "$@"
