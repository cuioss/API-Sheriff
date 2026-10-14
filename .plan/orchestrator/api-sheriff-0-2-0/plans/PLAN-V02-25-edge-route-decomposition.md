# PLAN-V02-25: Decompose `GatewayEdgeRoute` and Merge the Two Per-Route Types

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator: "GatewayEdgeRoute is
> a nightmare. We need a major rework here."). Design settled with the operator the same day (decisions
> in `epic.md` § Decisions, 2026-10-14). Second of three refactor plans, after `PLAN-V02-24`, parallel
> with the validator split (`PLAN-V02-26`, then slices `PLAN-V02-28` … `PLAN-V02-39`).

## Objective

Two problems, both measured at `5490144f`:

- **Every per-route value is carried by two parallel types.** `config/model/ResolvedRoute` (the resolved
  configuration) and `routing/RouteRuntime` (206 lines: the same effective auth, methods, forward block,
  security headers, retry and not-modified toggles, allowed origins and WebSocket idle timeout, plus the
  runtime objects — matcher, protocol processor, security configuration, HTTP client, resilience guard,
  location rewriter, asset source). `edge/RouteRuntimeAssembler` (305 lines) copies field by field from
  one to the other. A new per-route policy is a field in both plus a copy line.
- **`edge/GatewayEdgeRoute.java` (2,664 lines) does about eight jobs:** request admission and connection
  lifecycle (in-flight count, connection retirement, framing rejection); the reserved-path body ceiling;
  reserved and BFF endpoints and the portal; proxy dispatch for HTTP, WebSocket, gRPC, assets and
  redirects; the relay, including the early-answer handling (ADR-0066, #431, #448, kidicap `PLAN-32`);
  rejection rendering (problem+json, error pages, `Retry-After`); building the pipeline request (method,
  query, `Content-Length`, authority parsing); and upstream client, egress TLS, header-security
  configuration and resilience-guard construction.

Refactor: no observable behaviour change. The existing suite — the framing corpus, the early-answer
tests, the relay and WebSocket/gRPC tests, the integration tests — passing is the evidence (operator
decision: no additional golden comparison). A test changed to make the refactor pass needs its reason
stated in the PR.

## Deliverables

1. **Merge `ResolvedRoute` and `RouteRuntime` into one per-route type** (operator decision: merge, not
   wrap). The resolved policy and the runtime objects live in one immutable object built once at boot;
   `RouteRuntimeAssembler`'s field-by-field copy disappears. Settle at outline which package owns the
   merged type and how the boot-time construction is split between resolution (`PLAN-V02-24`'s
   mechanism) and runtime assembly (clients, guards, matchers). Migrate every user, tests included, using
   its `@Builder`.
2. **Map `GatewayEdgeRoute`'s jobs and settle the target split at outline**, starting from the eight
   jobs above; put the split to the operator before moving code.
3. **Extract the jobs into sub-packages of `edge/`** (operator decision), each with a `package-info.java`
   — for example `edge/admission`, `edge/reserved`, `edge/dispatch`, `edge/relay`, `edge/reject`,
   `edge/request`, `edge/client`; names settled at outline. `GatewayEdgeRoute` keeps route registration
   and the top-level `handle` → `process` flow only. The early-answer path moves as a unit and does not
   change behaviour. Add an architecture test that guards the package boundaries (no cycles; the
   sub-packages do not reach back into `GatewayEdgeRoute`).
4. **Tests follow the code.** Existing tests are moved with the code they test, not rewritten; new unit
   tests only where an extracted class has none. Simplifying the tests is `PLAN-V02-27`'s job, after this
   has merged.
5. **Architecture documentation.** `doc/architecture.adoc` describes the new edge structure and the single
   per-route type; an ADR records the package boundaries and the merge (derive the ordinal at write time).

Split guard: 5 deliverables. Keep each PR under 100 files and 150,000 diff characters; if needed, ship
deliverable 1 first and the extractions second.

## Claim Labels

- OBSERVED: `GatewayEdgeRoute.java` 2,664 lines; `RouteRuntime.java` 206; `RouteRuntimeAssembler.java` 305 — `wc -l` at `5490144f`
- OBSERVED: `RouteRuntime` carries the effective policy fields of `ResolvedRoute` plus the runtime objects listed in the Objective — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java`
- OBSERVED: the eight responsibilities, read from `GatewayEdgeRoute`'s method list (`handle`, `readReservedBodyThenDispatch`, `retireConnectionAfterResponse`, `dispatchReserved`, `handlePortal`, `dispatchAndRelay`, `dispatchWebSocket`, `dispatchGrpc`, `serveAsset`, `startRelay`, `renderRejection`, `renderProblem`, `buildPipelineRequest`, `clientFor`, `egressTlsBound`, `cookieHeaderConfigurationFor`, `guardFor`, …) at `5490144f`

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/` and new sub-packages under it
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ResolvedRoute.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/RouteRuntimeAssembler.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/`, `forward/`, `routing/` — readers of the per-route type (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/`, `routing/`, `config/`
- OBSERVED: `doc/architecture.adoc`, `doc/adr/`

## Dependencies and Sequencing

- Depends on: `PLAN-V02-24` (its resolution mechanism builds the merged type's policy half) and kidicap
  `PLAN-32` (early-answer IT fix, in `GatewayEdgeRoute`) having landed.
- Parallel with whichever validator slice (`PLAN-V02-28` … `PLAN-V02-39`) is running: disjoint surfaces. Emitted once
  V02-24 and kidicap PLAN-32 have both landed.
- Before: the re-grounded `PLAN-V02-23`, then `PLAN-V02-27`. Holds every staged plan editing `edge/**`:
  `PLAN-V02-05`, `-06`, `-07`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-25-edge-route-decomposition.md" plan_id=plan-v02-25-edge-route-decomposition
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
