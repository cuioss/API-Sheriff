envelope_version=1
sender_type=plan
sender_id=plan-v02-44-route-type-merge
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-17T09:29:56Z

## What landed

plan-v02-44-route-type-merge shipped as #476 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-44-route-type-merge
epic=api-sheriff-0-2-0
pr=#476
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=8280385
total_wall_seconds=150335.0
harness=claude
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- code-review was closed by operator override at f0219ff2 (a doc-only commit answering CodeRabbit fcef97). It carries no finding counts. The c385d240 advisory fixes passed the quality gate and 5797 tests.
- CodeRabbit ran out of quota after c385d240; cuioss-review-bot reviewed the final head f0219ff2.
- adr-propose was skipped by design: the ADR for the route-type merge and the edge package boundaries belongs to PLAN-V02-25.
