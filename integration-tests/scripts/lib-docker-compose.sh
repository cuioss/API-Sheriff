#!/bin/bash
# Shared helpers for resolving the Docker Compose command, probing the daemon, and normalising the
# management root path the bring-up scripts derive from the Compose model.
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

# Prints the given management root path with every trailing slash removed: "/q/" becomes "/q", and a
# root path of exactly "/" becomes the EMPTY STRING.
#
# The empty result is the correct rendering of "served at the port root", not a degenerate case to
# guard against. Every caller appends an endpoint suffix that already begins with a slash
# ("/health", "/health/ready", "/metrics") directly after this value, so a retained trailing slash
# would produce a doubled separator ("//health") and any non-empty stand-in for "/" would prefix
# that suffix with a path segment the gateway does not serve under. Emitting nothing is what makes
# "${root}/health" resolve to "/health" for a gateway whose management interface sits at the port
# root.
#
# This is the ONE home for that trim. The bring-up scripts emit the label's value RAW from their
# Compose-model discovery and call this after reading each row, so the normalisation cannot drift
# between the Python discovery programs and the shell that consumes them.
normalize_root_path() {
    local path="$1"
    while [[ "$path" == */ ]]; do
        path="${path%/}"
    done
    printf '%s\n' "$path"
}
