-- health_live_check.lua
-- WRK Lua script for benchmarking /q/health/live endpoint
-- Sends GET requests with JSON Accept header and tracks errors

-- Fail the benchmark run when the failure rate exceeds this threshold. The gate
-- folds non-2xx HTTP responses AND socket-level errors (see done()), so an
-- unreachable upstream that returns zero HTTP responses still fails the run.
-- A gateway that starts serving 404s must NOT benchmark as an improvement, so
-- done() exits non-zero above the gate (set -e in the wrapper .sh fails the run).
-- Override at runtime via the WRK_MAX_ERROR_RATE env var (fraction, e.g. 0.05).
-- The override is validated to a finite fraction in [0, 1]: a negative value
-- would invert the gate, a value above 1 would disable it, and a malformed value
-- would silently mask the intended threshold, so any set-but-invalid value is
-- rejected (the default 0.01 applies only when the env var is unset).
local function resolveMaxErrorRate()
    local raw = os.getenv("WRK_MAX_ERROR_RATE")
    if raw == nil then
        return 0.01
    end
    local value = tonumber(raw)
    -- value ~= value is true only for NaN; math.huge guards +/-inf.
    if value == nil or value ~= value or value == math.huge or value == -math.huge then
        io.write(string.format(
            "FATAL: WRK_MAX_ERROR_RATE must be a finite number in [0, 1], got %q\n", raw))
        os.exit(1)
    end
    if value < 0 or value > 1 then
        io.write(string.format(
            "FATAL: WRK_MAX_ERROR_RATE must be within [0, 1], got %s\n", tostring(value)))
        os.exit(1)
    end
    return value
end
local MAX_ERROR_RATE = resolveMaxErrorRate()

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
    -- Socket-level failures never reach response(), so they are absent from the
    -- HTTP counters. Fold them into both the denominator and the failure count so
    -- an unreachable upstream (zero HTTP responses, all connect/read/write/timeout
    -- errors) fails the gate instead of scoring a false PASS at error_rate == 0.
    local socket_errors = summary.errors.connect + summary.errors.read
        + summary.errors.write + summary.errors.timeout
    local total = total_success + total_non_200 + socket_errors
    local failures = total_non_200 + socket_errors
    local error_rate = 0
    if total > 0 then
        error_rate = failures / total
    end

    io.write("--- Lua Script Summary ---\n")
    io.write(string.format("Successful requests (200): %d\n", total_success))
    io.write(string.format("Non-200 responses: %d\n", total_non_200))
    io.write(string.format("Socket errors: connect=%d, read=%d, write=%d, timeout=%d\n",
        summary.errors.connect, summary.errors.read,
        summary.errors.write, summary.errors.timeout))
    io.write(string.format("Failure rate: %.4f (non-2xx + socket errors; fail threshold %.4f)\n",
        error_rate, MAX_ERROR_RATE))

    if error_rate > MAX_ERROR_RATE then
        io.write(string.format(
            "FAIL: failure rate %.4f (non-2xx + socket errors) exceeds threshold %.4f\n",
            error_rate, MAX_ERROR_RATE))
        os.exit(1)
    end
end
