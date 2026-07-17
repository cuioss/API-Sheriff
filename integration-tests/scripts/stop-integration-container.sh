#!/bin/bash
# Stop API Sheriff Integration Tests Docker containers

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# shellcheck source=lib-docker-compose.sh
source "${SCRIPT_DIR}/lib-docker-compose.sh"

echo "🛑 Stopping API Sheriff Integration Tests Docker containers"

cd "${PROJECT_DIR}"

# This runs at Maven's pre-clean phase as a best-effort cleanup. If Docker isn't
# available or the daemon isn't running there is nothing to stop, so exit
# cleanly rather than failing `clean install`.
COMPOSE_BASE="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_BASE" ]]; then
    echo "ℹ️  Docker Compose not available — nothing to stop, skipping cleanup."
    exit 0
fi
if ! docker_daemon_up; then
    echo "ℹ️  Docker daemon not running — nothing to stop, skipping cleanup."
    exit 0
fi

# Detect if JFR image is running to use matching compose files
JFR_RUNNING=$(docker ps --format "{{.Image}}" | grep "^api-sheriff:jfr$" || true)

if [[ -n "$JFR_RUNNING" ]]; then
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml -f docker-compose.jfr.yml"
    MODE="jfr"
else
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"
    MODE="distroless"
fi

# Stop and remove containers. --remove-orphans also tears down containers from
# optional compose profiles (e.g. the benchmark nginx-static) that this base
# COMPOSE_CMD does not list; without it those orphans linger and block network
# removal, breaking the teardown.
echo "📦 Stopping Docker containers ($MODE mode)..."
$COMPOSE_CMD down --remove-orphans

# Optional: Clean up images and volumes
if [ "$1" = "--clean" ]; then
    echo "🧹 Cleaning up Docker images and volumes..."
    $COMPOSE_CMD down --remove-orphans --volumes --rmi all
fi

echo "✅ API Sheriff Integration Tests stopped successfully"

# Show final status
if $COMPOSE_CMD ps | grep -q "Up"; then
    echo "⚠️  Some containers are still running:"
    $COMPOSE_CMD ps
else
    echo "✅ All containers are stopped"
fi
