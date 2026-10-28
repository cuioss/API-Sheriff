# Landing Analysis: PLAN-V02-09 — Token Sheriff Integration Fidelity

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #530 (https://github.com/cuioss/API-Sheriff/pull/530)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-28 from the inbox messages
> `plan-v02-09-token-sheriff-integration-fidelity-001` to `-006` (candidate lessons) and `-007` (the landing). Checked
> against `origin/main` at `0114065c`, the merge commit's diff, the issue state and the CI runs. The plan ran once under
> its own plan id.

## Deliverable Fidelity vs Spec

#530 changed 25 files (+1,891 / −351): well under the 100-file review-bot cap. Six are production files under
`api-sheriff/src/main` (one new, `bff/runtime/DiscoveryStatus`); `pom.xml` changed for the BOM re-measurement.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — what the extension's beans do at 0.9.8 | shipped | ADR-0074 reports it from the shipped artifact: the observability beans no longer read the issuer namespace themselves; `observability.ObservedValidatorResolver` picks the validator, skips the extension's own producer and, with exactly one other handle, observes that one (`EXTERNAL_VALIDATOR`). The spec's account that the health beans "were never registered before 0.9.7" was corrected (see the lessons). |
| D2 — decide the config-mapping question | shipped, a hybrid | The metrics collector is kept and now observes the gateway's `@GatewayValidator` validator. The health probes stay excluded: `application.properties` now reads `quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*` (the metrics package left the exclusion). No projection onto `sheriff.token.issuers.*` was built. ADR-0074 is `Accepted` and supersedes ADR-0027, which now reads `Superseded by ADR-0074`; the `#641` and `#617` references are in it. |
| D3 — close the two observability gaps | shipped | JWT validation metrics are published (`MetricsIT` derives its labels from the library's `EventCategory`; `SheriffMetricsTest`). Discovery reachability: `GatewayReadinessCheck` reports an `oidc_discovery` datum from `bff/runtime/DiscoveryStatus`, fed by every attempt of the lazy `BffRuntimeProducer` supplier and never changing the verdict, so boot still does not depend on the IdP. Issue #174 is `CLOSED` (verified with `gh issue view`). |
| D4 — reconcile every carrier | shipped | The rationale block in `application.properties`, `ExtensionUnqualifiedBeanExclusionTest`, `DefaultProfileReadinessTest`, ADR-0027 and the docs (`doc/configuration.adoc`, `doc/architecture.adoc`, `doc/security-threat-model.adoc`, `doc/user/environment-variable-overrides.adoc`, `doc/development/integration-test-topology.adoc`) match. ADR-0032, ADR-0041 and ADR-0050 were amended in place because they restated the superseded text. |
| D5 — re-test the two upstream assumptions | shipped | Reported in ADR-0074 and the `pom.xml` BOM comment, which was re-measured at token-sheriff 0.9.8 / Quarkus 3.40.1 (the comment block had last been measured at 0.9.5). |

The plan's own count is six deliverables (one more than the spec): the javadoc reconcile of `GatewayValidator` and
`TokenValidatorProducer` was added to D2 after the criteria were frozen. The functional review ended with 6 met, 0 with
residuals, 0 not met. Code review: 0 blocking, 5 advisory (shipped unfixed, see below).

## Metrics and Anomalies

- Tokens: 21,654,543. Wall time: 30,301 s (8.4 h) for 6 deliverables. Model claude-sonnet-5-5, effort low. Plugin and
  executor 0.1.2021. The cheapest plan of the epic so far.
- A late CodeRabbit round (8 comments) produced 5 fix tasks, moved HEAD five times and re-ran the four HEAD-bound review
  steps, a second settle band, a gate build and a native integration run. The plan then shipped five code-review
  advisories unfixed to avoid another re-fire. A tooling finding (third in a row); routed.
- The pre-commit gate rewrote about 400 files (import spacing) on every run and the churn was reverted each time; see
  the residue.
- The criteria snapshot was frozen before one criterion was added to D2; re-freezing needed an operator decision that was
  not made. The criterion was graded on its outline text. Nothing to correct in the ledger.
- Several dispatched leaves marked their own step with `mark-step-done --force` (adr-propose, lessons-capture); the plan
  did not investigate. A tooling finding; routed.
- No security note is mentioned by the plan.

## Routing and Merge Behavior

- Merged through the merge queue as `0114065c` at 2026-10-27T23:08:25Z. The plan's post-merge wait timed out with only the
  benchmark running; the orchestrator checked.
- Post-merge on `main` at `0114065c`, all completed with success: Maven Build (40600013939, including the
  `deploy-snapshot` job), Integration Tests (40600013900), Config Validation, Demo Client E2E and Scorecard. The
  PR-attached Performance Benchmark run (40600015906, head `d6feb923`) passed.
- Effect on the ADR corpus: 68 records on `main` (67 plus ADR-0074); ADR-0027 is `Superseded`; ADR-0032, ADR-0041 and
  ADR-0050 amended in place.

## Residue and Owners

- **Pre-commit gate churn.** The project memory records the import-spacing churn as fixed by #415. This plan saw about 400
  files rewritten on every `verify -Ppre-commit` run, so either the fix regressed or it does not cover this file set.
  The same candidate reports OpenRewrite adding a `TODO` marker to a catch block that records the error and rethrows it.
  **Not verified by the orchestrator** (it needs a build). Open: the operator decides whether to stage a check.
- **Five code-review advisories, shipped unfixed** (plan-reported): the `BffRuntimeProducer` `memoize` Javadoc, a stale
  comment in `DefaultProfileReadinessTest`, `BffRuntimeProducerTest.theViewIsTheOneTheRuntimeRecordsInto` (cannot fail for
  the recording link), the `doc/architecture.adoc` metric-category list (no mechanical tie to the library's
  `EventCategory`), and the ADR-0027 body edit, which was by design. Owners: `PLAN-V02-48` D12 (the Javadoc, the comment,
  the category list) and `PLAN-V02-49` D3 (the test).
- **An ADR no record holds.** A failed OIDC provider-metadata resolution is reported as a separate readiness datum,
  never a `DOWN` verdict. The behaviour is in `doc/architecture.adoc` and `doc/configuration.adoc`. Owner:
  `PLAN-V02-48` D12 (one record; first to drop at the cut line).
- **ADR-0027 is out of `PLAN-V02-48`'s way:** it is `Superseded`; the plan's spec was updated.
- **Constraint lifted:** `PLAN-V02-09` no longer excludes any plan. `PLAN-V02-12`'s held D3 is still unstaged.
- **Candidate lessons.** `-004` (spec claims about a dependency's history and about a guard test), `-006` (an exact
  key-set assertion for a retired key) and `-005` (superseding an ADR leaves other records on the old text, folded into
  2026-10-27-17-003) are project lessons: 2026-10-28-05-001, -002 and the update of 2026-10-27-17-003. `-001`
  (late bot round re-fires every verdict), `-003` (pr-comment promotion truncates a long body) and the forced step marks
  are plan-marshall tooling; routed to `lessons-routing` as two findings. `-002` is the gate churn above.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-09 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-09 --field pr --value 530`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-09 --field landing --value landings/PLAN-V02-09.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] `PLAN-V02-48` spec: 68 records, `0027` superseded, three more amended ADRs, D12 extended with three statements and one record
- [x] `PLAN-V02-49` spec: D3 gains the weak `BffRuntimeProducerTest` test
- [x] `epic.md` narrative reconciled
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Free slots: two of three (only `PLAN-V02-50` runs).
