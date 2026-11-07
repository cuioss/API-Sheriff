# Landing Analysis: PLAN-V02-28 — ConfigValidator Split, LimitRules

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #467 (https://github.com/cuioss/API-Sheriff/pull/467)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-15 from the inbox message
> `plan-v02-28-validator-limit-rules-006.md` (landing). Each material claim was verified against ground
> truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `efb3fa7e` (#467, merge queue). The PR touched 6 files (+871 / −633) with
a 95,729-byte diff.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — extract `LimitRules` (`validateSecurityProfileMinimal`, `validateEdgeHardening`, `validateAuthorizationHeaderValueLength`) | shipped, met with residual | `rule/LimitRules.java` (236 lines). `ConfigValidator` is now 2,588 lines (2,743 before). The minimal refusal is carried verbatim in #465's `PolicyBlock` form. One outline criterion was moot: "`effectiveAuth` exists once, in `RuleSupport`". #465 deleted `effectiveAuth`, `effectiveRequire`, `effectiveProfile` and `RuleSupport.resolveAnchor`. |
| D2 — move the tests | shipped, met with residual | `LimitRulesTest` is new. `ConfigValidatorTest.EndpointScopeRefusals` (line ~4172), added by #465, was left in place. It mixes three endpoint-scope `minimal` refusals, which now exercise `LimitRules`, with forward-block tests. Advisory finding in code review. |
| D3 — references and docs | shipped | Concern map in `boot-validation-rules.adoc` and the `doc/development/README.adoc` row. |

## Metrics and Anomalies

- Tokens: 4,573,408. Wall time: 9h39m (34,741 s). About 6 h of that was waiting on CodeRabbit's org-wide
  quota (06:57–12:44Z). Sonar: 0 new issues. Code review: 0 blocking, 2 advisory.
- #465 landed during the review wait and conflicted in all 5 shared files. The operator chose rebase and
  resolve. This conflict is why the slices now run serially (re-pack, 2026-10-15).
- The automatic-review step spent 3 of 5 loop-back rounds on the quota wait (candidate `-001`).
- The pre-push gate recorded module tests as DEGRADED again. `verify -Ppre-commit` ran 6,134 tests green.
  This is the same root as lesson `2026-10-06-08-001`.

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests: success.
- After the merge, on `main` at `efb3fa7e`, these all succeeded: Maven Build, Integration Tests, Config
  Validation, Demo Client E2E and Scorecard. The PR-attached benchmark run also succeeded.

## Residue and Owners

- **`EndpointScopeRefusals`**: the three `minimal` refusals move to `LimitRulesTest`, and the forward-block
  tests move with `ForwardingRules` → `PLAN-V02-41`. The `ConfigValidatorTest` citation in
  `doc/security-threat-model.adoc` → `PLAN-V02-43`, which already makes every moved test-class name current.
- **`LimitRulesTest` class Javadoc** says every case drives the full validator, but two
  `GetBodyOptInDefault` cases do not. → `PLAN-V02-27`.
- **Stale helper notes in the remaining slice specs** (`effectiveAuth` / `effectiveRequire` no longer exist)
  → corrected in `PLAN-V02-40` and `PLAN-V02-42`. `effectiveProtocol`, `lacksConfiguredIssuer`,
  `prefixContains`, `strictlyContains` and `MEDIA_TYPE_TOKEN` are still private in `ConfigValidator`.
