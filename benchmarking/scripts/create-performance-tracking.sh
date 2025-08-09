#!/bin/bash
# Create performance tracking data from benchmark results
# Usage: create-performance-tracking.sh <jmh-result-file> <templates-dir> <output-dir> <commit-hash>

set -e

JMH_RESULT_FILE="$1"
TEMPLATES_DIR="$2"
OUTPUT_DIR="$3"
COMMIT_HASH="$4"

if [ ! -f "$JMH_RESULT_FILE" ]; then
  echo "Error: JMH result file not found: $JMH_RESULT_FILE"
  exit 1
fi

echo "Creating performance tracking data..."

# Load utility libraries
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/metrics-utils.sh"

# Get environment info
eval $(get_environment_info)

# Create performance tracking directory
mkdir -p "$OUTPUT_DIR/data/tracking"
mkdir -p "$OUTPUT_DIR/badges"

# Run the unified performance badge script to get metrics and capture in a metrics file
METRICS_FILE="$OUTPUT_DIR/data/tracking/metrics-temp.sh"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Detect benchmark type
BENCHMARK_TYPE=$(detect_benchmark_type "$JMH_RESULT_FILE")

bash "$SCRIPT_DIR/create-unified-performance-badge.sh" "$BENCHMARK_TYPE" "$JMH_RESULT_FILE" "$OUTPUT_DIR/badges" > "$METRICS_FILE.log" 2>&1

# Read the badge output immediately after script execution
BADGE_OUTPUT_CONTENT=$(cat "$METRICS_FILE.log")

# Extract metrics from badge script output and create a sourceable metrics file
{
  echo "# Performance metrics extracted from create-unified-performance-badge.sh"
  echo "$BADGE_OUTPUT_CONTENT" | grep "PERFORMANCE_SCORE=" | head -1 || echo "PERFORMANCE_SCORE=0"
  echo "$BADGE_OUTPUT_CONTENT" | grep "THROUGHPUT_OPS_PER_SEC=" | head -1 || echo "THROUGHPUT_OPS_PER_SEC=0" 
  echo "$BADGE_OUTPUT_CONTENT" | grep "AVERAGE_TIME_SEC=" | head -1 || echo "AVERAGE_TIME_SEC=0"
  echo "$BADGE_OUTPUT_CONTENT" | grep "THROUGHPUT_DISPLAY=" | head -1 || echo "THROUGHPUT_DISPLAY=0"
  echo "$BADGE_OUTPUT_CONTENT" | grep "ERROR_RESILIENCE_OPS_PER_SEC=" | head -1 || echo "ERROR_RESILIENCE_OPS_PER_SEC=0"
  echo "$BADGE_OUTPUT_CONTENT" | grep "AVG_TIME_MICROS=" | head -1 || echo "AVG_TIME_MICROS=0"
} > "$METRICS_FILE"

# Source the metrics file for more robust parsing
source "$METRICS_FILE" || {
  echo "Error: Failed to source metrics from create-unified-performance-badge.sh"
  exit 1
}

# Clean up temporary files
rm -f "$METRICS_FILE" "$METRICS_FILE.log"

if [ -z "$PERFORMANCE_SCORE" ] || [ "$PERFORMANCE_SCORE" = "0" ]; then
  echo "Warning: Could not extract valid performance metrics for tracking"
  exit 1
fi

# Create performance run JSON from template using envsubst for safe variable substitution
export TIMESTAMP="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
export COMMIT_HASH="$COMMIT_HASH"
export PERFORMANCE_SCORE="$PERFORMANCE_SCORE"
export THROUGHPUT_VALUE="$THROUGHPUT_OPS_PER_SEC"
export AVERAGE_TIME_SEC="$AVERAGE_TIME_SEC"
export THROUGHPUT_DISPLAY="$THROUGHPUT_DISPLAY"
export ERROR_RESILIENCE_VALUE="$ERROR_RESILIENCE_OPS_PER_SEC"
export THROUGHPUT_OPS_PER_SEC="$THROUGHPUT_OPS_PER_SEC"
export AVG_TIME_MICROS="$AVG_TIME_MICROS"
export ERROR_RESILIENCE_OPS_PER_SEC="$ERROR_RESILIENCE_OPS_PER_SEC"
export JAVA_VERSION="$JAVA_VERSION"
export JVM_ARGS="$JVM_ARGS_VALUE"
export OS_NAME="$OS_NAME"

TIMESTAMP_FILE=$(date -u +"%Y%m%d-%H%M%S")
envsubst < "$TEMPLATES_DIR/performance-run.json" > "$OUTPUT_DIR/data/tracking/performance-$TIMESTAMP_FILE.json"

echo "Created performance tracking file: data/tracking/performance-$TIMESTAMP_FILE.json"

# Export metrics for use by calling script
echo "PERF_SCORE=$PERFORMANCE_SCORE"
echo "PERF_THROUGHPUT=$THROUGHPUT_DISPLAY"
echo "PERF_LATENCY=$AVERAGE_TIME_SEC"
echo "PERF_RESILIENCE=$ERROR_RESILIENCE_OPS_PER_SEC"
