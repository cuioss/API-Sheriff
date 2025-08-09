#!/bin/bash
set -e

# Script to build native executable if it doesn't exist

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Check if native executable exists
RUNNER_FILE=$(find "${PROJECT_DIR}/target" -name "*-runner" -type f 2>/dev/null | head -n 1)

if [[ -z "$RUNNER_FILE" ]]; then
    echo "🔨 Native executable not found, building it now..."
    echo "This will take approximately 2 minutes..."
    
    # Build native executable using Quarkus Maven plugin
    # Find the root directory with mvnw
    ROOT_DIR=$(cd "${PROJECT_DIR}/../.." && pwd)
    cd "${ROOT_DIR}"
    ./mvnw --no-transfer-progress -Pintegration-tests quarkus:build -pl api-sheriff-quarkus-parent/api-sheriff-quarkus-integration-tests
    
    # Verify it was created
    RUNNER_FILE=$(find "${PROJECT_DIR}/target" -name "*-runner" -type f 2>/dev/null | head -n 1)
    if [[ -z "$RUNNER_FILE" ]]; then
        echo "❌ Failed to build native executable"
        exit 1
    fi
    echo "✅ Native executable built: $(basename "$RUNNER_FILE")"
else
    echo "✅ Native executable already exists: $(basename "$RUNNER_FILE")"
fi