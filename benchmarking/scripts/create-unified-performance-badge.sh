#!/bin/bash
# Unified Performance Badge Creation Script
# Handles both micro-benchmarks and integration benchmarks
# Usage: create-unified-performance-badge.sh <benchmark-type> <result-file> <output-directory> [commit-hash] [timestamp] [timestamp-with-time]

set -e

# Load utility libraries
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/badge-utils.sh"
source "$SCRIPT_DIR/lib/metrics-utils.sh"

BENCHMARK_TYPE="$1"  # "micro" or "integration"
RESULT_FILE="$2"
OUTPUT_DIR="$3"
COMMIT_HASH="${4:-unknown}"
TIMESTAMP="${5:-$(date +"%Y-%m-%d")}"
TIMESTAMP_WITH_TIME="${6:-$(date +"%Y-%m-%d %H:%M %Z")}"

if [ ! -f "$RESULT_FILE" ]; then
  echo "Error: Result file not found: $RESULT_FILE"
  exit 1
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "Creating unified performance badge for $BENCHMARK_TYPE benchmarks..."


# Process micro benchmarks
process_micro_benchmarks() {
    echo "Processing micro-benchmark results..."
    
    # Extract throughput data
    local throughput_entry=$(jq -r '.[] | select(.benchmark == "de.cuioss.jwt.validation.benchmark.standard.SimpleCoreValidationBenchmark.measureThroughput")' "$RESULT_FILE" 2>/dev/null)
    local throughput_score=$(echo "$throughput_entry" | jq -r '.primaryMetric.score' 2>/dev/null || echo "0")
    local throughput_unit=$(echo "$throughput_entry" | jq -r '.primaryMetric.scoreUnit' 2>/dev/null || echo "")
    
    # Extract average time data
    local avg_time_entry=$(jq -r '.[] | select(.benchmark == "de.cuioss.jwt.validation.benchmark.standard.SimpleCoreValidationBenchmark.measureAverageTime")' "$RESULT_FILE" 2>/dev/null)
    local avg_time_score=$(echo "$avg_time_entry" | jq -r '.primaryMetric.score' 2>/dev/null || echo "0")
    local avg_time_unit=$(echo "$avg_time_entry" | jq -r '.primaryMetric.scoreUnit' 2>/dev/null || echo "")
    
    # Extract error resilience data
    local error_resilience_entry=$(jq -r '.[] | select(.benchmark == "de.cuioss.jwt.validation.benchmark.standard.SimpleErrorLoadBenchmark.validateMixedTokens0")' "$RESULT_FILE" 2>/dev/null)
    local error_resilience_score=$(echo "$error_resilience_entry" | jq -r '.primaryMetric.score' 2>/dev/null || echo "0")
    local error_resilience_unit=$(echo "$error_resilience_entry" | jq -r '.primaryMetric.scoreUnit' 2>/dev/null || echo "")
    
    # Convert units
    local throughput_ops_per_sec=$(convert_to_ops_per_sec "$throughput_score" "$throughput_unit")
    local avg_time_ms=$(convert_to_milliseconds "$avg_time_score" "$avg_time_unit")
    local error_resilience_ops_per_sec=$(convert_to_ops_per_sec "$error_resilience_score" "$error_resilience_unit")
    
    # Calculate performance score
    local performance_score=$(calculate_performance_score "$throughput_ops_per_sec" "$avg_time_ms" "$error_resilience_ops_per_sec")
    
    # Format for display
    local formatted_score=$(printf "%.0f" "$performance_score")
    local throughput_display=$(format_throughput_display "$throughput_ops_per_sec")
    local formatted_avg_time_ms=$(printf "%.0f" "$avg_time_ms")
    
    # Create performance badge
    create_badge "Performance Score" "${formatted_score} (${throughput_display} ops/s, ${formatted_avg_time_ms}ms)" "brightgreen" "performance-badge.json"
    
    echo "Created micro-benchmark performance badge: Score=$formatted_score (Throughput=${throughput_display} ops/s, AvgTime=${formatted_avg_time_ms}ms)"
    
    # Export metrics for performance tracking
    echo "PERFORMANCE_SCORE=$formatted_score"
    echo "THROUGHPUT_OPS_PER_SEC=$throughput_ops_per_sec"
    echo "AVERAGE_TIME_SEC=$(echo "scale=6; $avg_time_ms / 1000" | bc -l)"
    echo "THROUGHPUT_DISPLAY=$throughput_display"
    echo "ERROR_RESILIENCE_OPS_PER_SEC=$error_resilience_ops_per_sec"
    echo "AVG_TIME_MICROS=$(echo "scale=0; $avg_time_ms * 1000" | bc -l)"
}

# Process integration benchmarks
process_integration_benchmarks() {
    echo "Processing integration benchmark results..."
    
    # Calculate average integration throughput (handle both ops/s and ops/ms)
    local avg_throughput=$(jq -r '
      [.[] | select(.benchmark and .mode == "thrpt")] |
      if length > 0 then
        map(
          if .primaryMetric.scoreUnit == "ops/ms" then
            .primaryMetric.score * 1000  # Convert ops/ms to ops/s
          else
            .primaryMetric.score
          end
        ) | add / length | . * 100 | round / 100
      else
        0
      end
    ' "$RESULT_FILE")
    
    # Calculate average integration latency (convert to milliseconds)
    local avg_latency_ms=$(jq -r '
      [.[] | select(.benchmark and (.mode == "avgt" or .primaryMetric.scoreUnit == "ms/op"))] |
      if length > 0 then
        (map(.primaryMetric.score) | add / length | . * 1000 | round / 1000)
      else
        0
      end
    ' "$RESULT_FILE")
    
    # Calculate integration performance score
    local integration_score=$(calculate_performance_score "$avg_throughput" "$avg_latency_ms" "0")
    
    # Format for display
    local formatted_score=$(printf "%.0f" "$integration_score")
    local throughput_display=$(format_throughput_display "$avg_throughput")
    local formatted_latency_ms=$(printf "%.0f" "$avg_latency_ms")
    
    # Determine badge color
    local badge_color=$(get_performance_badge_color "$integration_score")
    
    # Create integration performance badge (different name from micro benchmarks)
    create_badge "Integration Performance" "${formatted_score} (${throughput_display} ops/s, ${formatted_latency_ms}ms)" "$badge_color" "integration-performance-badge.json"
    
    # Create additional integration-specific badges
    local throughput_color=$(get_throughput_badge_color "$avg_throughput")
    local latency_color=$(get_latency_badge_color "$avg_latency_ms")
    
    create_badge "Throughput" "${throughput_display} ops/s" "$throughput_color" "throughput-badge.json"
    create_badge "Latency" "${formatted_latency_ms}ms" "$latency_color" "latency-badge.json"
    
    echo "Created integration benchmark badges: Score=$formatted_score (Throughput=${throughput_display} ops/s, Latency=${formatted_latency_ms}ms)"
    
    # Export metrics for performance tracking
    echo "PERFORMANCE_SCORE=$formatted_score"
    echo "THROUGHPUT_OPS_PER_SEC=$avg_throughput"
    echo "AVERAGE_TIME_SEC=$(echo "scale=6; $avg_latency_ms / 1000" | bc -l)"
    echo "THROUGHPUT_DISPLAY=$throughput_display"
    echo "ERROR_RESILIENCE_OPS_PER_SEC=0"
    echo "AVG_TIME_MICROS=$(echo "scale=0; $avg_latency_ms * 1000" | bc -l)"
}

# Create simple badges for both types
create_simple_badges() {
    echo "Creating simple informational badges..."
    
    # Create combined badge for all benchmarks
    create_badge "JWT Benchmarks" "Updated $TIMESTAMP" "brightgreen" "all-benchmarks.json"
    
    # Create last benchmark run badge with time
    create_badge "Last Benchmark Run" "$TIMESTAMP_WITH_TIME" "blue" "last-run-badge.json"
    
    echo "Simple badges created successfully"
}

# Main processing logic
if [ "$BENCHMARK_TYPE" = "micro" ]; then
    process_micro_benchmarks
elif [ "$BENCHMARK_TYPE" = "integration" ]; then
    process_integration_benchmarks
else
    echo "Error: Invalid benchmark type. Use 'micro' or 'integration'"
    exit 1
fi

# Always create simple badges
create_simple_badges

echo "Badge creation completed successfully"