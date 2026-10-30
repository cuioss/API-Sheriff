envelope_version=1
sender_type=plan
sender_id=plan-v02-07-threat-classification
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-30T06:53:36Z

## What landed

plan-v02-07-threat-classification shipped as #537 (merged, merge commit 996f36bd99f0600111bd5ffa20b6d3d8da83d243): per-source threat score over its own SourceLedger with one ECS-shaped WARN ALERT (ApiSheriff-143) per threat episode, action fixed to OBSERVE, ADR-0078.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-07-threat-classification
epic=api-sheriff-0-2-0
pr=#537
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=40417005
total_wall_seconds=94577
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2032
executor_version=0.1.2032
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.functional-review.deliverables_met=4
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=1
step.code-review.rules_cited=1
step.sonar-roundtrip.count_status=confirmed
step.sonar-roundtrip.new_code_issue_count=0
step.finalize-step-sync-baseline.action=noop
```

## Residue

- **automatic-review closed by force-done, not by the gate.** The step was closed by a WARNING-logged force-done at two heads. CodeRabbit really reviewed clean (check-run success, summary coverage at head 5988f533), but its finished-review summary lacks the participation marker (recent_review_start) the gate keys on, so the gate could not credit the review. This is a plan-marshall tooling gap, routed to the plan-marshall lessons-routing inbox as messages 074-076. Sourcery (optional) refused on PR size.
- **Merge under operator barrier-ask-override.** The pre-merge review barrier did not pass on its own; the operator chose "Merge anyway (record reason)" at 2026-10-30T06:07:51Z, bound to HEAD 5988f533 (gap class review-barrier-gap, unproven_bots=coderabbit for the marker reason above). The merge then went through the platform merge queue.
- **adr-propose recorded skipped**: lane off; ADR-0078 was authored in-plan as deliverable 6.
- **Post-merge Performance Benchmark run unobserved.** Workflow run 41014927604 was still in progress when the post-merge CI wait hit its deadline; its result was not observed and is not claimed.
- **Runtime stamp**: `model` is self-reported at plan start (landing-time refresh could not measure it: reason model_unknown); no effort was stamped, so `effort=unknown` is a recorded gap.
- **Functional review**: 2 deliverables met with residuals, 8 advisory findings; code review 1 advisory. Loop-back budgets spent: functional-review 3, automatic-review 3, ci-verify 1.
- A git-ignored local security note exists for this plan.
- `archive-plan` had not yet run when this landing was written.
