#!/bin/bash
set -e

# Script to build native executable if it doesn't exist
# Targets the api-sheriff module (the deployable application)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"
APP_TARGET_DIR="${ROOT_DIR}/api-sheriff/target"

# Check if native executable exists in api-sheriff target
RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)

if [[ -z "$RUNNER_FILE" ]]; then
    echo "🔨 Native executable not found, building it now..."
    echo "This will take approximately 2 minutes..."

    # Build native executable using Quarkus Maven plugin on api-sheriff module
    cd "${ROOT_DIR}"
    ./mvnw --no-transfer-progress -Pnative quarkus:build -pl api-sheriff

    # Verify it was created
    RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)
    if [[ -z "$RUNNER_FILE" ]]; then
        echo "❌ Failed to build native executable"
        exit 1
    fi
    echo "✅ Native executable built: $(basename "$RUNNER_FILE")"
else
    echo "✅ Native executable already exists: $(basename "$RUNNER_FILE")"
fi
