# PLAN-V02-25: Decompose `GatewayEdgeRoute` — Per-Route Policy Reaches Stages Through One Resolved Context

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator: "GatewayEdgeRoute is
> a nightmare. We need a major rework here."). Second of three refactor plans, after `PLAN-V02-24`.

## Objective

`edge/GatewayEdgeRoute.java` is 2,664 lines with about 78 methods. Every per-route setting is threaded
by hand from it into each pipeline stage and relay call site, so every new per-route policy edits this
class in several places (V02-23's deliverables 1, 3, 4 and 5 all did). Split it so that per-route
policy reaches the stages through one resolved route context, and the class keeps only the wiring.

Pure refactor: no observable behaviour change. The edge's behaviour is pinned by a large suite — the
framing corpus, the early-answer tests, the WebSocket and gRPC relays, the integration tests — and that
suite passing unchanged is this plan's evidence. A test changed to make the refactor pass needs its
reason stated in the PR.

## Deliverables

1. **Map it first.** List the class's responsibilities (listener and transport setup, upstream client
   and guard construction, stage wiring, relay paths for HTTP, gRPC and WebSocket, error rendering,
   early-answer handling) and every per-route value threaded into a stage. Settle the target split at
   outline and put it to the operator before moving code.
2. **A resolved route context.** The stages and relay paths read per-route policy from one immutable
   context built from `ResolvedRoute` (`PLAN-V02-24`'s output), instead of receiving it as hand-threaded
   parameters. A new per-route policy is then one field on the context plus its consumer.
3. **Move responsibilities out.** Extract the responsibilities from deliverable 1 into focused classes in
   `edge/` (or sub-packages with `package-info.java`). `GatewayEdgeRoute` keeps routing and wiring only.
   The early-answer path (ADR-0066, #431, #448 and kidicap `PLAN-32`) moves as a unit; do not change its
   behaviour.
4. **Tests follow the code.** Unit tests for each extracted class; existing tests are moved, not
   rewritten, wherever possible. Framing corpus, `Http2EarlyResponseResetIT` and the relay ITs stay green.
5. **Architecture document.** `doc/architecture.adoc` describes the new edge structure; an ADR records the
   context seam if it constrains future stages.

Split guard: 5 deliverables. Keep the PR under 100 files and 150,000 diff characters; if the move is
larger, ship deliverable 2 first and the extractions in a second PR.

## Claim Labels

- OBSERVED: `GatewayEdgeRoute.java` is 2,664 lines at `5490144f` — `git show | wc -l`
- OBSERVED: `edge/` holds 16 source files at `5490144f` — `git ls-tree`
- HYPOTHESIS: about 78 method declarations, and every per-route setting is passed by hand into stages — the V02-23 finding's count; re-measure at outline (verify-at-outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/` — `GatewayEdgeRoute` and the stages it wires
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/`, `forward/` — stage signatures, if they take the context (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/`
- OBSERVED: `doc/architecture.adoc`; `doc/adr/` if a record is written

## Dependencies and Sequencing

- Depends on: `PLAN-V02-24` (the resolved route this context is built from), and kidicap `PLAN-32`
  (the early-answer IT fix, running, in `GatewayEdgeRoute`) having landed.
- Parallel with `PLAN-V02-26` (validator split): disjoint surfaces, `edge/` versus `config/validation/`.
- Before: the re-grounded `PLAN-V02-23`; and holds every staged plan editing `edge/**`: `PLAN-V02-05`,
  `-06`, `-07`; kidicap `PLAN-20`, `-29`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-25-edge-route-decomposition.md" plan_id=plan-v02-25-edge-route-decomposition
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
