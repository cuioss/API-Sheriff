envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-27T23:28:11Z

## What landed

plan-v02-09-token-sheriff-integration-fidelity shipped as #530 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
pr=#530
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=21654543
total_wall_seconds=30301
harness=claude
model=claude-sonnet-5-5
effort=low
plugin_version=0.1.2021
executor_version=0.1.2021
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=6
step.functional-review.deliverables_met_with_residuals=0
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=5
step.code-review.rules_cited=4
```

## Residue

- Merged through the merge queue as 0114065c3f9c75cbc85f2f7772e7dec8b5a1ec71. The post-merge CI wait timed out with only the benchmark job ("Run Integration Benchmarks") still running; the Maven Build run for the merge commit on main and that benchmark run still need checking by the orchestrator.
- The squash commit message produced by the queue was not composed or inspected on the merge-queue path.
- adr-propose suggested one further ADR, not created: a failed OIDC provider-metadata resolution is reported as a separate readiness datum read from a non-fetching view and never a DOWN verdict (next free ordinal on a branch cut from origin/main after this merge; 0075 if main has not moved).
- Five code-review advisories were shipped unfixed (fixing them would have re-fired every head-dependent review): the memoize Javadoc in BffRuntimeProducer says the delegate runs at most once although it retries after a failure; the ADR-0027 body was edited by design; a stale comment in DefaultProfileReadinessTest; BffRuntimeProducerTest.theViewIsTheOneTheRuntimeRecordsInto cannot fail for the recording link; the category list in doc/architecture.adoc has no mechanical tie to the library EventCategory now that MetricsIT derives its labels from it.
- The criteria snapshot was frozen before one extra criterion was added to deliverable 2 (javadoc reconcile of GatewayValidator and TokenValidatorProducer); it was graded by exact outline text but is not in the frozen record, and re-freezing needs an operator decision that was not made.
- Issue #174 closes with the PR (Closes #174 in the body); the issue state was not separately verified after the merge.
- Several dispatched leaves recorded their own step with mark-step-done --force (adr-propose, lessons-capture); the reason was not investigated.
- Late review findings (8 CodeRabbit comments) cost a full second settle band, gate build and native integration run; the pre-commit gate rewrote about 400 files of import spacing after every run and the churn was reverted each time.
