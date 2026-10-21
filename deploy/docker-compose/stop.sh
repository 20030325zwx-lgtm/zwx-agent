#!/usr/bin/env sh
set -eu

WORKDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker compose --env-file "$WORKDIR/.env" -f "$WORKDIR/docker-compose.yml" down
