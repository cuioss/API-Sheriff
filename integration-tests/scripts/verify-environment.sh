#!/bin/bash
# Setup environment for API Gateway Integration Tests

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$(dirname "$PROJECT_DIR")")"

echo "🔧 Setting up API Gateway Integration Tests environment"

cd "${PROJECT_DIR}"

# Check Docker requirements
echo "🐳 Checking Docker requirements..."
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed or not in PATH"
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "❌ Docker Compose v2 is not available"
    exit 1
fi

echo "✅ Docker: $(docker --version)"
echo "✅ Docker Compose: $(docker compose version)"

# Generate certificates
echo "🔐 Generating certificates..."
cd src/main/docker/certificates
./generate-certificates.sh
cd "${PROJECT_DIR}"

# Verify Maven wrapper setup
echo "📦 Checking Maven wrapper setup..."
if [ ! -f "${ROOT_DIR}/mvnw" ]; then
    echo "❌ Maven wrapper (mvnw) not found at ${ROOT_DIR}/mvnw"
    exit 1
fi

cd "${ROOT_DIR}"
echo "✅ Maven Wrapper: $(./mvnw --version | head -1)"
cd "${PROJECT_DIR}"

# Check if native build tools are available
echo "⚙️  Checking GraalVM native-image..."
if command -v native-image &> /dev/null; then
    echo "✅ Native Image: $(native-image --version | head -1)"
else
    echo "⚠️  native-image not found in PATH - will try to use container build"
fi

echo ""
echo "✅ Environment setup complete!"
echo ""
echo "🚀 Next steps:"
echo "  ./scripts/start-integration-container.sh  # Start Docker containers"
echo "  ./scripts/stop-integration-container.sh   # Stop Docker containers"