#!/bin/bash
# Master orchestration script for processing all benchmark types
# Usage: process-all-benchmarks.sh <benchmark-results-dir> <templates-dir> <output-dir> <commit-hash> <timestamp>

set -e

BENCHMARK_RESULTS_DIR="$1"
TEMPLATES_DIR="$2"
OUTPUT_DIR="$3"
COMMIT_HASH="$4"
TIMESTAMP="$5"
TIMESTAMP_WITH_TIME="$6"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "🚀 Processing all benchmark results..."
echo "📁 Benchmark Results: $BENCHMARK_RESULTS_DIR"
echo "📋 Templates: $TEMPLATES_DIR"
echo "📦 Output: $OUTPUT_DIR"
echo "🔗 Commit: $COMMIT_HASH"
echo "📅 Timestamp: $TIMESTAMP"

# Initialize processing results
RESULTS_FILE="$OUTPUT_DIR/processing-results.json"
mkdir -p "$OUTPUT_DIR"

# Create initial results structure
cat > "$RESULTS_FILE" << EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "commit": "$COMMIT_HASH",
  "processing": {
    "micro": { "status": "not_found", "message": "No micro benchmark results" },
    "integration": { "status": "not_found", "message": "No integration benchmark results" }
  },
  "errors": []
}
EOF

# Create header for badge markdown
echo "## Benchmark Results ($TIMESTAMP)" > "$OUTPUT_DIR/badge-markdown.txt"

# Prepare basic GitHub Pages structure
echo "📋 Preparing GitHub Pages structure..."
bash "$SCRIPT_DIR/prepare-github-pages.sh" "$BENCHMARK_RESULTS_DIR" "$TEMPLATES_DIR" "$OUTPUT_DIR"

# Process micro benchmarks
echo "🔬 Checking for micro benchmark results..."
if [ -f "$BENCHMARK_RESULTS_DIR/micro-benchmark-result.json" ]; then
    echo "✅ Found micro benchmark results, processing..."
    
    # Copy for legacy compatibility
    cp "$BENCHMARK_RESULTS_DIR/micro-benchmark-result.json" "$BENCHMARK_RESULTS_DIR/jmh-result.json"
    
    # Process micro benchmarks
    if bash "$SCRIPT_DIR/process-micro-benchmarks.sh" "$BENCHMARK_RESULTS_DIR/jmh-result.json" "$TEMPLATES_DIR" "$OUTPUT_DIR" "$COMMIT_HASH" "$TIMESTAMP" "$TIMESTAMP_WITH_TIME"; then
        # Update results file with success
        jq '.processing.micro = {"status": "success", "message": "Micro benchmarks processed successfully"}' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "✅ Micro benchmarks processed successfully"
    else
        # Update results file with error
        jq '.processing.micro = {"status": "error", "message": "Failed to process micro benchmarks"} | .errors += ["Micro benchmark processing failed"]' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "❌ Failed to process micro benchmarks"
    fi
else
    echo "⚠️  No micro benchmark results found"
    # Create fallback badges for micro benchmarks
    mkdir -p "$OUTPUT_DIR/badges"
    echo "{\"schemaVersion\":1,\"label\":\"Performance Score\",\"message\":\"No Data\",\"color\":\"red\"}" > "$OUTPUT_DIR/badges/performance-badge.json"
    echo "{\"schemaVersion\":1,\"label\":\"Performance Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/badges/trend-badge.json"
fi

# Process integration benchmarks
echo "🔗 Checking for integration benchmark results..."
# Check for combined file first (preferred approach), then fallback to separate files
if [ -f "$BENCHMARK_RESULTS_DIR/integration-benchmark-result.json" ]; then
    echo "✅ Found combined integration benchmark results, processing..."
    
    # Process the combined JMH benchmark file for integration tests
    if bash "$SCRIPT_DIR/process-integration-jmh-benchmarks.sh" "$BENCHMARK_RESULTS_DIR/integration-benchmark-result.json" "$OUTPUT_DIR" "$COMMIT_HASH" "$TIMESTAMP"; then
        # Update results file with success
        jq '.processing.integration = {"status": "success", "message": "Integration benchmarks processed successfully"}' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "✅ Integration benchmarks processed successfully"
        
        # Add integration benchmark link to main page
        echo "- [Integration Benchmark Results](integration-index.html)" >> "$OUTPUT_DIR/badge-markdown.txt"
    else
        # Update results file with error
        jq '.processing.integration = {"status": "error", "message": "Failed to process integration benchmarks"} | .errors += ["Integration benchmark processing failed"]' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "❌ Failed to process integration benchmarks"
    fi
elif [ -f "$BENCHMARK_RESULTS_DIR/health-check-results.json" ] && [ -f "$BENCHMARK_RESULTS_DIR/jwt-validation-results.json" ]; then
    echo "✅ Found separate integration benchmark results, processing..."
    
    # Process integration benchmarks with separate files
    if bash "$SCRIPT_DIR/process-integration-benchmarks.sh" "$BENCHMARK_RESULTS_DIR/health-check-results.json" "$BENCHMARK_RESULTS_DIR/jwt-validation-results.json" "$OUTPUT_DIR" "$COMMIT_HASH"; then
        # Update results file with success
        jq '.processing.integration = {"status": "success", "message": "Integration benchmarks processed successfully"}' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "✅ Integration benchmarks processed successfully"
        
        # Add integration benchmark link to main page
        echo "- [Integration Benchmark Results](integration-index.html)" >> "$OUTPUT_DIR/badge-markdown.txt"
    else
        # Update results file with error
        jq '.processing.integration = {"status": "error", "message": "Failed to process integration benchmarks"} | .errors += ["Integration benchmark processing failed"]' "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
        echo "❌ Failed to process integration benchmarks"
    fi
else
    echo "⚠️  No integration benchmark results found"
    # Create fallback badges for integration benchmarks
    mkdir -p "$OUTPUT_DIR/badges"
    echo "{\"schemaVersion\":1,\"label\":\"Integration Performance\",\"message\":\"No Data\",\"color\":\"red\"}" > "$OUTPUT_DIR/badges/integration-performance-badge.json"
    echo "{\"schemaVersion\":1,\"label\":\"Integration Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/badges/integration-trend-badge.json"
fi

# Final status report
echo ""
echo "📊 Processing Summary:"
MICRO_STATUS=$(jq -r '.processing.micro.status' "$RESULTS_FILE")
INTEGRATION_STATUS=$(jq -r '.processing.integration.status' "$RESULTS_FILE")
ERROR_COUNT=$(jq '.errors | length' "$RESULTS_FILE")

echo "  🔬 Micro Benchmarks: $MICRO_STATUS"
echo "  🔗 Integration Benchmarks: $INTEGRATION_STATUS"
echo "  ❌ Errors: $ERROR_COUNT"

if [ "$ERROR_COUNT" -gt 0 ]; then
    echo "⚠️  Some processing steps failed. Check $RESULTS_FILE for details."
    exit 1
else
    echo "✅ All benchmark processing completed successfully!"
fi