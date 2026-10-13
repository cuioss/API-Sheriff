envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-12T21:10:43Z

## What landed

integration-test-evidence-follow-ups shipped as #433 (merged, squash via merge queue, merge commit 128fd07e892f3f1b0981a027eb4e30c820eefc8d).

```landing-facts
schema=landing-facts/1
plan_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
pr=#433
merge_state=merged
cleanup_owed=false
deliverables_total=9
deliverables_done=9
total_tokens=5811909
total_wall_seconds=16621.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:done
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- DP-2 remainder (`connect_timeout_ms`, `read_timeout_ms`, `retry.max_attempts`, `retry.idempotent_only` parse but are read by nothing) was NOT fixed here; filed as `integration-test-evidence-follow-ups-001.md` in this inbox. `doc/configuration.adoc` and `doc/user/endpoint-routes.adoc` now mark them inert.
- Decisions recorded: per-route `circuit_breaker` block removed entirely (ADR-0064, no runtime change); `deployment/compose-sample` nginx stays on the minor-line tag `nginx:1.27-alpine` as a documented exclusion, digest pin limited to `integration-tests/**`.
- Accepted-but-untracked follow-ups: no guard test that all nginx digest pins stay equal (CodeRabbit nitpick); toxiproxy / go-httpbin / prometheus images in the IT compose stack remain tag-pinned (finding 0c6baa, predates this plan).
- Post-merge benchmark run (38307102339) was still in progress when branch-cleanup's CI wait timed out (747 s); the main-branch Maven Build run for 128fd07e (incl. deploy-snapshot) is owed an orchestrator-side check.
- Cost: CodeRabbit hourly quota was exhausted (review refused once); each doc-only review-triage commit re-fired every head-dependent finalize step (see candidate-lesson -008).
