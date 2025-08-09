#!/bin/bash
# Common metric calculation utilities
# Usage: source lib/metrics-utils.sh

# Convert various units to operations per second
convert_to_ops_per_sec() {
    local value="$1"
    local unit="$2"
    
    if [[ "$unit" == "ops/s" ]]; then
        echo "$value"
    elif [[ "$unit" == "ops/ms" ]]; then
        # Convert ops/ms to ops/s by multiplying by 1000
        echo "scale=2; $value * 1000" | bc -l
    elif [[ "$unit" == "s/op" ]]; then
        if [ "$(echo "$value == 0" | bc -l)" -eq 1 ]; then
            echo "0"
        else
            echo "scale=2; 1 / $value" | bc -l
        fi
    elif [[ "$unit" == "ms/op" ]]; then
        if [ "$(echo "$value == 0" | bc -l)" -eq 1 ]; then
            echo "0"
        else
            echo "scale=2; 1000 / $value" | bc -l
        fi
    elif [[ "$unit" == "us/op" ]]; then
        if [ "$(echo "$value == 0" | bc -l)" -eq 1 ]; then
            echo "0"
        else
            echo "scale=2; 1000000 / $value" | bc -l
        fi
    else
        echo "0"
    fi
}

# Convert various units to milliseconds
convert_to_milliseconds() {
    local value="$1"
    local unit="$2"
    
    if [[ "$unit" == "ms/op" ]]; then
        echo "$value"
    elif [[ "$unit" == "us/op" ]]; then
        echo "scale=3; $value / 1000" | bc -l
    elif [[ "$unit" == "s/op" ]]; then
        echo "scale=3; $value * 1000" | bc -l
    else
        echo "0"
    fi
}

# Calculate weighted performance score
calculate_performance_score() {
    local throughput="$1"
    local latency_ms="$2"
    local error_resilience="$3"
    
    # Convert latency to operations per second equivalent
    local latency_ops_per_sec
    if [ "$(echo "$latency_ms == 0" | bc -l)" -eq 1 ]; then
        latency_ops_per_sec="0"
    else
        latency_ops_per_sec=$(echo "scale=2; 1000 / $latency_ms" | bc -l)
    fi
    
    # Calculate weighted score
    if [ "$error_resilience" != "0" ]; then
        # Enhanced formula: (throughput * 0.57) + (latency * 0.40) + (error_resilience * 0.03)
        echo "scale=2; ($throughput * 0.57) + ($latency_ops_per_sec * 0.40) + ($error_resilience * 0.03)" | bc -l
    else
        # Fallback to original formula: (throughput * 0.6) + (latency * 0.4)
        echo "scale=2; ($throughput * 0.6) + ($latency_ops_per_sec * 0.4)" | bc -l
    fi
}

# Detect benchmark type from JMH result file
detect_benchmark_type() {
    local result_file="$1"
    
    if grep -q "JwtHealthBenchmark\|JwtValidationBenchmark\|JwtEchoBenchmark" "$result_file"; then
        echo "integration"
    elif grep -q "SimpleCoreValidationBenchmark\|SimpleErrorLoadBenchmark" "$result_file"; then
        echo "micro"
    else
        echo "integration"  # Default to integration
    fi
}

# Extract environment information
get_environment_info() {
    local java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 2>/dev/null || echo "unknown")
    local os_name="$(uname -s)"
    local jvm_args_value="default"
    
    # Capture actual JVM arguments if available from environment
    if [ -n "$MAVEN_OPTS" ]; then
        jvm_args_value="$MAVEN_OPTS"
    elif [ -n "$JAVA_OPTS" ]; then
        jvm_args_value="$JAVA_OPTS"
    fi
    
    echo "JAVA_VERSION=$java_version"
    echo "OS_NAME=$os_name"
    echo "JVM_ARGS_VALUE=$jvm_args_value"
}