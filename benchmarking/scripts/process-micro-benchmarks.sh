#!/bin/bash
# Process micro benchmark results and create badges/tracking
# Usage: process-micro-benchmarks.sh <jmh-result-file> <templates-dir> <output-dir> <commit-hash> <timestamp> <timestamp-with-time>

set -e

JMH_RESULT_FILE="$1"
TEMPLATES_DIR="$2"
OUTPUT_DIR="$3"
COMMIT_HASH="$4"
TIMESTAMP="$5"
TIMESTAMP_WITH_TIME="$6"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "🔬 Processing micro benchmark results..."
echo "📄 JMH Result File: $JMH_RESULT_FILE"

if [ ! -f "$JMH_RESULT_FILE" ]; then
    echo "❌ Error: JMH result file not found: $JMH_RESULT_FILE"
    exit 1
fi

# Ensure output directories exist
mkdir -p "$OUTPUT_DIR/badges"

# Copy JMH result to output directory for visualization
cp "$JMH_RESULT_FILE" "$OUTPUT_DIR/data/jmh-result.json"

# Load utility libraries and detect benchmark type
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/metrics-utils.sh"

echo "🔍 Detecting benchmark type..."
BENCHMARK_TYPE=$(detect_benchmark_type "$JMH_RESULT_FILE")

if [ "$BENCHMARK_TYPE" = "integration" ]; then
    echo "  ✓ Detected integration benchmarks"
else
    echo "  ✓ Detected micro benchmarks"
fi

# Create performance badge using unified script (includes simple badges)
echo "🏆 Creating performance badge for $BENCHMARK_TYPE benchmarks..."
if ! bash "$SCRIPT_DIR/create-unified-performance-badge.sh" "$BENCHMARK_TYPE" "$OUTPUT_DIR/data/jmh-result.json" "$OUTPUT_DIR/badges" "$COMMIT_HASH" "$TIMESTAMP" "$TIMESTAMP_WITH_TIME"; then
    echo "⚠️  Failed to create performance badge, creating fallback..."
    echo "{\"schemaVersion\":1,\"label\":\"Performance Score\",\"message\":\"Processing Error\",\"color\":\"red\"}" > "$OUTPUT_DIR/badges/performance-badge.json"
fi

# Performance Tracking System
echo "📈 Setting up performance tracking..."

# Create performance tracking data
TRACKING_OUTPUT=$(bash "$SCRIPT_DIR/create-performance-tracking.sh" "$OUTPUT_DIR/data/jmh-result.json" "$TEMPLATES_DIR" "$OUTPUT_DIR" "$COMMIT_HASH" 2>&1)
echo "$TRACKING_OUTPUT"

# Extract metrics from tracking script output
PERF_SCORE=$(echo "$TRACKING_OUTPUT" | grep "PERF_SCORE=" | cut -d'=' -f2 || echo "0")
PERF_THROUGHPUT=$(echo "$TRACKING_OUTPUT" | grep "PERF_THROUGHPUT=" | cut -d'=' -f2 || echo "0")
PERF_LATENCY=$(echo "$TRACKING_OUTPUT" | grep "PERF_LATENCY=" | cut -d'=' -f2 || echo "0")
PERF_RESILIENCE=$(echo "$TRACKING_OUTPUT" | grep "PERF_RESILIENCE=" | cut -d'=' -f2 || echo "0")

echo "📊 Extracted Performance Metrics:"
echo "  Score: $PERF_SCORE"
echo "  Throughput: $PERF_THROUGHPUT"
echo "  Latency: $PERF_LATENCY"
echo "  Resilience: $PERF_RESILIENCE"

# Update consolidated tracking and create trend badge
if [ -n "$PERF_SCORE" ] && [ "$PERF_SCORE" != "0" ] && [ "$PERF_SCORE" != "" ]; then
    echo "📈 Updating performance trends..."
    if bash "$SCRIPT_DIR/update-performance-trends.sh" "$TEMPLATES_DIR" "$OUTPUT_DIR" "$COMMIT_HASH" "$PERF_SCORE" "$PERF_THROUGHPUT" "$PERF_LATENCY" "$PERF_RESILIENCE"; then
        echo "✅ Performance trends updated successfully"
    else
        echo "⚠️  Failed to update performance trends, creating fallback badge..."
        echo "{\"schemaVersion\":1,\"label\":\"Performance Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/badges/trend-badge.json"
    fi
else
    echo "⚠️  Could not extract valid performance metrics for trend tracking"
    echo "{\"schemaVersion\":1,\"label\":\"Performance Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/badges/trend-badge.json"
fi

# Create micro benchmark processing results
MICRO_RESULTS_FILE="$OUTPUT_DIR/micro-processing-results.json"
cat > "$MICRO_RESULTS_FILE" << EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "commit": "$COMMIT_HASH",
  "micro_benchmarks": {
    "performance_score": "$PERF_SCORE",
    "throughput": "$PERF_THROUGHPUT",
    "latency": "$PERF_LATENCY",
    "resilience": "$PERF_RESILIENCE",
    "jmh_result_file": "$JMH_RESULT_FILE",
    "status": "success"
  }
}
EOF

echo "✅ Micro benchmark processing completed successfully"
echo "📄 Results saved to: $MICRO_RESULTS_FILE"