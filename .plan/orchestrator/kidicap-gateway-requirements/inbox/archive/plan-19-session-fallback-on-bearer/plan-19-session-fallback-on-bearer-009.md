envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=landing
created=2026-09-24T21:13:06Z

## What landed

plan-19-session-fallback-on-bearer shipped as #356 (merged, squash commit 1fa648da3cb04fd197a47e82148a1ee535a427aa via the merge queue).

```landing-facts
schema=landing-facts/1
plan_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
pr=#356
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=7155269
total_wall_seconds=42153.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
step.branch-cleanup.merge_mechanism=merge_queue
step.create-pr.pr_number=356
step.sonar-roundtrip.new_code_issue_count=0
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- Operator scope decisions: the branch metric `sheriff_auth_branch_total{route,branch}` is recorded only on `session_fallback` routes; the access-log half of deliverable 5 was dropped (no access log is enabled) and handed over as inbox finding `plan-19-session-fallback-on-bearer-001`.
- The SESSION branch of a `session_fallback` route runs no scope check, the same as every `require: session` route. The caveat is documented in `doc/configuration.adoc`, `doc/user/bff-session.adoc` and threat-model BFF-16. Routed to PLAN-23 as inbox finding `-002`: PLAN-23 should add a `session_fallback` acceptance case (the IT route `bff-session-fallback` in `bff-scoped.yaml` is a ready fixture), remove the caveat when it lands, and re-ground against this merge, because PLAN-23 was staged to run first.
- Loop-backs: 2. Round 1 fixed 4 CodeRabbit doc/Javadoc findings and 3 Sonar findings (fix tasks 14 and 15). Round 2 is where the automatic-review participation classifier misreported cuioss-review-bot as `participated_stale` (lesson 2026-09-24-18-001, global store). The orchestrator reconciled it from provider evidence: the PR-Agent guide was edited in place to "Review updated until 2a7c68a", and CodeRabbit replied "Already reviewed the last commit".
- The pre-merge ADR duplicate-number gate was unread: the installed executor 0.1.1719 `manage-adr scan` emits no `duplicate_count`. The operator authorized the merge after a manual check. The only duplicate is the pre-existing ADR-0053 pair (the renumber is owned by PLAN-20); ADR-0055 is unique.
- The earlier phase-transition mailbox probes reported `not_orchestrated` because they ran with the worktree as cwd, where `.plan/local/orchestrator` is absent. `inbox detect` on the stored source_id resolves `orchestrated`.
- `realized_footprint` (55 paths) was captured against stale local `main`, so it includes upstream commits. `merge_commit_sha` 1fa648d is the exact source for the path set (36 files).
- Candidate lessons were sent as inbox messages 003 to 008.
