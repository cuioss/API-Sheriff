envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-17T23:32:16Z

## What landed

plan-v02-25-edge-route-decomposition shipped as #487 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
pr=#487
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=9825509
total_wall_seconds=32107.0
harness=claude
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=7
step.code-review.rules_cited=5
```

## Residue

- Merge commit on main: `ef4a3345ccabd4bc443586a6cfbe31a188a45c30` (squash via the platform merge queue; landing wait 1302s of the 1800s budget).
- `archive-plan` had not yet run when this landing was written, so it is listed as `pending`; it runs immediately after this step.
- The post-merge `Run Integration Benchmarks` job (workflow `Performance Benchmark`, run 39078174255) was still in progress when the post-merge CI wait hit its 702s deadline. No check had failed. The orchestrator owns verifying that run and the main-branch `Maven Build` run for the merge commit.
- Follow-up recommended, not filed: a pre-existing drain race in `EdgeAdmission` (increment `inFlight` before checking `draining`, undo and refuse when draining) that CodeRabbit surfaced and the plan declined as out of scope for a move-only refactor.
- Integration tests ran only in CI (passed on both pushed heads); they were not run locally.
- Log categories changed: each extracted edge class now logs under its own class name (ADR-0068 records this). Message ids and texts are unchanged.
- Three review-fix commits were comment/doc-only and landed after the reviews examined an earlier HEAD (bf51fe4f, 026e65e1, f6f3a7e8); the pre-push gate re-ran on the final tree.
- Pending info-level finding 174a5a (anti-pattern) was left open as non-blocking.
