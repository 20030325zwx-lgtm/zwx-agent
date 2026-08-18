#!/usr/bin/env sh
set -eu

DESKTOP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT_DIR=$(CDPATH= cd -- "$DESKTOP_DIR/.." && pwd)

(cd "$ROOT_DIR" && sh ./mvnw -q -DskipTests clean package)
mkdir -p "$DESKTOP_DIR/server"
cp "$ROOT_DIR/target/zwx-agent-0.0.1-SNAPSHOT.jar" "$DESKTOP_DIR/server/zwx-agent.jar"
