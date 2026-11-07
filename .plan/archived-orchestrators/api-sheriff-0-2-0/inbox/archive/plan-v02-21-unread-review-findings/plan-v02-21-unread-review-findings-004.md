envelope_version=1
sender_type=plan
sender_id=plan-v02-21-unread-review-findings
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-13T14:16:18Z

## What landed

plan-v02-21-unread-review-findings shipped as #439 (merged via the merge queue as 48e8d5a6907960a31c93db6d594b6973882f4f91).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-21-unread-review-findings
epic=api-sheriff-0-2-0
pr=#439
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=3698752
total_wall_seconds=15990.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:pending,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
step.pre-submission-self-review.blocking_count=0
```

## Residue

- Spec D4 (finding 6, ImageMetadataIT image name) was DECLINED by the operator at refine: the integration-tests POM failsafe excludes already keep each lane off the other image. Shipped deliverables are spec D1, D2, D3 and D5 (outline D1-D4).
- demo-client/scripts/start-dev-environment.sh still uses a literal 120 for its Keycloak wait, contrary to the amended ADR-0031; recorded in the ADR scope note, not edited. Candidate follow-up.
- ADR-0031 still says the integration stack runs "seven gateway instances"; there are twelve. Not edited (out of scope). Candidate follow-up.
- Local `verify -Pintegration-tests` failed twice on Http2EarlyResponseResetIT (20s batch timeout) while CI's integration-tests job was green on the same HEAD 1eb3386 — a local-environment flake of that test.
- The post-merge "Run Integration Benchmarks" job (Performance Benchmark run 38409700336) was still in progress when finalize's post-merge CI wait timed out; no failure observed. Post-merge verification belongs to the orchestrator.
- Workflow observations not admitted as candidate-lessons: phase-3/4 leaves self-transitioned their phase (outline was re-opened by hand to address 3 q-gate findings; metrics boundaries needed reconciliation); change-type-heuristic classified this request as `analysis` (overridden to tech_debt); pre-push derive_gate_bundles resolves no bundles in this Maven repo and module-tests has no default-scope canonical (gate recorded module-tests DEGRADED).
