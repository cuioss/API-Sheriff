#!/bin/bash
# Process integration JMH benchmark results
# Usage: process-integration-jmh-benchmarks.sh <integration-benchmark-result.json> <output-dir> <commit-hash> <timestamp>

set -e

INTEGRATION_JMH_RESULTS="$1"
OUTPUT_DIR="$2"
COMMIT_HASH="$3"
TIMESTAMP="$4"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATES_DIR="$(dirname "$SCRIPT_DIR")/doc/templates"

echo "🔗 Processing integration JMH benchmark results..."
echo "📊 Integration JMH Results: $INTEGRATION_JMH_RESULTS"
echo "📦 Output Directory: $OUTPUT_DIR"

# Validate input file
if [ ! -f "$INTEGRATION_JMH_RESULTS" ]; then
    echo "❌ Error: Integration JMH results not found: $INTEGRATION_JMH_RESULTS"
    exit 1
fi

# Ensure output directories exist
mkdir -p "$OUTPUT_DIR/data"
mkdir -p "$OUTPUT_DIR/badges"

# Copy the integration benchmark results to the data directory
echo "📋 Copying integration benchmark results..."
cp "$INTEGRATION_JMH_RESULTS" "$OUTPUT_DIR/data/integration-benchmark-result.json"

# Also copy as jmh-result.json for fallback compatibility
cp "$INTEGRATION_JMH_RESULTS" "$OUTPUT_DIR/data/jmh-result.json"

# Copy the integration visualizer template
echo "📄 Copying integration visualizer template..."
if [ -f "$TEMPLATES_DIR/integration-benchmark-visualizer.html" ]; then
    cp "$TEMPLATES_DIR/integration-benchmark-visualizer.html" "$OUTPUT_DIR/integration-benchmark-visualizer.html"
else
    echo "⚠️  Warning: Integration visualizer template not found, using fallback"
    # Create a minimal fallback if template doesn't exist
    cat > "$OUTPUT_DIR/integration-benchmark-visualizer.html" << 'EOF'
<!DOCTYPE html>
<html>
<head>
    <title>Integration Benchmark Results</title>
    <meta http-equiv="refresh" content="0; url=index-visualizer.html">
</head>
<body>
    <p>Redirecting to benchmark results...</p>
</body>
</html>
EOF
fi

# Create performance tracking data for integration benchmarks
echo "📊 Creating integration performance tracking..."
TRACKING_OUTPUT=$(bash "$SCRIPT_DIR/create-performance-tracking.sh" "$OUTPUT_DIR/data/integration-benchmark-result.json" "$TEMPLATES_DIR" "$OUTPUT_DIR" "$COMMIT_HASH" 2>&1)
echo "$TRACKING_OUTPUT"

# Extract metrics from tracking script output
PERF_SCORE=$(echo "$TRACKING_OUTPUT" | grep "PERF_SCORE=" | cut -d'=' -f2 || echo "0")
PERF_THROUGHPUT=$(echo "$TRACKING_OUTPUT" | grep "PERF_THROUGHPUT=" | cut -d'=' -f2 || echo "0")
PERF_LATENCY=$(echo "$TRACKING_OUTPUT" | grep "PERF_LATENCY=" | cut -d'=' -f2 || echo "0")
PERF_RESILIENCE=$(echo "$TRACKING_OUTPUT" | grep "PERF_RESILIENCE=" | cut -d'=' -f2 || echo "0")

echo "🎯 Extracted Integration Performance Metrics:"
echo "  Score: $PERF_SCORE"
echo "  Throughput: $PERF_THROUGHPUT"
echo "  Latency: $PERF_LATENCY"
echo "  Resilience: $PERF_RESILIENCE"

# Create integration performance badge with actual score and details
if [ -n "$PERF_SCORE" ] && [ "$PERF_SCORE" != "0" ] && [ "$PERF_SCORE" != "" ]; then
    # Format latency for display (convert to ms if needed)
    LATENCY_MS=$(echo "$PERF_LATENCY * 1000" | bc -l | xargs printf "%.0f")
    
    # Format the badge message with score, throughput, and latency (like micro benchmarks)
    BADGE_MESSAGE="${PERF_SCORE} (${PERF_THROUGHPUT} ops/s, ${LATENCY_MS}ms)"
    
    # Determine color based on score
    SCORE_VALUE=$(echo "$PERF_SCORE" | sed 's/[^0-9.]//g')
    if (( $(echo "$SCORE_VALUE >= 5000" | bc -l) )); then
        BADGE_COLOR="brightgreen"
    elif (( $(echo "$SCORE_VALUE >= 1000" | bc -l) )); then
        BADGE_COLOR="green"
    elif (( $(echo "$SCORE_VALUE >= 500" | bc -l) )); then
        BADGE_COLOR="yellow"
    else
        BADGE_COLOR="orange"
    fi
    echo "{\"schemaVersion\":1,\"label\":\"Integration Performance\",\"message\":\"$BADGE_MESSAGE\",\"color\":\"$BADGE_COLOR\"}" > "$OUTPUT_DIR/badges/integration-performance-badge.json"
else
    echo "{\"schemaVersion\":1,\"label\":\"Integration Performance\",\"message\":\"No Data\",\"color\":\"red\"}" > "$OUTPUT_DIR/badges/integration-performance-badge.json"
fi

# Update integration trends using unified tracking system
if [ -n "$PERF_SCORE" ] && [ "$PERF_SCORE" != "0" ] && [ "$PERF_SCORE" != "" ]; then
    echo "📈 Updating integration performance trends..."
    
    # Use unified tracking system for integration benchmarks
    # Create a separate tracking file for integration benchmarks
    INTEGRATION_TRACKING_DIR="$OUTPUT_DIR"
    if bash "$SCRIPT_DIR/update-integration-performance-trends.sh" "$TEMPLATES_DIR" "$INTEGRATION_TRACKING_DIR" "$COMMIT_HASH" "$PERF_SCORE" "$PERF_THROUGHPUT" "$PERF_LATENCY" "$PERF_RESILIENCE"; then
        echo "✅ Integration performance trends updated successfully"
    else
        echo "⚠️  Failed to update integration performance trends, using fallback..."
        # Fallback to old method
        TRENDS_FILE="$OUTPUT_DIR/data/integration-trends.json"
        if [ -f "$TRENDS_FILE" ]; then
            # Read existing trends
            PREV_SCORE=$(jq -r '.latest_score // "0"' "$TRENDS_FILE" 2>/dev/null || echo "0")
            PREV_SCORE_VALUE=$(echo "$PREV_SCORE" | sed 's/[^0-9.]//g')
            SCORE_VALUE=$(echo "$PERF_SCORE" | sed 's/[^0-9.]//g')
            
            # Calculate trend
            if (( $(echo "$SCORE_VALUE > $PREV_SCORE_VALUE + 1" | bc -l) )); then
                TREND_SYMBOL="↗"
                TREND_COLOR="green"
            elif (( $(echo "$SCORE_VALUE < $PREV_SCORE_VALUE - 1" | bc -l) )); then
                TREND_SYMBOL="↘"
                TREND_COLOR="orange"
            else
                TREND_SYMBOL="→"
                TREND_COLOR="blue"
            fi
        else
            TREND_SYMBOL="→"
            TREND_COLOR="blue"
        fi
        
        # Update trends file
        cat > "$TRENDS_FILE" << EOF
{
  "latest_score": "$PERF_SCORE",
  "latest_throughput": "$PERF_THROUGHPUT",
  "latest_latency": "$PERF_LATENCY",
  "latest_resilience": "$PERF_RESILIENCE",
  "updated": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF
        
        # Create trend badge
        TREND_MESSAGE="${TREND_SYMBOL} ${SCORE_VALUE}%"
        echo "{\"schemaVersion\":1,\"label\":\"Integration Trend\",\"message\":\"$TREND_MESSAGE\",\"color\":\"$TREND_COLOR\"}" > "$OUTPUT_DIR/badges/integration-trend-badge.json"
    fi
else
    echo "{\"schemaVersion\":1,\"label\":\"Integration Trend\",\"message\":\"→ No Data\",\"color\":\"lightgrey\"}" > "$OUTPUT_DIR/badges/integration-trend-badge.json"
fi

# Create metadata file
cat > "$OUTPUT_DIR/data/integration-metadata.json" << EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "commit": "$COMMIT_HASH",
  "generated_at": "$TIMESTAMP",
  "metrics": {
    "performance_score": "$PERF_SCORE",
    "throughput": "$PERF_THROUGHPUT",
    "latency": "$PERF_LATENCY",
    "resilience": "$PERF_RESILIENCE"
  }
}
EOF

echo "✅ Integration JMH benchmarks processed successfully"
echo "   - Performance Score: $PERF_SCORE"
echo "   - Throughput: $PERF_THROUGHPUT"
echo "   - Latency: $PERF_LATENCY"