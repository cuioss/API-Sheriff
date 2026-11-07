envelope_version=1
sender_type=plan
sender_id=plan-v02-48-adr-corpus-cleanup-part-2
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-30T16:40:15Z

## What landed

plan-v02-48-adr-corpus-cleanup-part-2 shipped as #544 (merged, 6f06e7b2): 50 ADRs rewritten to the skeleton, corpus 17,328 to 13,123 lines.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-48-adr-corpus-cleanup-part-2
epic=api-sheriff-0-2-0
pr=#544
merge_state=merged
cleanup_owed=false
deliverables_total=12
deliverables_done=12
total_tokens=83325035
total_wall_seconds=32245.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2032
executor_version=0.1.2032
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:done
step.functional-review.deliverables_met=9
step.functional-review.deliverables_met_with_residuals=3
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
```

## Residue

- Part 1 of two serial PRs, by operator ruling. A second plan, started after this one, owns the 0022 relocation to `doc/technical_aspects.adoc`, the 0030 relocation to `doc/development/test-corpus-integrity.adoc`, the new `oidc_discovery` ADR, and the A1/A2 citation rewrite with removal of the hidden anchors.
- Code review was closed by operator override with partial coverage: 40 ADRs were not read by it, and the final commit was not code-reviewed. The PR body lists them. `code-review` records `acceptance=operator_override`.
- The merge gate refused the operator's re-fire waiver for simplify and functional review, so both re-ran at the final head on the changes since their last pass; only a factual error could block, and none was found.
- Open with no owner yet: the APISIX `bearer` lane cannot validate tokens, so `run-comparison.sh --target both` stops there.
- Known wording left in place: `0007` line 118, `0008` line 90, `0070` lines 133 and 145, `benchmarks/README.adoc` lines 62-66, `deployment/compose-sample/.env` lines 15-16.
- The kidicap PLAN-29 line references into 0008 and 0064 are stale; the operator has the hand-off text.
- Security notes were recorded locally.
- The landed commit message is the PR body: the queue offers no per-enqueue message, and the repository default was left unchanged.
