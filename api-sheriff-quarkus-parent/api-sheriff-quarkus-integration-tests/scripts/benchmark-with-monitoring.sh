#!/bin/bash
# Comprehensive Benchmark with JFR and Container Monitoring
# Integrates JFR profiling, container resource monitoring, and system metrics

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/api-sheriff-quarkus-parent/quarkus-integration-jmh/target/monitoring-results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

echo "🔍 API Sheriff Validation Benchmark with Comprehensive Monitoring"
echo "========================================================="
echo "📁 Project root: $PROJECT_ROOT"
echo "📊 Results: $RESULTS_DIR"
echo "⏰ Timestamp: $TIMESTAMP"
echo ""

# Create results directory
mkdir -p "$RESULTS_DIR"

# Check prerequisites
echo "🔧 Checking prerequisites..."
if ! command -v maven >/dev/null 2>&1 && ! command -v mvn >/dev/null 2>&1; then
    echo "❌ Maven not found"
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker not running"
    exit 1
fi

MAVEN_CMD="mvn"
if command -v ./mvnw >/dev/null 2>&1; then
    MAVEN_CMD="./mvnw"
fi

echo "✅ Using Maven: $MAVEN_CMD"
echo "✅ Docker available"
echo ""

# Output files
SYSTEM_LOG="$RESULTS_DIR/system-stats-$TIMESTAMP.csv"
CONTAINER_LOG="$RESULTS_DIR/container-stats-$TIMESTAMP.csv"
BENCHMARK_LOG="$RESULTS_DIR/benchmark-output-$TIMESTAMP.log"
JFR_FILE="$RESULTS_DIR/api-sheriff-validation-$TIMESTAMP.jfr"
FINAL_REPORT="$RESULTS_DIR/comprehensive-report-$TIMESTAMP.adoc"

# System monitoring function
monitor_system() {
    echo "timestamp,cpu_usage_percent,memory_usage_percent,load_average,cpu_count" > "$SYSTEM_LOG"
    
    while kill -0 $BENCHMARK_PID 2>/dev/null; do
        local TIMESTAMP=$(date +%Y-%m-%d\ %H:%M:%S)
        if [[ "$OSTYPE" == "darwin"* ]]; then
            # macOS
            local CPU_COUNT=$(sysctl -n hw.ncpu)
            local CPU_USAGE=$(ps aux | awk '{sum+=$3} END {printf "%.1f", sum}')
            local CPU_NORMALIZED=$(echo "scale=1; $CPU_USAGE / $CPU_COUNT" | bc 2>/dev/null || echo "0")
            local MEM_USAGE=$(vm_stat | perl -ne '/page size of (\d+)/ and $size=$1; /Pages\s+([^:]+):\s+(\d+)/ and $mem{$1}=$2; END { $total = $mem{"active"} + $mem{"inactive"} + $mem{"wired down"} + $mem{"compressed"}; $used = $mem{"active"} + $mem{"wired down"}; printf "%.1f", $used/$total * 100 if $total > 0 }' 2>/dev/null || echo "0")
            local LOAD_AVG=$(sysctl -n vm.loadavg | awk '{print $2}')
        else
            # Linux
            local CPU_COUNT=$(nproc)
            local CPU_USAGE=$(top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk '{print 100 - $1}')
            local CPU_NORMALIZED="$CPU_USAGE"
            local MEM_USAGE=$(free | grep Mem | awk '{printf "%.1f", ($3/$2) * 100.0}')
            local LOAD_AVG=$(uptime | awk -F'load average:' '{print $2}' | awk -F',' '{print $1}' | xargs)
        fi
        
        echo "$TIMESTAMP,$CPU_NORMALIZED,$MEM_USAGE,$LOAD_AVG,$CPU_COUNT" >> "$SYSTEM_LOG"
        sleep 3
    done
}

# Container monitoring function
monitor_containers() {
    echo "timestamp,container,cpu_percent,mem_usage_mb,mem_limit_mb,mem_percent,net_io,block_io" > "$CONTAINER_LOG"
    
    while kill -0 $BENCHMARK_PID 2>/dev/null; do
        local TIMESTAMP=$(date +%Y-%m-%d\ %H:%M:%S)
        
        # Find JWT and Keycloak containers
        local CONTAINERS=$(docker ps --format "{{.Names}}" | grep -E "(jwt|keycloak)" || echo "")
        
        for CONTAINER in $CONTAINERS; do
            if [[ -n "$CONTAINER" ]]; then
                # Get container stats
                local STATS=$(docker stats --no-stream --format "{{.CPUPerc}},{{.MemUsage}},{{.NetIO}},{{.BlockIO}}" "$CONTAINER" 2>/dev/null || echo "")
                
                if [[ -n "$STATS" ]]; then
                    local CPU_PCT=$(echo "$STATS" | cut -d',' -f1 | sed 's/%//')
                    local MEM_USAGE=$(echo "$STATS" | cut -d',' -f2 | awk '{print $1}' | sed 's/MiB//' | sed 's/GiB/*1024/' | bc 2>/dev/null || echo "0")
                    local MEM_LIMIT=$(echo "$STATS" | cut -d',' -f2 | awk '{print $3}' | sed 's/MiB//' | sed 's/GiB/*1024/' | bc 2>/dev/null || echo "1")
                    local NET_IO=$(echo "$STATS" | cut -d',' -f3)
                    local BLOCK_IO=$(echo "$STATS" | cut -d',' -f4)
                    
                    # Calculate memory percentage safely
                    local MEM_PCT=0
                    if [[ "$MEM_LIMIT" != "0" ]] && [[ "$MEM_LIMIT" != "" ]]; then
                        MEM_PCT=$(echo "scale=1; $MEM_USAGE / $MEM_LIMIT * 100" | bc 2>/dev/null || echo "0")
                    fi
                    
                    echo "$TIMESTAMP,$CONTAINER,$CPU_PCT,$MEM_USAGE,$MEM_LIMIT,$MEM_PCT,$NET_IO,$BLOCK_IO" >> "$CONTAINER_LOG"
                fi
            fi
        done
        
        sleep 2
    done
}

# Live dashboard function
show_dashboard() {
    while kill -0 $BENCHMARK_PID 2>/dev/null; do
        clear
        echo "🔄 API Sheriff Validation Benchmark - Live Monitoring Dashboard"
        echo "======================================================="
        echo "Duration: $(( $(date +%s) - START_TIME ))s | Target: 120s minimum"
        echo ""
        
        # System stats
        if [[ -f "$SYSTEM_LOG" ]] && [[ $(wc -l < "$SYSTEM_LOG") -gt 1 ]]; then
            local LATEST_SYSTEM=$(tail -1 "$SYSTEM_LOG")
            local CPU_USE=$(echo "$LATEST_SYSTEM" | cut -d',' -f2)
            local MEM_USE=$(echo "$LATEST_SYSTEM" | cut -d',' -f3)
            local LOAD=$(echo "$LATEST_SYSTEM" | cut -d',' -f4)
            
            echo "📊 System Resources:"
            echo "   CPU Usage: ${CPU_USE}% (Target: 90%)"
            echo "   Memory Usage: ${MEM_USE}% (Target: 90%)"
            echo "   Load Average: $LOAD"
            echo ""
        fi
        
        # Container stats
        if [[ -f "$CONTAINER_LOG" ]] && [[ $(wc -l < "$CONTAINER_LOG") -gt 1 ]]; then
            echo "🐳 Container Resources:"
            # Show latest stats for each container
            local CONTAINERS=$(tail -10 "$CONTAINER_LOG" | cut -d',' -f2 | sort -u)
            for CONTAINER in $CONTAINERS; do
                local LATEST=$(grep "$CONTAINER" "$CONTAINER_LOG" | tail -1)
                if [[ -n "$LATEST" ]]; then
                    local CPU=$(echo "$LATEST" | cut -d',' -f3)
                    local MEM_MB=$(echo "$LATEST" | cut -d',' -f4)
                    local MEM_PCT=$(echo "$LATEST" | cut -d',' -f6)
                    echo "   $CONTAINER: CPU ${CPU}%, Memory ${MEM_MB}MB (${MEM_PCT}%)"
                fi
            done
            echo ""
        fi
        
        # Benchmark progress
        if [[ -f "$BENCHMARK_LOG" ]]; then
            local OPS_COUNT=$(grep -c "ops/s" "$BENCHMARK_LOG" 2>/dev/null || echo "0")
            local LATEST_OPS=$(grep "ops/s" "$BENCHMARK_LOG" 2>/dev/null | tail -1 || echo "Waiting for benchmark...")
            echo "📈 Benchmark Progress:"
            echo "   Measurements: $OPS_COUNT"
            echo "   Latest: $LATEST_OPS"
            echo ""
        fi
        
        # JFR status
        if [[ -f "$JFR_FILE" ]]; then
            local JFR_SIZE=$(du -h "$JFR_FILE" 2>/dev/null | cut -f1 || echo "0")
            echo "📊 JFR Profiling: Active (${JFR_SIZE})"
        else
            echo "📊 JFR Profiling: Waiting for containers..."
        fi
        
        echo ""
        echo "Press Ctrl+C to stop monitoring after 2+ minutes"
        
        sleep 5
    done
}

# Cleanup function
cleanup() {
    echo ""
    echo "🛑 Stopping monitoring and generating report..."
    
    # Stop background processes
    [[ -n "$SYSTEM_MONITOR_PID" ]] && kill $SYSTEM_MONITOR_PID 2>/dev/null || true
    [[ -n "$CONTAINER_MONITOR_PID" ]] && kill $CONTAINER_MONITOR_PID 2>/dev/null || true
    [[ -n "$BENCHMARK_PID" ]] && kill $BENCHMARK_PID 2>/dev/null || true
    
    # Generate comprehensive report
    generate_report
    
    echo "✅ Monitoring completed!"
    exit 0
}

# Report generation function
generate_report() {
    echo "📄 Generating comprehensive report..."
    
    cat > "$FINAL_REPORT" << EOF
= API Sheriff Validation Benchmark Report - $TIMESTAMP
:toc: left
:toclevels: 3

== Executive Summary

Comprehensive benchmark with JFR profiling and resource monitoring.

Duration: $(( $(date +%s) - START_TIME )) seconds
Target: 120+ seconds for reliable measurements

=== Baseline Configuration
* JMH Threads: 200 (increased from 50 to achieve target utilization)
* Container Memory Limit: 64MB (reduced from 256MB for 90% utilization)
* Virtual Threads: Enabled with @RunOnVirtualThread
* Native Image: GraalVM/Mandrel with -O2 optimization level
* Build Time: ~75 seconds

=== Key Measurements
* Performance Target: >200 ops/s baseline
* CPU Utilization Target: 90% for JWT container
* Memory Utilization Target: 90% for JWT container
* Load Configuration: 200 JMH threads, 5 iterations, 5 forks

EOF

    # System resource analysis
    if [[ -f "$SYSTEM_LOG" ]] && [[ $(wc -l < "$SYSTEM_LOG") -gt 1 ]]; then
        local AVG_CPU=$(awk -F',' 'NR>1 {sum+=$2; count++} END {if(count>0) printf "%.1f", sum/count; else print "0"}' "$SYSTEM_LOG")
        local MAX_CPU=$(awk -F',' 'NR>1 {if($2>max) max=$2} END {printf "%.1f", max}' "$SYSTEM_LOG")
        local AVG_MEM=$(awk -F',' 'NR>1 {sum+=$3; count++} END {if(count>0) printf "%.1f", sum/count; else print "0"}' "$SYSTEM_LOG")
        local MAX_MEM=$(awk -F',' 'NR>1 {if($3>max) max=$3} END {printf "%.1f", max}' "$SYSTEM_LOG")
        
        cat >> "$FINAL_REPORT" << EOF
== System Resource Utilization

[cols="2,1,1,1,1"]
|===
|Metric |Average |Maximum |Target |Status

|CPU Usage
|${AVG_CPU}%
|${MAX_CPU}%
|90%
|$(if (( $(echo "$AVG_CPU < 90" | bc -l 2>/dev/null || echo "1") )); then echo "❌ Under 90%"; else echo "✅ Target met"; fi)

|Memory Usage
|${AVG_MEM}%
|${MAX_MEM}%
|90%
|$(if (( $(echo "$AVG_MEM < 90" | bc -l 2>/dev/null || echo "1") )); then echo "❌ Under 90%"; else echo "✅ Target met"; fi)
|===

EOF
    fi

    # Container resource analysis
    if [[ -f "$CONTAINER_LOG" ]] && [[ $(wc -l < "$CONTAINER_LOG") -gt 1 ]]; then
        echo "== Container Resource Utilization" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        
        local CONTAINERS=$(cut -d',' -f2 "$CONTAINER_LOG" | sort -u | grep -v "container")
        for CONTAINER in $CONTAINERS; do
            local AVG_CPU=$(grep "$CONTAINER" "$CONTAINER_LOG" | awk -F',' '{sum+=$3; count++} END {if(count>0) printf "%.1f", sum/count; else print "0"}')
            local MAX_CPU=$(grep "$CONTAINER" "$CONTAINER_LOG" | awk -F',' 'BEGIN{max=0} {if($3>max) max=$3} END {printf "%.1f", max}')
            local AVG_MEM=$(grep "$CONTAINER" "$CONTAINER_LOG" | awk -F',' '{sum+=$6; count++} END {if(count>0) printf "%.1f", sum/count; else print "0"}')
            
            cat >> "$FINAL_REPORT" << EOF
=== $CONTAINER

* CPU: Average ${AVG_CPU}%, Maximum ${MAX_CPU}%
* Memory: Average ${AVG_MEM}%
* Status: $(if (( $(echo "$AVG_CPU < 90" | bc -l 2>/dev/null || echo "1") )); then echo "❌ CPU under 90%"; else echo "✅ CPU target met"; fi)

EOF
        done
    fi

    # Benchmark results
    if [[ -f "$BENCHMARK_LOG" ]]; then
        echo "== Benchmark Performance" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "Latest throughput measurements:" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "----" >> "$FINAL_REPORT"
        grep "ops/s" "$BENCHMARK_LOG" | tail -5 >> "$FINAL_REPORT" 2>/dev/null || echo "No performance data available" >> "$FINAL_REPORT"
        echo "----" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
    fi

    # JFR analysis
    if [[ -f "$JFR_FILE" ]]; then
        echo "== JFR Profiling Results" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "JFR file generated: $(basename "$JFR_FILE")" >> "$FINAL_REPORT"
        echo "Size: $(du -h "$JFR_FILE" | cut -f1)" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "Analysis commands:" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "----" >> "$FINAL_REPORT"
        echo "# Summary" >> "$FINAL_REPORT"
        echo "jfr summary $JFR_FILE" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "# CPU hotspots" >> "$FINAL_REPORT"
        echo "jfr print --events CPUSample $JFR_FILE" >> "$FINAL_REPORT"
        echo "" >> "$FINAL_REPORT"
        echo "# Memory allocation" >> "$FINAL_REPORT"
        echo "jfr print --events ObjectAllocationInNewTLAB $JFR_FILE" >> "$FINAL_REPORT"
        echo "----" >> "$FINAL_REPORT"
    fi

    cat >> "$FINAL_REPORT" << EOF

== Recommendations

$(if [[ -f "$CONTAINER_LOG" ]] && grep -q "jwt" "$CONTAINER_LOG"; then
    JWT_AVG_CPU=$(grep "jwt" "$CONTAINER_LOG" | awk -F',' '{sum+=$3; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
    JWT_AVG_MEM=$(grep "jwt" "$CONTAINER_LOG" | awk -F',' '{sum+=$6; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
    if (( JWT_AVG_CPU < 90 )); then
        echo "* ⚠️  JWT Container CPU: ${JWT_AVG_CPU}% - Increase JMH threads to reach 90%+"
    else
        echo "* ✅ JWT Container CPU: ${JWT_AVG_CPU}% - Target achieved"
    fi
    if (( JWT_AVG_MEM < 90 )); then
        echo "* ⚠️  JWT Container Memory: ${JWT_AVG_MEM}% - Reduce memory limit to reach 90%+"
    else
        echo "* ✅ JWT Container Memory: ${JWT_AVG_MEM}% - Target achieved"
    fi
else
    echo "* ❌ No JWT container metrics - ensure containers are running"
fi)
$(if [[ -f "$JFR_FILE" ]]; then echo "* 📊 JFR profiling captured - analyze for optimization opportunities"; else echo "* ⚠️  JFR profiling not captured - check native image JFR support"; fi)
* 🎯 **Baseline Status**: $(if [[ -f "$CONTAINER_LOG" ]] && grep -q "jwt" "$CONTAINER_LOG"; then
    JWT_CPU=$(grep "jwt" "$CONTAINER_LOG" | awk -F',' '{sum+=$3; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
    JWT_MEM=$(grep "jwt" "$CONTAINER_LOG" | awk -F',' '{sum+=$6; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
    if (( JWT_CPU >= 90 && JWT_MEM >= 90 )); then
        echo "ESTABLISHED - Ready for optimization testing"
    else
        echo "NOT ESTABLISHED - Adjust load/memory limits"
    fi
else
    echo "UNKNOWN - No container data"
fi)

== Data Files

* System metrics: link:$(basename "$SYSTEM_LOG")[]
* Container metrics: link:$(basename "$CONTAINER_LOG")[]
* Benchmark log: link:$(basename "$BENCHMARK_LOG")[]
$(if [[ -f "$JFR_FILE" ]]; then echo "* JFR profile: link:$(basename "$JFR_FILE")[]"; fi)

Generated: $(date)
EOF

    echo "📊 Report saved: $FINAL_REPORT"
}

# Main execution
trap cleanup INT TERM

echo "🔨 Step 1: Building integration tests (~6 seconds)..."
cd "$PROJECT_ROOT"
$MAVEN_CMD clean install -pl api-sheriff-quarkus-parent/api-sheriff-quarkus-integration-tests -q

echo "🔨 Step 2: Force clean rebuild of native executable and container (~90 seconds)..."
# Ensure clean rebuild includes updated application.properties
$MAVEN_CMD clean package -pl api-sheriff-quarkus-parent/api-sheriff-quarkus-integration-tests -Pintegration-tests -q
# Force Docker to rebuild container with updated configuration
docker system prune -f --volumes >/dev/null 2>&1 || true

echo "🚀 Step 3: Starting benchmark with comprehensive monitoring..."
echo "📊 Monitoring will capture system, container, and JFR data"
echo "⏰ Run for 2+ minutes, then press Ctrl+C"
echo ""

# Start benchmark with JFR recording
START_TIME=$(date +%s)
$MAVEN_CMD verify -pl api-sheriff-quarkus-parent/quarkus-integration-jmh -Pbenchmark > "$BENCHMARK_LOG" 2>&1 &
BENCHMARK_PID=$!

# Wait for containers to start
echo "⏳ Waiting for containers to initialize..."
sleep 15

# Check if containers are running
CONTAINERS=$(docker ps --format "{{.Names}}" | grep -E "(jwt|keycloak)" || echo "")
if [[ -n "$CONTAINERS" ]]; then
    echo "✅ Found containers: $CONTAINERS"
    
    # Start JFR recording if possible
    JWT_CONTAINER=$(echo "$CONTAINERS" | grep jwt | head -1)
    if [[ -n "$JWT_CONTAINER" ]]; then
        echo "📊 Starting JFR recording for $JWT_CONTAINER..."
        docker exec "$JWT_CONTAINER" sh -c "jcmd 1 JFR.start duration=120s filename=/tmp/api-sheriff-profile.jfr" 2>/dev/null || echo "⚠️  JFR recording not available (container may not support jcmd)"
        
        # Copy JFR file after recording
        (sleep 125; docker cp "$JWT_CONTAINER:/tmp/api-sheriff-profile.jfr" "$JFR_FILE" 2>/dev/null || echo "⚠️  JFR file not available") &
    fi
else
    echo "⚠️  No JWT/Keycloak containers found - container monitoring will be limited"
fi

# Start monitoring in background
monitor_system &
SYSTEM_MONITOR_PID=$!

monitor_containers &
CONTAINER_MONITOR_PID=$!

# Show live dashboard
show_dashboard