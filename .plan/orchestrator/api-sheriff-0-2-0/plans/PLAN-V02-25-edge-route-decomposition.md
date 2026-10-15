# PLAN-V02-25: Decompose `GatewayEdgeRoute` into Sub-Packages of `edge/`

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator: "GatewayEdgeRoute is
> a nightmare. We need a major rework here."). Design settled with the operator the same day (decisions
> in `epic.md` § Decisions, 2026-10-14). Split 2026-10-15 by operator decision: the per-route type merge is
> `PLAN-V02-44`, which runs first; this plan is the decomposition.

## Objective

**`edge/GatewayEdgeRoute.java` (2,664 lines at `5490144f`) does about eight jobs:**

- request admission and connection lifecycle (in-flight count, connection retirement, framing rejection);
- the reserved-path body ceiling;
- reserved and BFF endpoints and the portal;
- proxy dispatch for HTTP, WebSocket, gRPC, assets and redirects;
- the relay, including the early-answer handling (ADR-0066, #431, #448, kidicap `PLAN-32`);
- rejection rendering (problem+json, error pages, `Retry-After`);
- building the pipeline request (method, query, `Content-Length`, authority parsing);
- upstream client, egress TLS, header-security configuration and resilience-guard construction.

This is a refactor with no observable behaviour change. The evidence is the existing suite passing: the
framing corpus, the early-answer tests, the relay and WebSocket/gRPC tests, and the integration tests
(operator decision: no additional golden comparison). If a test has to change to make the refactor pass,
the PR states why.

## Deliverables

1. **Map `GatewayEdgeRoute`'s jobs and settle the target split at outline.** Start from the eight jobs
   above, re-measured on the post-`PLAN-V02-44` and post-`PLAN-32` code. Put the split to the operator
   before moving any code.
2. **Extract the jobs into sub-packages of `edge/`** (operator decision), each with a `package-info.java`.
   Example names: `edge/admission`, `edge/reserved`, `edge/dispatch`, `edge/relay`, `edge/reject`,
   `edge/request`, `edge/client`; the names are settled at outline. `GatewayEdgeRoute` keeps only route
   registration and the top-level `handle` → `process` flow. The early-answer path moves as one unit and
   its behaviour does not change. Add an architecture test that guards the package boundaries: no cycles,
   and no sub-package reaches back into `GatewayEdgeRoute`.
3. **Tests follow the code.** Move existing tests with the code they test; do not rewrite them. Add new
   unit tests only where an extracted class has none. Simplifying the tests is `PLAN-V02-27`'s job, after
   this plan has merged.
4. **Architecture documentation.** `doc/architecture.adoc` describes the new edge structure. An ADR records
   the package boundaries and the single per-route type from `PLAN-V02-44`. Derive the ordinal at write
   time; 0068 is the next free one on 2026-10-15.

**Size.** Split guard: 4 deliverables. The 100-file cap holds. Moving ~2,600 lines counts twice in a diff,
so this plan will pass 150,000 characters. Under the operator's 2026-10-15 rule for move-only work, that
cap is lifted: the code moves without changing behaviour. If the PR would pass ~350 KB, or CodeRabbit
declines to review it, split at a sub-package boundary and report the split in the inbox message.

## Claim Labels

- OBSERVED: `GatewayEdgeRoute.java` 2,664 lines — `wc -l` at `5490144f`
- OBSERVED: the eight responsibilities, read from `GatewayEdgeRoute`'s method list (`handle`, `readReservedBodyThenDispatch`, `retireConnectionAfterResponse`, `dispatchReserved`, `handlePortal`, `dispatchAndRelay`, `dispatchWebSocket`, `dispatchGrpc`, `serveAsset`, `startRelay`, `renderRejection`, `renderProblem`, `buildPipelineRequest`, `clientFor`, `egressTlsBound`, `cookieHeaderConfigurationFor`, `guardFor`, …) at `5490144f`

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/` and new sub-packages under it
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/`
- OBSERVED: `doc/architecture.adoc`, `doc/adr/`

## Dependencies and Sequencing

- Depends on `PLAN-V02-44` (the per-route type merge) and kidicap `PLAN-32` (the early-answer IT fix in
  `GatewayEdgeRoute`, PR #466) having landed. `PLAN-V02-24` landed with #465.
- Can run alongside whichever validator slice (`PLAN-V02-28`, `PLAN-V02-40` … `-43`) is running: their
  surfaces are disjoint.
- Comes before the re-grounded `PLAN-V02-23`, then `PLAN-V02-27`. Holds every staged plan that edits
  `edge/**`: `PLAN-V02-05`, `-06`, `-07`. Never runs alongside `PLAN-V02-04`, because both author ADRs.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-25-edge-route-decomposition.md" plan_id=plan-v02-25-edge-route-decomposition
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
