-- api_health_check.lua
-- WRK Lua script for benchmarking /api/health endpoint
-- Sends GET requests with JSON Accept header and validates 200 response

-- Fail the benchmark run when the non-2xx error rate exceeds this threshold.
-- A gateway that starts serving 404s must NOT benchmark as an improvement, so
-- done() exits non-zero above the gate (set -e in the wrapper .sh fails the run).
-- Override at runtime via the WRK_MAX_ERROR_RATE env var (fraction, e.g. 0.05).
local MAX_ERROR_RATE = tonumber(os.getenv("WRK_MAX_ERROR_RATE")) or 0.01

-- Each wrk worker thread runs in its own Lua state, so per-thread counters are
-- not visible from done() (which runs in the main state). Collect every thread
-- in setup() and read each thread's final counter values back in done() via
-- thread:get(name), then sum them for a real aggregate total.
local threads = {}

wrk.method = "GET"
wrk.headers["Accept"] = "application/json"

function setup(thread)
    table.insert(threads, thread)
end

function init(args)
    -- Declared as globals (no 'local') so wrk can read them back per thread
    -- via thread:get() in done().
    success_count = 0
    non_200_count = 0
end

function response(status, headers, body)
    if status == 200 then
        success_count = success_count + 1
    else
        non_200_count = non_200_count + 1
    end
end

function done(summary, latency, requests)
    local total_success = 0
    local total_non_200 = 0
    for _, thread in ipairs(threads) do
        total_success = total_success + (thread:get("success_count") or 0)
        total_non_200 = total_non_200 + (thread:get("non_200_count") or 0)
    end
    local total = total_success + total_non_200
    local error_rate = 0
    if total > 0 then
        error_rate = total_non_200 / total
    end

    io.write("--- Lua Script Summary ---\n")
    io.write(string.format("Successful requests (200): %d\n", total_success))
    io.write(string.format("Non-200 responses: %d\n", total_non_200))
    io.write(string.format("Error rate: %.4f (fail threshold %.4f)\n", error_rate, MAX_ERROR_RATE))
    io.write(string.format("Socket errors: connect=%d, read=%d, write=%d, timeout=%d\n",
        summary.errors.connect, summary.errors.read,
        summary.errors.write, summary.errors.timeout))

    if error_rate > MAX_ERROR_RATE then
        io.write(string.format(
            "FAIL: non-2xx error rate %.4f exceeds threshold %.4f\n", error_rate, MAX_ERROR_RATE))
        os.exit(1)
    end
end
