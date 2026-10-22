# Landing Analysis: PLAN-V02-45 — Request-Body and HTTP/2 Buffer Bounds

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #506 (https://github.com/cuioss/API-Sheriff/pull/506)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-22 from the inbox message
> `plan-v02-45-request-body-and-h2-buffer-bounds-019.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `99e9dfd3` (#506, merge queue): 24 files changed (+3,999 / −251) in 22 commits,
18 of them Java.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — refused and queued HTTP/2 streams release their credit | met (measure-first, no code change) | Proven by tests; recorded in `doc/architecture.adoc` and ADR-0060 Amendment A2. |
| D2 — inbound connection window | met (measure-first, no code change) | A 2 MiB window was measured against the derived one and rejected on the numbers. |
| D3 — request-body bound (verify-first) | met with residual | A fixed 25 s deadline from the start of the upstream attempt: `408 REQUEST_BODY_TIMEOUT` before the response head, never counted by the breaker, never retried; `ApiSheriff-24` (latched per route). A fixed constant: no `gateway.yaml` key, nothing under `config/validation/` changed (diff checked). |
| D4 — gRPC stream-idle bound (verify-first) | met with residual | A fixed five-minute bound after the response headers, ending with `grpc-status: 4`; `ApiSheriff-25` (latched per route). |
| D5 — PLAN-32 test gaps | met | Red proof recorded for three of the four slow-body tests (the fourth was green before the fix). |
| D6 — docs and decision record | met | ADR-0060 Amendment A2 (2026-10-21), `doc/LogMessages.adoc` rows 24/25, `doc/user/endpoint-routes.adoc`, `doc/user/protocol-routes.adoc`, threat model GW-07. |

The spec's verify-first correction held: the plan did not add a whole-body deadline as a config key, and
ADR-0060 A1's rejection of a per-route whole-body deadline still stands.

## Metrics and Anomalies

- Tokens: 10,188,347. Wall time: 76,121 s (21.1 h), in parallel with `PLAN-V02-43`, `-47` and `-22`. Model: claude-sonnet-5-5, effort medium.
- Code review: 0 blocking, 6 advisory. Functional review: 4 met, 2 met with residuals.
- **Review loop.** Five rounds of late wording corrections in threat-model, validation-matrix, ADR and
  Javadoc text each moved the head and re-fired the head-dependent reviews. The run closed the loop
  with one Javadoc-only inline correction (`24ca7e1a`); the pushed tree differs from the reviewed tree by
  that commit alone.
- **CodeRabbit** found a real defect none of the in-run reviews caught (a late timeout callback writing
  request-context data from the upstream event loop); fixed by TASK-15 and resolved. 0 unresolved threads.
- Sourcery declined on diff size (optional bot).

## Routing and Merge Behavior

- Merged through the merge queue as `99e9dfd3`.
- Post-merge on `main` at `99e9dfd3`, all passed: Maven Build, Integration Tests, Demo Client E2E, Config
  Validation, Scorecard.

## Residue and Owners

- Threat-model GW-07 (request body) stays PARTIAL: two steps in `dispatchGrpc` are run by `GrpcProxyIT`
  but asserted by no test. Owner: `PLAN-V02-27` (test simplification) or the next plan touching gRPC dispatch.
- Three open test-gap advisories: the late-timeout context-data case drives the new method directly, and the
  per-route log latch of `ApiSheriff-24` / `ApiSheriff-25` is reached by no test. Same owner.
- `PLAN-V02-46` is unblocked and re-grounded at `99e9dfd3`; the 25 s deadline is one more trigger of the
  upstream close it re-evaluates.
- **Lessons.** 18 candidate lessons. Project-local ones are promoted to the local store (below); the
  plan-marshall ones are routed to the plan-marshall lessons-routing inbox as messages 041–047
  (043, 045 and 047 are recurrences).
  - `2026-10-22-05-001`: an edge end-path change checks the governing end-path ADRs first.
  - `2026-10-22-05-002`: a threat-model coverage status is worded no stronger than its test evidence.
  - `2026-10-22-05-003`: one test per timing window an ADR amendment names.
  - `2026-10-22-05-004`: count and exclusivity words in Javadoc go stale.
  - `2026-10-22-05-005`: an upstream-loop callback never writes RoutingContext data.
