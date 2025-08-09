#!/bin/bash
# Update consolidated integration performance tracking and calculate trends
# Usage: update-integration-performance-trends.sh <templates-dir> <output-dir> <commit-hash> <score> <throughput> <latency> <resilience>

set -e

TEMPLATES_DIR="$1"
OUTPUT_DIR="$2"
COMMIT_HASH="$3"
CURRENT_SCORE="$4"
CURRENT_THROUGHPUT="$5"
CURRENT_LATENCY="$6"
CURRENT_RESILIENCE="$7"

echo "Updating integration performance tracking..."

# Ensure output directories exist
mkdir -p "$OUTPUT_DIR/data"
mkdir -p "$OUTPUT_DIR/badges"

# Use separate tracking file for integration benchmarks
TRACKING_FILE="$OUTPUT_DIR/data/integration-performance-tracking.json"
# Try to download from the deployed GitHub Pages URL
GITHUB_PAGES_URL="https://cuioss.github.io/cui-jwt/benchmarks/data/integration-performance-tracking.json"

# Check if we already have a local tracking file with data
if [ -f "$TRACKING_FILE" ]; then
    LOCAL_RUN_COUNT=$(jq '.runs | length' "$TRACKING_FILE" 2>/dev/null || echo "0")
    if [ "$LOCAL_RUN_COUNT" -gt "0" ]; then
        echo "Using existing local tracking file with $LOCAL_RUN_COUNT runs"
    else
        # Local file exists but is empty, try to download from GitHub Pages
        echo "Local tracking file is empty, checking GitHub Pages..."
        TEMP_FILE=$(mktemp)
        if curl -f -s "$GITHUB_PAGES_URL" -o "$TEMP_FILE" 2>/dev/null; then
            REMOTE_RUN_COUNT=$(jq '.runs | length' "$TEMP_FILE" 2>/dev/null || echo "0")
            if [ "$REMOTE_RUN_COUNT" -gt "0" ]; then
                echo "Downloaded existing performance tracking file with $REMOTE_RUN_COUNT runs"
                mv "$TEMP_FILE" "$TRACKING_FILE"
            else
                echo "Remote tracking file is also empty, starting fresh"
                rm -f "$TEMP_FILE"
                echo '{"runs":[]}' > "$TRACKING_FILE"
            fi
        else
            echo "No remote tracking file found, starting fresh"
            rm -f "$TEMP_FILE"
            echo '{"runs":[]}' > "$TRACKING_FILE"
        fi
    fi
else
    # No local file, try to download from GitHub Pages
    echo "No local tracking file, downloading from GitHub Pages..."
    if curl -f -s "$GITHUB_PAGES_URL" -o "$TRACKING_FILE" 2>/dev/null; then
        REMOTE_RUN_COUNT=$(jq '.runs | length' "$TRACKING_FILE" 2>/dev/null || echo "0")
        echo "Downloaded existing performance tracking file with $REMOTE_RUN_COUNT runs"
        if [ "$REMOTE_RUN_COUNT" -eq "0" ]; then
            echo "Warning: Remote tracking file is empty"
        fi
    else
        echo "No remote tracking file found, starting fresh"
        echo '{"runs":[]}' > "$TRACKING_FILE"
    fi
fi

# Convert formatted values to raw numbers
# Remove 'k' suffix and multiply by 1000 if present
if [[ "$CURRENT_THROUGHPUT" == *"k" ]]; then
    CURRENT_THROUGHPUT=$(echo "$CURRENT_THROUGHPUT" | sed 's/k$//' | awk '{print $1 * 1000}')
fi
if [[ "$CURRENT_RESILIENCE" == *"k" ]]; then
    CURRENT_RESILIENCE=$(echo "$CURRENT_RESILIENCE" | sed 's/k$//' | awk '{print $1 * 1000}')
fi

# Ensure values are numeric (default to 0 if not)
CURRENT_SCORE=$(echo "$CURRENT_SCORE" | grep -o '[0-9.]*' | head -1 || echo "0")
CURRENT_THROUGHPUT=$(echo "$CURRENT_THROUGHPUT" | grep -o '[0-9.]*' | head -1 || echo "0")
CURRENT_LATENCY=$(echo "$CURRENT_LATENCY" | grep -o '[0-9.]*' | head -1 || echo "0")
CURRENT_RESILIENCE=$(echo "$CURRENT_RESILIENCE" | grep -o '[0-9.]*' | head -1 || echo "0")

# Add current run to tracking data
CURRENT_RUN=$(cat <<EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "commit": "$COMMIT_HASH",
  "performance": {
    "score": $CURRENT_SCORE,
    "throughput": {"value": $CURRENT_THROUGHPUT, "unit": "ops/s"},
    "averageTime": {"value": $CURRENT_LATENCY, "unit": "s"},
    "errorResilience": {"value": $CURRENT_RESILIENCE, "unit": "ops/s"}
  }
}
EOF
)

# Validate JSON structure before updating
if jq empty "$TRACKING_FILE" 2>/dev/null; then
  # Add new run and keep only last 10 runs
  jq --argjson newrun "$CURRENT_RUN" '.runs += [$newrun] | .runs = (.runs | sort_by(.timestamp) | .[-10:])' "$TRACKING_FILE" > "$TRACKING_FILE.tmp" && mv "$TRACKING_FILE.tmp" "$TRACKING_FILE"
else
  echo "Warning: Invalid JSON in tracking file, resetting to default structure."
  echo '{"runs":[]}' > "$TRACKING_FILE"
  jq --argjson newrun "$CURRENT_RUN" '.runs += [$newrun] | .runs = (.runs | sort_by(.timestamp) | .[-10:])' "$TRACKING_FILE" > "$TRACKING_FILE.tmp" && mv "$TRACKING_FILE.tmp" "$TRACKING_FILE"
fi

# Log the final state for debugging
FINAL_RUN_COUNT=$(jq '.runs | length' "$TRACKING_FILE" 2>/dev/null || echo "0")
echo "Performance tracking file now contains $FINAL_RUN_COUNT runs"

# Verify the file is actually written and contains data
if [ -f "$TRACKING_FILE" ]; then
    echo "✅ Performance tracking file exists at: $TRACKING_FILE"
    FILE_SIZE=$(wc -c < "$TRACKING_FILE")
    echo "   File size: $FILE_SIZE bytes"
    if [ "$FINAL_RUN_COUNT" -eq "0" ]; then
        echo "⚠️  WARNING: Tracking file exists but contains no runs!"
    fi
else
    echo "❌ ERROR: Performance tracking file was not created!"
fi

# Calculate trends and create trend badge using unified script for integration
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
bash "$SCRIPT_DIR/create-unified-trend-badge.sh" integration "$TRACKING_FILE" "$OUTPUT_DIR/badges"

# Integration performance trends template is copied by prepare-github-pages.sh script

echo "Integration performance tracking updated successfully"
