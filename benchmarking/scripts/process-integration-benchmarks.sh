#!/bin/bash
# Process integration benchmark results and create badges/visualization
# Usage: process-integration-benchmarks.sh <health-check-results.json> <jwt-validation-results.json> <output-dir> <commit-hash>

set -e

HEALTH_RESULTS="$1"
JWT_RESULTS="$2"
OUTPUT_DIR="$3"
COMMIT_HASH="$4"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "🔗 Processing integration benchmark results..."
echo "💚 Health Check Results: $HEALTH_RESULTS"
echo "🔐 JWT Validation Results: $JWT_RESULTS"

# Validate input files
if [ ! -f "$HEALTH_RESULTS" ]; then
    echo "❌ Error: Health check results not found: $HEALTH_RESULTS"
    exit 1
fi

if [ ! -f "$JWT_RESULTS" ]; then
    echo "❌ Error: JWT validation results not found: $JWT_RESULTS"
    exit 1
fi

# Ensure output directories exist
mkdir -p "$OUTPUT_DIR/badges"
mkdir -p "$OUTPUT_DIR/data"

# Create combined integration results file for compatibility
echo "🔄 Merging integration benchmark results..."
INTEGRATION_RESULT="$OUTPUT_DIR/data/integration-result.json"

jq -n --slurpfile health "$HEALTH_RESULTS" --slurpfile jwt "$JWT_RESULTS" '
  {
    "timestamp": (now | strftime("%Y-%m-%dT%H:%M:%SZ")),
    "commit": "'$COMMIT_HASH'",
    "health_check": $health[0],
    "jwt_validation": $jwt[0],
    "jwt_overhead_ms": ($jwt[0].latency_p95_ms - $health[0].latency_p95_ms)
  }' > "$INTEGRATION_RESULT"

echo "✅ Integration results merged successfully"

# Copy integration template to unified structure
TEMPLATES_DIR="$(dirname "$SCRIPT_DIR")/doc/templates"
if [ -f "$TEMPLATES_DIR/integration-index.html" ]; then
    cp "$TEMPLATES_DIR/integration-index.html" "$OUTPUT_DIR/integration-index.html"
    echo "📋 Integration template copied"
else
    echo "⚠️  Integration template not found at: $TEMPLATES_DIR/integration-index.html"
fi

# Extract metrics from integration results
echo "📊 Extracting integration metrics..."

HEALTH_LATENCY=$(jq -r '.health_check.latency_p95_ms // 0' "$INTEGRATION_RESULT")
JWT_LATENCY=$(jq -r '.jwt_validation.latency_p95_ms // 0' "$INTEGRATION_RESULT")
JWT_THROUGHPUT=$(jq -r '.jwt_validation.throughput_rps // 0' "$INTEGRATION_RESULT")
JWT_OVERHEAD=$(jq -r '.jwt_overhead_ms // 0' "$INTEGRATION_RESULT")

echo "Integration Performance Metrics:"
echo "  Health Check P95: ${HEALTH_LATENCY}ms"
echo "  JWT Validation P95: ${JWT_LATENCY}ms"
echo "  JWT Throughput: ${JWT_THROUGHPUT} ops/s"
echo "  JWT Overhead: ${JWT_OVERHEAD}ms"

# Validate extracted metrics
if [ "$HEALTH_LATENCY" = "null" ] || [ "$HEALTH_LATENCY" = "" ]; then HEALTH_LATENCY="0"; fi
if [ "$JWT_LATENCY" = "null" ] || [ "$JWT_LATENCY" = "" ]; then JWT_LATENCY="0"; fi
if [ "$JWT_THROUGHPUT" = "null" ] || [ "$JWT_THROUGHPUT" = "" ]; then JWT_THROUGHPUT="0"; fi
if [ "$JWT_OVERHEAD" = "null" ] || [ "$JWT_OVERHEAD" = "" ]; then JWT_OVERHEAD="0"; fi

# Create integration performance badges with smart color coding
echo "🏆 Creating integration performance badges..."

# JWT P95 Latency Badge
JWT_LATENCY_COLOR="red"
if (( $(echo "$JWT_LATENCY <= 50" | bc -l 2>/dev/null || echo "0") )); then
    JWT_LATENCY_COLOR="green"
elif (( $(echo "$JWT_LATENCY <= 100" | bc -l 2>/dev/null || echo "0") )); then
    JWT_LATENCY_COLOR="yellow"
elif (( $(echo "$JWT_LATENCY <= 200" | bc -l 2>/dev/null || echo "0") )); then
    JWT_LATENCY_COLOR="orange"
fi

echo "{\"schemaVersion\":1,\"label\":\"JWT P95 Latency\",\"message\":\"${JWT_LATENCY}ms\",\"color\":\"$JWT_LATENCY_COLOR\"}" > "$OUTPUT_DIR/badges/integration-performance-badge.json"

# JWT Throughput Badge
JWT_THROUGHPUT_COLOR="red"
if (( $(echo "$JWT_THROUGHPUT >= 1000" | bc -l 2>/dev/null || echo "0") )); then
    JWT_THROUGHPUT_COLOR="green"
elif (( $(echo "$JWT_THROUGHPUT >= 500" | bc -l 2>/dev/null || echo "0") )); then
    JWT_THROUGHPUT_COLOR="yellow"
elif (( $(echo "$JWT_THROUGHPUT >= 100" | bc -l 2>/dev/null || echo "0") )); then
    JWT_THROUGHPUT_COLOR="orange"
fi

# Format throughput for display
if (( $(echo "$JWT_THROUGHPUT >= 1000" | bc -l 2>/dev/null || echo "0") )); then
    JWT_THROUGHPUT_DISPLAY=$(echo "scale=1; $JWT_THROUGHPUT / 1000" | bc -l)k
else
    JWT_THROUGHPUT_DISPLAY=$(printf "%.0f" "$JWT_THROUGHPUT" 2>/dev/null || echo "$JWT_THROUGHPUT")
fi

echo "{\"schemaVersion\":1,\"label\":\"JWT Throughput\",\"message\":\"${JWT_THROUGHPUT_DISPLAY} ops/s\",\"color\":\"$JWT_THROUGHPUT_COLOR\"}" > "$OUTPUT_DIR/badges/integration-throughput-badge.json"

# JWT Overhead Badge
JWT_OVERHEAD_COLOR="red"
if (( $(echo "$JWT_OVERHEAD <= 10" | bc -l 2>/dev/null || echo "0") )); then
    JWT_OVERHEAD_COLOR="green"
elif (( $(echo "$JWT_OVERHEAD <= 25" | bc -l 2>/dev/null || echo "0") )); then
    JWT_OVERHEAD_COLOR="yellow"
elif (( $(echo "$JWT_OVERHEAD <= 50" | bc -l 2>/dev/null || echo "0") )); then
    JWT_OVERHEAD_COLOR="orange"
fi

echo "{\"schemaVersion\":1,\"label\":\"JWT Overhead\",\"message\":\"${JWT_OVERHEAD}ms\",\"color\":\"$JWT_OVERHEAD_COLOR\"}" > "$OUTPUT_DIR/badges/integration-overhead-badge.json"

echo "✅ Integration badges created successfully"

# Create integration processing results
INTEGRATION_RESULTS_FILE="$OUTPUT_DIR/integration-processing-results.json"
cat > "$INTEGRATION_RESULTS_FILE" << EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "commit": "$COMMIT_HASH",
  "integration_benchmarks": {
    "health_check_latency_p95_ms": "$HEALTH_LATENCY",
    "jwt_validation_latency_p95_ms": "$JWT_LATENCY",
    "jwt_throughput_rps": "$JWT_THROUGHPUT",
    "jwt_overhead_ms": "$JWT_OVERHEAD",
    "health_results_file": "$HEALTH_RESULTS",
    "jwt_results_file": "$JWT_RESULTS",
    "combined_results_file": "$INTEGRATION_RESULT",
    "status": "success"
  }
}
EOF

echo "✅ Integration benchmark processing completed successfully"
echo "📄 Results saved to: $INTEGRATION_RESULTS_FILE"
echo "📄 Combined results available at: $INTEGRATION_RESULT"