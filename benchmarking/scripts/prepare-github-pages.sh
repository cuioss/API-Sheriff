#!/bin/bash
# Prepare GitHub Pages structure and copy benchmark results
# Usage: prepare-github-pages.sh <benchmark-results-dir> <templates-dir> <output-dir>

set -e

BENCHMARK_RESULTS_DIR="$1"
TEMPLATES_DIR="$2"
OUTPUT_DIR="$3"

echo "Preparing unified GitHub Pages structure..."

# Create directories
mkdir -p "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/data"
mkdir -p "$OUTPUT_DIR/resources"
mkdir -p "$OUTPUT_DIR/badges"

# Copy all benchmark results to data directory
if [ -d "$BENCHMARK_RESULTS_DIR" ]; then
  cp -r "$BENCHMARK_RESULTS_DIR"/* "$OUTPUT_DIR/data"/
  echo "Copied benchmark results to data directory"
else
  echo "Warning: Benchmark results directory not found: $BENCHMARK_RESULTS_DIR"
fi

# Find and copy the main JMH result file
if [ -f "jmh-result.json" ]; then
  echo "Using jmh-result.json from project root"
  cp jmh-result.json "$OUTPUT_DIR/data/jmh-result.json"
else
  # Find the result file in benchmark-results directory
  echo "Looking for JMH result files in benchmark-results directory"
  if [ -d "$BENCHMARK_RESULTS_DIR" ]; then
    find "$BENCHMARK_RESULTS_DIR" -name "jmh-result*.json" -type f -exec cp {} "$OUTPUT_DIR/data/jmh-result.json" \; 2>/dev/null || true
    find "$BENCHMARK_RESULTS_DIR" -name "micro-benchmark-result.json" -type f -exec cp {} "$OUTPUT_DIR/data/jmh-result.json" \; 2>/dev/null || true
  fi
fi

# Verify benchmark result file exists
if [ ! -f "$OUTPUT_DIR/data/jmh-result.json" ]; then
  echo "ERROR: No benchmark result file found!"
  exit 1
fi

# Copy all page templates to root directory using original names
cp "$TEMPLATES_DIR/index-visualizer.html" "$OUTPUT_DIR/index-visualizer.html"
echo "Copied micro benchmarks template"

if [ -f "$TEMPLATES_DIR/integration-index.html" ]; then
  cp "$TEMPLATES_DIR/integration-index.html" "$OUTPUT_DIR/integration-index.html"
  echo "Copied integration benchmarks template"
fi

if [ -f "$TEMPLATES_DIR/step-metrics-visualizer.html" ]; then
  cp "$TEMPLATES_DIR/step-metrics-visualizer.html" "$OUTPUT_DIR/step-metrics-visualizer.html"
  echo "Copied step metrics visualizer template"
fi

if [ -f "$TEMPLATES_DIR/performance-trends.html" ]; then
  cp "$TEMPLATES_DIR/performance-trends.html" "$OUTPUT_DIR/performance-trends.html"
  echo "Copied performance trends template"
fi

if [ -f "$TEMPLATES_DIR/integration-performance-trends.html" ]; then
  cp "$TEMPLATES_DIR/integration-performance-trends.html" "$OUTPUT_DIR/integration-performance-trends.html"
  echo "Copied integration performance trends template"
fi

# Copy shared resources
if [ -d "$TEMPLATES_DIR/resources" ]; then
  cp -r "$TEMPLATES_DIR/resources"/* "$OUTPUT_DIR/resources"/
  echo "Copied shared resources (CSS, JS)"
fi

# Ensure jwt-validation-metrics.json is available for step metrics
if [ -f "$TEMPLATES_DIR/data/jwt-validation-metrics.json" ]; then
  cp "$TEMPLATES_DIR/data/jwt-validation-metrics.json" "$OUTPUT_DIR/data/"
  echo "Copied JWT validation step metrics"
fi

echo "✅ Unified GitHub Pages structure prepared successfully"
echo "📁 All pages are now in same directory with shared navigation"
echo "📊 Data files are in data/ subdirectory"
echo "🎨 Shared resources are in resources/ subdirectory"