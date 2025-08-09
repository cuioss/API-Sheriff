#!/bin/bash
# Unified Trend Badge Creation Script
# Handles both micro-benchmarks and integration benchmarks
# Usage: create-unified-trend-badge.sh <benchmark-type> <tracking-file> <output-directory>

set -e

BENCHMARK_TYPE="$1"  # "micro" or "integration"
TRACKING_FILE="$2"
OUTPUT_DIR="$3"

if [ ! -f "$TRACKING_FILE" ]; then
    echo "Error: Tracking file not found: $TRACKING_FILE"
    # Create placeholder badge
    mkdir -p "$OUTPUT_DIR"
    echo "{\"schemaVersion\":1,\"label\":\"Performance Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/trend-badge.json"
    echo "Created placeholder trend badge: no tracking data"
    exit 0
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "Calculating performance trends for $BENCHMARK_TYPE benchmarks..."

# Common trend calculation function
calculate_trend() {
    local scores="$1"
    local score_count="$2"
    
    if [ "$score_count" -ge 2 ]; then
        # Calculate simple trend (percentage change from first to last in the dataset)
        local first_score=$(echo "$scores" | head -1)
        local last_score=$(echo "$scores" | tail -1)
        
        # Handle division by zero
        local percent_change
        if [ "$(echo "$first_score == 0" | bc -l)" -eq 1 ]; then
            if [ "$(echo "$last_score == 0" | bc -l)" -eq 1 ]; then
                percent_change="0"
            else
                percent_change="99999"  # Treat as massive improvement
            fi
        else
            percent_change=$(echo "scale=2; (($last_score - $first_score) / $first_score) * 100" | bc -l)
        fi
        
        # Determine trend direction and color
        local trend_direction="stable"
        local trend_color="lightgrey"
        local trend_symbol="→"
        
        if [ $(echo "$percent_change > 2" | bc -l) -eq 1 ]; then
            trend_direction="improving"
            trend_color="brightgreen"
            trend_symbol="↗"
        elif [ $(echo "$percent_change < -2" | bc -l) -eq 1 ]; then
            trend_direction="declining"
            trend_color="orange"
            trend_symbol="↘"
        fi
        
        local abs_change=$(echo "$percent_change" | sed 's/-//')
        local formatted_change=$(printf "%.1f" $abs_change)
        
        # Create trend badge with benchmark type context
        local label="Performance Trend"
        local badge_file="trend-badge.json"
        if [ "$BENCHMARK_TYPE" = "integration" ]; then
            label="Integration Performance Trend"
            badge_file="integration-trend-badge.json"
        fi
        
        echo "{\"schemaVersion\":1,\"label\":\"$label\",\"message\":\"$trend_symbol $formatted_change% ($trend_direction)\",\"color\":\"$trend_color\"}" > "$OUTPUT_DIR/$badge_file"
        
        echo "Created trend badge: $trend_direction ($formatted_change%)"
    else
        # Not enough data for trend
        local label="Performance Trend"
        local badge_file="trend-badge.json"
        if [ "$BENCHMARK_TYPE" = "integration" ]; then
            label="Integration Performance Trend"
            badge_file="integration-trend-badge.json"
        fi
        
        echo "{\"schemaVersion\":1,\"label\":\"$label\",\"message\":\"→ Insufficient Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/$badge_file"
        echo "Created trend badge: insufficient data"
    fi
}

# Extract performance scores based on benchmark type
if [ "$BENCHMARK_TYPE" = "micro" ]; then
    # Extract scores from micro-benchmark tracking file
    SCORES=$(jq -r '.runs[].performance.score' "$TRACKING_FILE" 2>/dev/null | tail -10)
    SCORE_COUNT=$(echo "$SCORES" | wc -l)
    
    if [ "$SCORE_COUNT" -eq 0 ]; then
        # Try alternative structure
        SCORES=$(jq -r '.runs[].score' "$TRACKING_FILE" 2>/dev/null | tail -10)
        SCORE_COUNT=$(echo "$SCORES" | wc -l)
    fi
    
elif [ "$BENCHMARK_TYPE" = "integration" ]; then
    # Extract scores from integration benchmark tracking file
    SCORES=$(jq -r '.runs[].integration.performanceScore' "$TRACKING_FILE" 2>/dev/null | tail -10)
    SCORE_COUNT=$(echo "$SCORES" | wc -l)
    
    if [ "$SCORE_COUNT" -eq 0 ]; then
        # Try alternative structure
        SCORES=$(jq -r '.runs[].performance.score' "$TRACKING_FILE" 2>/dev/null | tail -10)
        SCORE_COUNT=$(echo "$SCORES" | wc -l)
    fi
    
else
    echo "Error: Invalid benchmark type. Use 'micro' or 'integration'"
    exit 1
fi

# Validate extracted scores
if [ "$SCORE_COUNT" -eq 0 ] || [ -z "$SCORES" ]; then
    echo "Warning: No performance scores found in tracking file"
    label="Performance Trend"
    badge_file="trend-badge.json"
    if [ "$BENCHMARK_TYPE" = "integration" ]; then
        label="Integration Performance Trend"
        badge_file="integration-trend-badge.json"
    fi
    echo "{\"schemaVersion\":1,\"label\":\"$label\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/$badge_file"
    echo "Created placeholder trend badge: no performance data"
    exit 0
fi

# Calculate and create trend badge
calculate_trend "$SCORES" "$SCORE_COUNT"

echo "Trend badge creation completed successfully"