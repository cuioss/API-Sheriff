envelope_version=1
sender_type=plan
sender_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-13T10:17:11Z

# Early upstream answers on an HTTP/1.1 upstream use up the gateway's ephemeral ports and trip the circuit breaker (residual uploadSmall failure after #431)

## Summary

Since #431 (ADR-0066, step 1), when an upstream answers before the request body has finished, the gateway resets the upstream request. On an HTTP/1.1 upstream that closes the connection instead of returning it to the pool. ADR-0066 lists this as a negative consequence ("a new upstream connection per early answer"). It did not cover what happens under sustained load. The gateway closes first, so every closed connection holds one of the gateway container's ephemeral ports in TIME_WAIT for 60 s. The container has 28,232 ports (32768-60999), and port reuse is limited to loopback (tcp_tw_reuse=2). Above about 470 early answers per second to one upstream address, the gateway runs out of local ports. New upstream connects then fail, the route's circuit breaker opens, and the upstream gets no traffic and every request to it is answered 503 until the oldest TIME_WAIT entries expire (up to 60 s).

This is a gateway defect, not a benchmark-script or stack defect. Under PLAN-28 TASK-12's STOP rule it is reported here and not fixed.

## Evidence (PLAN-28 TASK-12, local benchmark run 1, branch merged with main incl. #431 + #435)

- Gateway log `benchmarks/target/benchmark-results/quarkus-logs/quarkus.log`, uploadSmall window 10:10:13-10:11:12 UTC:
  - 28,231 consecutive `ERROR [io.ver.cor.htt.imp.HttpClientRequestImpl] Stream reset: 0` lines from 10:10:13.007 to 10:10:59.097. These are the gateway's own resets of the HTTP/1.1 upstream request (nginx-static answers `return 200` before reading the body). No other log line falls in that range.
  - 4 ms after the last reset: `ApiSheriff-103: Circuit breaker opened for upstream 'nginx-static:8080:retry=true'`. It re-opened at 10:11:04 and 10:11:09, so every half-open probe failed. The oldest TIME_WAIT entries (from 10:10:13) expire only at about 10:11:13, after the run ended.
- k6 (uploadSmall): 47,441 iterations, error_rate 0.404924 → 19,210 failed + 28,231 succeeded. The success count equals the reset count exactly and matches the 28,232-port range (one port presumably held by a pooled connection). k6 progress shows a sharp cliff, not a gradual slowdown: about 480 iterations/s up to 45.9 s (28,119 complete), then about 1,400/s of fast-failing requests.
- Gateway container network namespace (read with `docker run --net container:integration-tests-api-sheriff-1 busybox`): `ip_local_port_range 32768 60999`, `tcp_tw_reuse 2`, `tcp_fin_timeout 60`.
- Green CI post-merge run 38370104773 (main after #431): uploadSmall 26,442 iterations (440.07 rps), error_rate 0. That is 1,790 below the port budget, so CI stays green only because its runner is slower. A slightly faster runner, or a longer duration, crosses the cliff.
- Different from the original CI failure 38307102339 (pre-#431, GOAWAY ENHANCE_YOUR_CALM on the k6→gateway HTTP/2 edge). That failure is fixed by #431. The failure reported here is on the gateway→upstream HTTP/1.1 leg and was introduced by #431's upstream reset.

## Secondary defect

Every deliberate upstream reset is logged by Vert.x at ERROR (`HttpClientRequestImpl ... Stream reset: 0`): 28,231 ERROR lines in 46 s, with no LogRecord and no route context. The gateway's own reset should be absorbed by an exception handler on the upstream request, in the same way ADR-0066 keeps the gateway's own inbound reset out of INBOUND_BODY_ABORTED.

## Impact

Any upstream that legitimately answers uploads early (ADR-0066 itself calls this legitimate HTTP behaviour) can make the gateway cut itself off from that upstream at about 470 early answers/s: the circuit breaker opens and stays open for up to 60 s. It is load-dependent and self-inflicted. The client sees 503 on all traffic to that upstream, not only on uploads.

## Options for the owning plan (not decided here)

1. On an HTTP/1.1 upstream, do not reset after a complete early response. Drain or discard the remaining forwarded body within a bound, so the connection can be reused. This is the same trade-off ADR-0066 rejected for the inbound leg, but the upstream leg has different costs.
2. Keep the reset but stop the port churn: TIME_WAIT on the gateway side can only be avoided by not closing first, or by tuning the socket/sysctl (`tcp_tw_reuse=1` is per network namespace and a deployment setting, not code).
3. In any case: absorb the gateway's own upstream reset (no ERROR log per early answer), and decide whether a gateway-initiated reset after a complete upstream response should count as a circuit-breaker failure.

## Benchmark-side note

nginx-static answers uploads with `return 200` before reading the body (`integration-tests/src/main/docker/nginx/body-limits.conf` says so). After #431 the gateway therefore stops forwarding the body at the early answer, so uploadSmall/uploadLarge measure the early-answer path, not the upload throughput the scripts claim. Making the fairness backend read the body (for example a body-reading location for `/anything/upload-*`) would make the aspect measure what it says, and would also hide this defect from the benchmark. That change is outside PLAN-28's declared files and was deliberately not made.

## Source

plan-28-jwks-refresh-follow-ups, TASK-12 (deliverable 8). Maven log: `.plan/local/plans/plan-28-jwks-refresh-follow-ups/build-results/benchmarks/maven-2026-10-13-095505.log` (worktree).
