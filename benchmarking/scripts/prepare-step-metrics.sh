#!/bin/bash
# Prepare step metrics visualization (integrated into prepare-github-pages.sh)
# Usage: prepare-step-metrics.sh <benchmark-results-dir> <templates-dir> <output-dir>

set -e

BENCHMARK_RESULTS_DIR="$1"
TEMPLATES_DIR="$2"
OUTPUT_DIR="$3"

echo "Preparing step metrics visualization..."

# Create data directory
mkdir -p "$OUTPUT_DIR/data"

# Copy step metrics data if it exists in benchmark results
if [ -f "$BENCHMARK_RESULTS_DIR/jwt-validation-metrics.json" ]; then
  cp "$BENCHMARK_RESULTS_DIR/jwt-validation-metrics.json" "$OUTPUT_DIR/data/"
  echo "Copied jwt-validation-metrics.json to data directory"
else
  echo "Warning: jwt-validation-metrics.json not found in $BENCHMARK_RESULTS_DIR"
fi

# Copy from templates data directory if available
if [ -f "$TEMPLATES_DIR/data/jwt-validation-metrics.json" ]; then
  cp "$TEMPLATES_DIR/data/jwt-validation-metrics.json" "$OUTPUT_DIR/data/"
  echo "Copied jwt-validation-metrics.json from templates data directory"
fi

echo "Step metrics data prepared successfully"