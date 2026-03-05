#!/bin/bash
# Stop API Sheriff Integration Tests Docker containers

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🛑 Stopping API Sheriff Integration Tests Docker containers"

cd "${PROJECT_DIR}"

# Detect if JFR image is running to use matching compose files
JFR_RUNNING=$(docker ps --format "{{.Image}}" | grep "^api-sheriff:jfr$" || true)

if [[ -n "$JFR_RUNNING" ]]; then
    COMPOSE_CMD="docker compose -f docker-compose.yml -f docker-compose.jfr.yml"
    MODE="jfr"
else
    COMPOSE_CMD="docker compose -f docker-compose.yml"
    MODE="distroless"
fi

# Stop and remove containers
echo "📦 Stopping Docker containers ($MODE mode)..."
$COMPOSE_CMD down

# Optional: Clean up images and volumes
if [ "$1" = "--clean" ]; then
    echo "🧹 Cleaning up Docker images and volumes..."
    $COMPOSE_CMD down --volumes --rmi all
fi

echo "✅ API Sheriff Integration Tests stopped successfully"

# Show final status
if $COMPOSE_CMD ps | grep -q "Up"; then
    echo "⚠️  Some containers are still running:"
    $COMPOSE_CMD ps
else
    echo "✅ All containers are stopped"
fi
