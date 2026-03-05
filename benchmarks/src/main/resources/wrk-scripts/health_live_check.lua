-- health_live_check.lua
-- WRK Lua script for benchmarking /q/health/live endpoint
-- Sends GET requests with JSON Accept header and tracks errors

local error_count = 0
local success_count = 0
local non_200_count = 0

wrk.method = "GET"
wrk.headers["Accept"] = "application/json"

function response(status, headers, body)
    if status == 200 then
        success_count = success_count + 1
    else
        non_200_count = non_200_count + 1
    end
end

function done(summary, latency, requests)
    io.write("--- Lua Script Summary ---\n")
    io.write(string.format("Successful requests (200): %d\n", success_count))
    io.write(string.format("Non-200 responses: %d\n", non_200_count))
    io.write(string.format("Socket errors: connect=%d, read=%d, write=%d, timeout=%d\n",
        summary.errors.connect, summary.errors.read,
        summary.errors.write, summary.errors.timeout))
end
