envelope_version=1
sender_type=plan
sender_id=plan-29-upstream-inert-keys
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-09T13:06:30Z

## What landed

plan-29-upstream-inert-keys shipped as #586 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-29-upstream-inert-keys
epic=kidicap-gateway-requirements
pr=#586
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=15582782
total_wall_seconds=13744.0
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.2088
executor_version=0.1.2088
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=0
review_body_findings_handled=0
sonar_open_new_issues=0
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=0
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=1
step.code-review.rules_cited=1
```

## Residue

- The `steps` list names the eighteen finalize steps settled when this message was written; `emit-landing` (this message) and `archive-plan` follow it and are not listed.
- Merge: PR #586 landed through the platform merge queue as squash commit ab568dab. The merge step could not set the squash commit message (the CI abstraction has no setter), so the landed message comes from the repository default and may carry the CodeRabbit summary; a PR comment records the intended message.
- Post-merge CI was still running when the merge step's wait ended (Performance Benchmark run 42620028579, no check failed). The orchestrator still owes a look at that run and at the main-branch Maven Build run for ab568dab.
- Review: coderabbit (required) participated with no actionable comments; sourcery (optional) refused on its weekly quota. The three open items `pr comments --unresolved-only` lists are plain PR comments with no review thread (two bot notices and one run note), so `unresolved_review_threads` is 0.
- Operator decisions on record: the removed test ConfigValidatorTest.shouldAcceptMillisecondPrecisionTimeout was accepted at the functional review; ADR-0008 was edited after the code review found it stale (loop-back round 1, criterion D3.C6 reworded under criteria-authorize); two docs outside the request's list were included (finding 04c35d).
- `effort=medium` is the stamp's self-reported value, not a measurement; it could not be refreshed (refresh-runtime-stamp: model_unknown).
- The metrics accumulator `samples` counter for 6-finalize is 2 high: an accumulate call with invented figures was issued in error and reversed with negated values (totals are correct; see the plan's decision log).
- The pre-commit quality gate (`verify -Ppre-commit`) deleted one blank line in 448 api-sheriff/src files on every run, which had to be reverted each time; it is not recorded as a lesson message.
