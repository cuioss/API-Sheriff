# WS-01: Defect Fixes

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-01-defect-fixes.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Fix the behavioural defects measured in API Sheriff 0.2.1 that need no new routing primitive: the two
blocking defects (AS-7 structured userinfo claims, AS-13 query validation after decoding) and the JWKS
readiness gap (AS-10, API-Sheriff#194). Closed when PLAN-13 has landed, with any externally gated item
recorded as a documented gap.

## Scope

- In scope: userinfo claim serialization, query-parameter handoff to the `cui-http` pipeline, readiness
  gating on live JWKS state, their docs and tests.
- Out of scope: `upstream.path` semantics (moved to WS-02 / PLAN-15 at aggregation), session and scopes
  (WS-04), routing and headers (WS-02), portal (WS-03). A `cui-http` or token-validation library release is
  outside this repository.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-13-defect-fixes | staged | AS-7 + AS-13 (blocking) + AS-10; 10 deliverables |

Superseded at aggregation (2026-09-15): PLAN-01, PLAN-02, PLAN-10, PLAN-11 — see `plans/superseded/README.md`.

## Sequencing and Surface Notes

- Queue head. Overlaps PLAN-14 (`BffRuntimeProducer.java`, docs) and PLAN-15 / PLAN-16
  (`GatewayEdgeRoute.java`, `doc/configuration.adoc`).
- The raw-query representation settled for AS-13 is the input for PLAN-14's return-URL work.
