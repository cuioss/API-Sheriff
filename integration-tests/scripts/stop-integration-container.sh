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

# The teardown must compose the SAME overlay set the bring-up did, and the lane says which that
# was: each Maven profile's stop-integration-app execution sets SHERIFF_IMAGE_TYPE to the same
# value it set on start-integration-container.sh, so the two ends of the lifecycle agree by
# declaration rather than by both guessing alike.
#
# Detecting it from the running containers is kept only for callers with no lane behind them —
# the pre-clean cleanup execution, the benchmarks module, a hand-run teardown. It is a fallback
# and not the rule because it answers wrongly in two ordinary situations: a stack that has
# already died leaves nothing to inspect and reads as "distroless", and an api-sheriff:jfr
# container belonging to a different checkout on the same daemon reads as "jfr" for a stack
# that is not one.
case "${SHERIFF_IMAGE_TYPE:-}" in
    distroless | jfr)
        MODE="$SHERIFF_IMAGE_TYPE"
        ;;
    "")
        if docker ps --format "{{.Image}}" | grep -q "^api-sheriff:jfr$"; then
            MODE="jfr"
        else
            MODE="distroless"
        fi
        ;;
    *)
        echo "❌ SHERIFF_IMAGE_TYPE=${SHERIFF_IMAGE_TYPE} is not a known image type"
        echo "Expected 'distroless' or 'jfr', or leave it unset to detect one."
        exit 1
        ;;
esac

if [[ "$MODE" == "jfr" ]]; then
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml -f docker-compose.jfr.yml"
else
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"
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
