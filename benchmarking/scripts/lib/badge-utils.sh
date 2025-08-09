#!/bin/bash
# Common badge creation utilities
# Usage: source lib/badge-utils.sh

# Common badge creation function
create_badge() {
    local label="$1"
    local message="$2"
    local color="$3"
    local filename="$4"
    local output_dir="${5:-$OUTPUT_DIR}"
    
    echo "{\"schemaVersion\":1,\"label\":\"$label\",\"message\":\"$message\",\"color\":\"$color\"}" > "$output_dir/$filename"
}

# Format throughput for display
format_throughput_display() {
    local throughput="$1"
    
    if [ $(echo "$throughput >= 1000" | bc -l) -eq 1 ]; then
        throughput_k=$(echo "scale=1; $throughput / 1000" | bc -l)
        echo "${throughput_k}k"
    else
        printf "%.0f" "$throughput"
    fi
}

# Determine badge color based on score
get_performance_badge_color() {
    local score="$1"
    
    if (( $(echo "$score >= 50" | bc -l) )); then
        echo "green"
    elif (( $(echo "$score >= 25" | bc -l) )); then
        echo "yellow"
    elif (( $(echo "$score >= 10" | bc -l) )); then
        echo "orange"
    else
        echo "red"
    fi
}

# Get throughput badge color
get_throughput_badge_color() {
    local throughput="$1"
    
    if (( $(echo "$throughput >= 1000" | bc -l) )); then
        echo "green"
    elif (( $(echo "$throughput >= 500" | bc -l) )); then
        echo "yellow"
    elif (( $(echo "$throughput >= 100" | bc -l) )); then
        echo "orange"
    else
        echo "red"
    fi
}

# Get latency badge color (lower is better)
get_latency_badge_color() {
    local latency_ms="$1"
    
    if (( $(echo "$latency_ms <= 10" | bc -l) )); then
        echo "green"
    elif (( $(echo "$latency_ms <= 25" | bc -l) )); then
        echo "yellow"
    elif (( $(echo "$latency_ms <= 50" | bc -l) )); then
        echo "orange"
    else
        echo "red"
    fi
}