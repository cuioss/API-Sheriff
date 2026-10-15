# Landing Analysis: PLAN-V02-26 — Split `ConfigValidator` by Concern (slice 1 of 13)

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #463 (https://github.com/cuioss/API-Sheriff/pull/463)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-14 from the inbox
> messages `plan-v02-26-config-validator-split-001.md` (finding) and `-009.md` (landing), after verifying
> each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `7e5eb579` (#463, merge queue, 17 files, +1,274 / −672).

The spec asked for the whole split in one PR under a 150,000-character cap. The two constraints could not
both hold (the move is ~1 MB of diff), and the operator chose "several PRs" during the run. The plan
re-scoped itself to slice 1, and its functional review measured the 5 deliverables against that slice.
Against the spec as written, the split is about one tenth done.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — group the rules | shipped | 16 concern classes named; the concern map is `doc/development/boot-validation-rules.adoc` (new, 205 lines), with the 13 remaining groups listed in inbox `-001`. |
| D2 — one rule class per concern, `ConfigValidator` orchestrates only | **partial** | `RuleSupport`, `RouteMatchingRules` (342 lines), `TokenValidationRules` (287) extracted; one ordered `DEFAULT_RULES` list, `PortalRules.RULES` dropped. `ConfigValidator` is 2,750 lines (was 3,273); 13 groups remain private. |
| D3 — `validateAnchorAuthFloor` in its own class | **not done** | Still private; it moves with `AnchorRules` (`PLAN-V02-41`). |
| D4 — tests follow the rules | shipped for slice 1 | Four test classes renamed with the rules (`RouteMatchingRulesTest`, `PortalRulesTest`, two `TokenValidationRules*Test`); route-matching cases moved out of `ConfigValidatorTest`. |
| D5 — developer documentation | shipped | `boot-validation-rules.adoc` plus a `doc/development/README.adoc` row. Per review, it names owners and points at `DEFAULT_RULES` instead of restating counts and positions. |

**Realized footprint.** All 17 files are inside the declared surface, plus `DocumentedSetsContractTest`,
one ADR cross-reference and `doc/security-threat-model.adoc` (test-class names). No behaviour change was
claimed or observed.

## Metrics and Anomalies

- Tokens: 7,085,857. Duration: 5h09m wall (18,525 s). The PR measured 139,406 diff bytes against a
  ~100 KB outline estimate, a ratio of **1.39**. The follow-up estimates are re-scaled by that ratio, not
  by the 1.28 that `-001` measured before finalize.
- Anomalies (candidate lessons `-002` … `-008`, routed below):
  - The outline left the assessment store empty again, fifth occurrence of `2026-10-04-09-003`.
  - The pre-push gate derived 0 bundles and had no module-test command; same root as `2026-10-06-08-001`.
  - The learned 525 s timeout clipped `verify -Ppre-commit` under foreign load.
  - The lane router under-read a spec-pointer request and escalated mid-run.
  - The spec's size cap was unsatisfiable. That is an orchestrator defect at spec time.

## Routing and Merge Behavior

- Merged through the merge queue. The merge-group `Maven Build` and `Integration Tests` runs succeeded.
- Post-merge on `main` (`7e5eb579`): Maven Build, Integration Tests, Config Validation, Demo Client E2E
  and Scorecard all succeeded. The PR-attached benchmark run succeeded.
- One ADR was proposed and not created (rules live in concern classes on the `ValidationRule` seam).
  The series' last slice records it.

## Follow-ups

- Operator ruling 2026-10-14: finish the whole split **before** V02-23. The 13 groups are staged as
  slice specs, `PLAN-V02-28` and (re-packed 2026-10-15) `PLAN-V02-40` … `PLAN-V02-43`, and run serially (each one edits `DEFAULT_RULES` and
  `ConfigValidatorTest`).
- `PLAN-V02-27` (test simplification) now depends on the last slice, `PLAN-V02-43`.
