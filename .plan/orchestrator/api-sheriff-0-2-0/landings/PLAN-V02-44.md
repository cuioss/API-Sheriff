# Landing Analysis: PLAN-V02-44 — Merge `ResolvedRoute` and `RouteRuntime` into One Per-Route Type

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #476 (https://github.com/cuioss/API-Sheriff/pull/476)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-17 from the inbox message
> `plan-v02-44-route-type-merge-010.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `68a652a2` (#476, merge queue). The PR changed 74 files (+3,505 / −2,551)
with a **552,507-byte** diff.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — settle the shape at outline | shipped | The merged type is `routing/Route`. Runtime assembly stays in `edge/RouteAssembler` (renamed from `RouteRuntimeAssembler`). Policy comes from `config/PolicyBlock`. |
| D2 — merge and migrate every reader | shipped, met with residual | `config/model/ResolvedRoute`, `routing/RouteRuntime` and `edge/RouteRuntimeAssembler` are absent on `main`; `routing/Route` is present. Readers and tests migrated; `RouteTest` absorbs `RouteRuntimeTest` and the `ResolvedRoute` contract cases. **Open Defect 12 resolved**: WebSocket routes no longer get an upstream `HttpClient` and `Guard` they never used (`GatewayEdgeRouteTest.bootsWebSocketProtocol` expects `ClientWiring(0,0,1)`). Advisory residuals: three change notes were superseded by an operator decision during the run (414811), and the PR body lists every test whose assertions changed, with its reason. |
| D3 — carry V02-24's residue | shipped, met with residual | `ApiSheriff-101` now reads "Route '%s' of endpoint '%s' overrides anchor '%s' %s policy at %s scope", naming the endpoint and the scope (`doc/LogMessages.adoc`). The "every run fails" sentence is gone from `doc/development/README.adoc`. Advisory residual: a route-scope `allowed_methods` WARN case cannot exist, because that block has no route scope; this is pinned by a test. The `ApiSheriff-1` template was reworded by operator decision. |
| D4 — architecture documentation | shipped | `doc/architecture.adoc` describes `Route` and the resilience keying (`RouteAssembler.ResilienceShape`: upstream target plus retry setting). A contradicting older sentence was fixed after CodeRabbit flagged it (lesson `2026-10-17-09-001`). No ADR, by design: `PLAN-V02-25` owns it. |

## Metrics and Anomalies

- Tokens: 8,280,385. Wall time: **41h46m** (150,335 s), the longest plan in this epic. Sonar: 0 new issues.
- **The spec's size limit was not kept.** This was not a move-only plan, so the 150,000-character cap
  applied, with a stated fallback: split production and test migration. It shipped as one PR at 552 KB
  instead. CodeRabbit reviewed until its quota ran out after `c385d240`; cuioss-review-bot reviewed the
  final head. Code review was closed by operator override at `f0219ff2`, a doc-only commit. Recorded
  here so the next non-move plan keeps the split rule.
- Execute and finalize friction, all routed to plan-marshall (`api-sheriff-api-sheriff-0-2-0-010` … `-017`):
  - the 5→6 transition guard counted the base commit and orphaned ledger rows after the baseline rebase;
  - outline and plan leaves advanced the phase themselves;
  - task derivation attached whole-module gates to one half of a breaking split;
  - the build-server long-poll flooded the work log (about 1,700 lines);
  - recurrences of the assessment-store and flag-paraphrase classes.

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests: success.
- After the merge, on `main` at `68a652a2`, these all succeeded: Maven Build, Integration Tests, Config
  Validation, Demo Client E2E and Scorecard. The PR-attached benchmark run also succeeded. The six
  Dependabot bumps after it (#477–#482) are green on `main` at `56132a43`.

## Residue and Owners

- **Edge structure for `PLAN-V02-25`.** `edge/RouteAssembler` and its `ResilienceShape` now sit in `edge/`
  next to `GatewayEdgeRoute`. The decomposition settles which sub-package owns them. Recorded in V02-25's spec.
- **`PLAN-V02-23`** still names the deleted types in its surface. It is re-grounded in full after `PLAN-V02-25`.
