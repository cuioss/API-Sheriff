# PLAN-30: Early Upstream Answers Exhaust the Gateway's Local Ports and Open the Circuit Breaker

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-30-early-answer-upstream-port-exhaustion.md` and is queued as one row file,
> `queue/PLAN-30.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-13 from inbox message `plan-28-jwks-refresh-follow-ups-001.md` and from PLAN-28's
> landing (`landings/PLAN-28.md`, message `plan-28-jwks-refresh-follow-ups-008.md`).

## Objective

Since #431 (ADR-0066), when an upstream answers before the request body has finished, the gateway resets
the upstream request. On an HTTP/1.1 upstream that closes the connection. The gateway closes first, so
each closed connection holds one of the gateway's local ports for 60 seconds. PLAN-28 measured what that
does under load: at roughly 470 early answers per second to one upstream address the gateway has no
local port left, new upstream connections fail, the route's circuit breaker opens, and every request to
that upstream is answered `503` for up to a minute — not only the uploads.

An upstream answering an upload early is legitimate HTTP; ADR-0066 says so. So this is a way for
ordinary traffic, or a client sending such traffic on purpose, to cut the gateway off from an upstream.
This plan removes it.

## Source

Inbox `plan-28-jwks-refresh-follow-ups-001.md` (finding, 2026-10-13), filed under PLAN-28's STOP rule
from its deliverable 8. The evidence is one local benchmark run on a branch merged with `main` at #431
and #435. Promoted lesson `2026-10-13-14-001` records that this defect was first mis-diagnosed from
reading code: **do not record a fix here as working until a run shows it**.

## Deliverables

1. **Reproduce it in a test before changing anything.** A test that drives early answers on an HTTP/1.1
   upstream and observes the consequence the finding reports. The finding's numbers come from one
   benchmark run; a unit or integration test will not exhaust 28,000 ports, so settle what the test
   asserts instead (for example: after an early answer the upstream connection is reused, or the number of
   upstream connections opened stays bounded across N early answers). The test must fail on `main` and
   pass after deliverable 2.
2. **Stop an early answer from costing an upstream connection, or bound the cost.** The finding names
   three directions and decides none: (a) on an HTTP/1.1 upstream, do not reset after a complete early
   response, and drain or discard the rest of the forwarded body within a bound so the connection returns
   to the pool; (b) keep the reset and change who closes first, or rely on a socket setting, which is a
   deployment matter and not code; (c) whatever else is chosen, see deliverables 3 and 4.
   ⚠ **Put the choice to the operator at outline time with a recommendation.** ADR-0066 rejected draining
   for the inbound leg; say why the upstream leg is or is not different. Record the decision as an
   amendment to ADR-0066 or as a new ADR (derive the ordinal by listing `doc/adr/` at write time).
   ⛔ HTTP/2 upstreams and the inbound reset ADR-0066 introduced are NOT in scope: do not reopen them.
3. **Decide whether the gateway's own reset counts as an upstream failure.** Today a reset the gateway
   issued after a complete upstream response can feed the circuit breaker. Decide and record whether it
   should; if not, keep it out of the breaker's failure count, with a test.
4. **Stop logging the gateway's own upstream reset at ERROR.** Vert.x logs every one
   (`HttpClientRequestImpl … Stream reset: 0`): 28,231 ERROR lines in 46 s in the measured run, with no
   `LogRecord` and no route context. Absorb it with a handler on the upstream request, the way ADR-0066
   keeps the gateway's own inbound reset out of `INBOUND_BODY_ABORTED`.
5. **Make the upload benchmarks measure uploads.** `nginx-static` answers `upload_small` and
   `upload_large` before reading the body, so since #431 both measure the early-answer path. Give the
   fairness backend a location that reads the body for those routes. ⚠ Do this AFTER deliverable 2 is
   shown to work on the unchanged benchmark: changing the backend first would hide the defect the plan
   exists to fix. Keep one early-answer measurement if it is cheap, so a regression stays visible.
6. **Show the fix under load.** Run the benchmark (`verify -Pbenchmark -pl benchmarks -am`) on the fixed
   branch with the backend still answering early, and record the `upload_small` iteration count and
   error rate next to PLAN-28's numbers (47,441 iterations, error rate 0.40). The claim "fixed" needs a
   run past the old cliff of about 28,000 iterations with an error rate of 0.
7. **Documentation.** ADR-0066's negative consequence ("a new upstream connection per early answer") and
   the passages of `doc/architecture.adoc` and `doc/security-threat-model.adoc` that #431 added, each
   corrected to what the gateway does after this plan; `doc/LogMessages.adoc` if a record is added;
   `doc/development/integration-test-topology.adoc` and `nginx/body-limits.conf`'s own comment for the
   changed backend.

Split guard: 7 deliverables — within the operator-authorized 12.

## Claim Labels

- OBSERVED: ADR-0066 states that resetting an HTTP/1.1 upstream request closes that upstream connection instead of returning it to the pool, "paid for with a new upstream connection per early answer" — read at `doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc` § Consequences (lines 164 to 165), at `48e8d5a6`
  - verdict: corroborated | checked_at: 48e8d5a6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: ADR-0066 lines 164-165 at 48e8d5a6: resetting an HTTP/1.1 upstream request closes that upstream connection, paid for with a new upstream connection per early answer
- OBSERVED: the edge resets upstream requests at `GatewayEdgeRoute` (`upstream.request().reset()`, line 1864) and at three sites in `DispatchStage` (616, 696, 712) — read by searching `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/` for `.reset(` at `48e8d5a6`
  - verdict: corroborated | checked_at: 48e8d5a6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: search for .reset( under edge/ at 48e8d5a6: GatewayEdgeRoute:1864 upstream.request().reset(); DispatchStage:616, 696, 712
- OBSERVED: `EarlyResponseEnd`, `InboundStreamWindow`, `ResponseEnd`, `GatewayEdgeEarlyResponseResetTest`, `Http2EarlyResponseResetIT` and `sheriff-config/endpoints/early-answer.yaml` exist, all added by #431 — read from the diff of `09edaea9`
  - verdict: corroborated | checked_at: 48e8d5a6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: all six files are in the diff of 09edaea9 (#431)
- OBSERVED: `integration-tests/src/main/docker/nginx/body-limits.conf` carries a comment about its `return 200` location — read at that file (line 10), at `48e8d5a6`
  - verdict: corroborated | checked_at: 48e8d5a6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: body-limits.conf:10 mentions the current return 200 location
- HYPOTHESIS: the reset that fires on an early answer is the one in `GatewayEdgeRoute` and not one of the three in `DispatchStage` — the orchestrator did not trace it. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § the call site of `upstream.request().reset()` and `EarlyResponseEnd` (verify-at-outline)
- HYPOTHESIS: the gateway runs out of local ports at about 470 early answers per second, because each closed connection holds a port in TIME_WAIT for 60 s (port range 32768 to 60999, `tcp_tw_reuse` 2), and the breaker then opens (`ApiSheriff-103`) — the sender's measurement from one local run (28,231 resets, 47,441 iterations, error rate 0.404924); the orchestrator read none of the logs. Confirm/refute by deliverable 1's reproduction (verify-at-outline)
- HYPOTHESIS: a gateway-issued upstream reset after a complete response is counted by the circuit breaker as a failure — the finding raises it as a question, not as an observation. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java` § the guard's failure predicate (verify-at-outline)
- HYPOTHESIS: CI's benchmark stays green only because its runner is slower (26,442 iterations in run 38370104773, 1,790 below the port range) — the sender's inference. The post-merge run of #437 (38407570839) was also green. Confirm/refute by deliverable 6 (verify-at-outline)
- Verify-first clause: deliverable 1 comes first and its test must fail on `main`. If the consequence cannot be reproduced in a test or in a benchmark run on current `main`, STOP and file a finding: the mechanism is then not understood, and lesson `2026-10-13-14-001` is about exactly that.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/EarlyResponseEnd.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — `GatewayEdgeEarlyResponseResetTest`, `DispatchStageTest` and the new reproduction
- OBSERVED: `doc/adr/` — an amendment to ADR-0066 or one new record
- OBSERVED: `doc/architecture.adoc`, `doc/security-threat-model.adoc`
- OBSERVED: `integration-tests/src/main/docker/nginx/body-limits.conf` — deliverable 5
- OBSERVED: `doc/development/integration-test-topology.adoc`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseEnd.java` — #431 changed both for the inbound leg (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` and `doc/LogMessages.adoc` — only if deliverable 3 or 4 adds or changes a record (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/Http2EarlyResponseResetIT.java` and `integration-tests/src/main/docker/sheriff-config/endpoints/early-answer.yaml` — if the reproduction or its proof is at integration level (verify-at-outline)
- HYPOTHESIS: `integration-tests/docker-compose.yml`, `integration-tests/docker-compose.benchmark.yml` and `integration-tests/src/main/docker/sheriff-config/endpoints/httpbin.yaml` — if the body-reading backend location needs a route or a service change (verify-at-outline)
- HYPOTHESIS: `benchmarks/src/main/resources/k6-scripts/upload_small.js` and `benchmarks/src/main/resources/k6-scripts/` — if an early-answer measurement is kept as its own script (verify-at-outline)
- HYPOTHESIS: `doc/development/declared-limit-assertion-coverage.adoc` — if a bound is introduced for the drain (verify-at-outline)

## Dependencies and Sequencing

- Depends on: #431 (09edaea9, landed) and PLAN-28 (#437, landed).
- ⚠ **#431 was a spin-off plan outside both epics' queues; the sibling epic `api-sheriff-0-2-0` tracks
  it** as the fix for its Open Defect 16 and is still confirming it over further benchmark runs. Before
  the outline, read that epic's ledger on `origin/main` (`.plan/orchestrator/api-sheriff-0-2-0/`) for a
  staged or running plan on the early-answer path (its PLAN-V02-06 mentions #431). If one exists, STOP
  and say so: two plans must not rework that path at once.
- Runs BEFORE PLAN-29: both change `GatewayEdgeRoute` and `DispatchStage`, and this one is a defect with
  an availability consequence while PLAN-29 settles inert configuration keys.
- Overlaps with: PLAN-29 on the two edge classes, `doc/architecture.adoc` and `doc/adr/`; PLAN-20 on
  `GatewayEdgeRoute`, `DispatchStage` and `ResponseStage`; every staged spec on `doc/adr/`.
- Adds at most one integration suite. Needs benchmark runs (about 19 minutes each in CI).
- **If a test fails against shipped behaviour this plan did not intend to change, STOP and file a
  finding** to the epic inbox.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-30-early-answer-upstream-port-exhaustion.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
