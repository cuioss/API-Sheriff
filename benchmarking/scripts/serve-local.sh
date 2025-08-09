#!/bin/bash
# Simple HTTP server for local testing of benchmark templates
# Usage: ./serve-local.sh [port]

PORT="${1:-8080}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATES_DIR="$(cd "$SCRIPT_DIR/../doc/templates" && pwd)"

echo "🚀 Starting local HTTP server for benchmark templates"
echo "📁 Serving from: $TEMPLATES_DIR"
echo "🌐 URL: http://localhost:$PORT"
echo ""
echo "Available pages:"
echo "  - http://localhost:$PORT/index-visualizer.html    (Micro Benchmarks)"
echo "  - http://localhost:$PORT/integration-index.html   (Integration Tests)"
echo "  - http://localhost:$PORT/step-metrics-visualizer.html (Step Metrics)"
echo "  - http://localhost:$PORT/performance-trends.html  (Performance Trends)"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

# Change to templates directory
cd "$TEMPLATES_DIR"

# Try Python 3 first, then Python 2
if command -v python3 &> /dev/null; then
    python3 -m http.server "$PORT"
elif command -v python &> /dev/null; then
    python -m SimpleHTTPServer "$PORT"
else
    echo "❌ Error: Python is not installed. Please install Python to use this script."
    echo "Alternative: Use any other HTTP server like 'npx http-server' or 'php -S localhost:$PORT'"
    exit 1
fi