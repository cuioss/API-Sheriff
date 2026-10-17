# Landing Analysis: PLAN-V02-40 — ConfigValidator Split: TerminalActionRules, EdgeRules, ProtocolRules

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #485 (https://github.com/cuioss/API-Sheriff/pull/485)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-17 from the inbox message
> `plan-v02-40-validator-terminal-edge-protocol-rules-009.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `6f0169f6` (#485, merge queue). The PR changed 21 files (+1,967 / −1,361) with a
210,973-byte diff. That is inside the move-only size rule: 100 files, ~350 KB.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Extract the three groups | shipped | `rule/TerminalActionRules`, `rule/EdgeRules` and `rule/ProtocolRules` are present on `main`. `effectiveProtocol` now lives in `RuleSupport` and is used by all three. `ConfigValidator` is down to 1,952 lines. |
| Move the tests | shipped | `TerminalActionRulesTest`, `EdgeRulesTest` and `ProtocolRulesTest` are present. `ConfigValidatorTest` keeps the groups owned by later slices. |
| References and docs | shipped | `doc/security-threat-model.adoc:1478` cites `ProtocolRulesTest.WebSocketOriginAllowlist`. The outline Q-Gate added GW-09/GW-13, the `WebSocketProxyIT` Javadoc and the parallel helper lists to the scope. |
| Functional review | 5/5 met | No residuals, no criteria left unevidenced. Code review: 0 blocking, 4 advisory. |

## Metrics and Anomalies

- Tokens: 5,138,366. Wall time: 45h51m (165,053 s). About 1h43m of it was the finalize sync-baseline, which rebased onto 10
  upstream commits (including #476) and invalidated every execute-phase build verdict.
- Execute and finalize friction, all routed to plan-marshall as `api-sheriff-api-sheriff-0-2-0-018` … `-025`.
  Four of these are recurrences and were sent as findings:
  - 019: no CERTAIN_INCLUDE assessments on the inline path;
  - 022: `derive_gate_bundles` resolves zero bundles in a Maven project;
  - 023: executor version skew, where `marshal.json` records 0.1.1916 but the workflows are 0.1.1924;
  - 024: the build-server long-poll floods the log, for the second plan running.
- The project side of 023: the executor is stale against the installed workflows. This is the `/marshall-steward`
  run the operator already owes. It should happen before V02-25 starts.

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests passed.
- After the merge, on `main` at `6f0169f6`, all of these passed: Maven Build, Integration Tests, Config Validation, Demo Client E2E and Scorecard.
  The plan had not checked the main-branch run. Checked here: green.

## Residue and Owners

- **Public rule methods without `@param` tags** (advisory). Carried into `PLAN-V02-43`, the end of the split.
- **`doc/quality-report/code-correctness.adoc:105`** still cites `ConfigValidatorTest.WebSocketOriginAllowlist`. Left
  as it is: the series rule keeps `doc/quality-report/` untouched.
- **`doc/security-threat-model.adoc:303`**, which the message suspected was stale: **refuted**. The endpoint-level
  `minimal` refusals it cites (`shouldRefuseEndpointMinimalOnAuthenticatedRoute` / `…OnBffRoute`) are still in
  `ConfigValidatorTest`. They move with the slice that owns them, and `PLAN-V02-43` makes the citation current.
- **Consumer-sweep gap**: the outline's sweep missed prose citations. The project side is now a series rule in `PLAN-V02-41` … `-43`.
- **Inventory gap** (message 003): `integration-tests/src/main/docker/**` resolves to no module, which makes the
  module-mapping validator report a false positive. The tool side is routed (020). The project side is to attribute the path in the
  architecture inventory on the next `/marshall-steward` run.
