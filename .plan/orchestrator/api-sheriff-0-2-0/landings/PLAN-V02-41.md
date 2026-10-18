# Landing Analysis: PLAN-V02-41 — ConfigValidator Split: AssetDefaultsRules, ForwardingRules, AnchorRules

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #489 (https://github.com/cuioss/API-Sheriff/pull/489)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-18 from the inbox message
> `plan-v02-41-validator-asset-forwarding-anchor-rules-004.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `3a252fca` (#489, merge queue). The PR changed 20 files (+2,413 / −1,795) with a
275,878-byte diff, inside the move-only size rule. Sourcery refused it for size; Sourcery is optional and does not gate.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Extract the three groups | shipped | `rule/AssetDefaultsRules`, `rule/ForwardingRules` and `rule/AnchorRules` are present on `main`. `ConfigValidator` is down to 1,372 lines. |
| Auth floor derived from the declaration | shipped | `AnchorRules` reads the floor through `PolicyBlock.AUTH`, and #489 amends ADR-0067. The refusal message is unchanged. |
| Move the tests, including `EndpointScopeRefusals` | shipped | The endpoint-scope `minimal` refusals are now `LimitRulesTest.EndpointScopeMinimal`; the forward-block tests are `ForwardingRulesTest.EndpointScopeForward`. `doc/security-threat-model.adoc:303` now cites `LimitRulesTest` and `ForwardingRulesTest`, which closes V02-40's open citation. |
| Docs | shipped | Functional review met 4 of 4 with no residuals. Code review: 0 blocking, 0 advisory; two advisories from an earlier round were fixed in a docs-only commit. |

## Metrics and Anomalies

- Tokens: 5,955,484. Wall time: 5h33m (19,975 s).
- SonarCloud counted a verbatim-moved method as new code, because it lives in a new file. It raised S3776/S135, which
  were fixed in `1e80f991`. The one-line fix sent the whole finalize review chain round again, about 1.2M tokens.
  - Project side: lesson `2026-10-18-13-001`, written into the series rules of `PLAN-V02-42` and `-43`.
  - Tooling side: routed to plan-marshall as `api-sheriff-api-sheriff-0-2-0-034` (undeclared `verdict_inputs`).
- The outline labelled a deliverable `documentation_only` although it edited `integration-tests/src/main/docker/**`
  gateway.yaml comments, which are build inputs. No effect in this run. Routed as `-033`, linked to `-020`, the gap
  where those paths resolve to no module.

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests passed.
- After the merge, on `main` at `3a252fca`, all of these passed: Maven Build, Integration Tests, Config Validation, Demo Client E2E and Scorecard.
  The PR-attached Run Integration Benchmarks job also passed.

## Residue and Owners

- None owed by code. `PLAN-V02-42` and `-43` carry the new Sonar series rule.
