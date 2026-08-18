#!/usr/bin/env sh
set -eu

DESKTOP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RUNTIME_DIR="$DESKTOP_DIR/runtime"
JAVA_HOME_21=$(/usr/libexec/java_home -v 21)

rm -rf "$RUNTIME_DIR"
"$JAVA_HOME_21/bin/jlink" --add-modules java.se,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$RUNTIME_DIR"
