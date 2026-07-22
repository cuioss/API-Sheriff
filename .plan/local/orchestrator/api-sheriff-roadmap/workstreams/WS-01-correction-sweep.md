# WS-01: Correction Sweep

epic: api-sheriff-roadmap

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-01-correction-sweep.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-marshall-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Bring documentation and delivered code back into agreement and clear the test/benchmark
quality debt surfaced by the 2026-07-17 adversarial review, before anything later builds on
contradictory docs or an untrustworthy benchmark harness. Pure correction sweep — no features.
Closes when roadmap plan 02b lands.

## Scope

- In scope: doc corrections (ADRs, README ports, library claims, terminology), dead-code
  deletion (`validateWholeSecondTimeouts`, `jwks: inline`), test hygiene (vacuous tests,
  Hamcrest migration, invalid-config script wiring), benchmark harness repair (wrk counter
  aggregation, error-rate gating, post-processor matching + value-asserting tests).
- Out of scope: everything `doc/plan/03-endpoint-anchors.adoc` owns (placeholder substitution,
  anchors, validator hardening); the interim proxy's runtime enforcement gaps (Plan 04 replaces it).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-01-reconciliation | shipped | Roadmap plan 02b — PR #72 merged 2026-07-18; workstream closed |

## Sequencing and Surface Notes

- Runs first: the roadmap's sequencing rationale wants 02b landed before (or alongside) 03.
- Surface is disjoint from roadmap plan 03 beyond trivial doc passages (per the 02b scope guard),
  so PLAN-02-endpoint-anchors could in principle run in parallel; default is sequential.
