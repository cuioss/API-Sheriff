#!/bin/bash
# Shared helpers for resolving the Docker Compose command and probing the daemon.
# Source this file: `source "$(dirname "$0")/lib-docker-compose.sh"`
#
# Rationale: not every host wires the Compose v2 plugin into the docker CLI
# (`docker compose`). Rancher Desktop, for example, ships the standalone
# `docker-compose` binary instead. Hardcoding `docker compose` makes it fail
# with "unknown shorthand flag: 'f'" because docker parses `-f` as a top-level
# option. Resolve whichever form is actually available.

# Prints the working compose invocation (either "docker compose" or
# "docker-compose"), or nothing if neither is available. Returns non-zero when
# no compose command exists.
resolve_compose_cmd() {
    if docker compose version >/dev/null 2>&1; then
        echo "docker compose"
        return 0
    fi
    if command -v docker-compose >/dev/null 2>&1; then
        echo "docker-compose"
        return 0
    fi
    return 1
}

# Returns 0 when the Docker daemon is reachable, non-zero otherwise.
docker_daemon_up() {
    docker info >/dev/null 2>&1
}
