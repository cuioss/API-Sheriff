envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-24T15:14:02Z

## What landed

plan-v02-23-cookie-forwarding-policy-r2 shipped as #515 (merged via the merge queue as bd730f5a).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
pr=#515
merge_state=merged
cleanup_owed=false
deliverables_total=8
deliverables_done=8
total_tokens=11621535
total_wall_seconds=183722
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=7
step.functional-review.deliverables_met_with_residuals=1
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=7
step.code-review.rules_cited=7
```

## Residue

- Post-merge verification is owed by the orchestrator: the PR-attached post-merge CI wait hit its deadline (deadline_exceeded) after the merge, and the main-branch Maven Build run for bd730f5a (deploy-snapshot) was not observed by the plan.
- Three operator-approved fix rounds landed late in finalize (bare nameless gateway cookie, activity-cookie reservation in both session modes, CodeRabbit doc wording); ADR-0037 already covers them, and the security audit recorded one design follow-up only in the git-ignored local security notes for the operator.
- The 7 code-review and 15 functional-review advisories (stale ApiSheriff-123 commit messages, http package-info reader list, RouteTableBuilder ApiSheriff-101 on terminal routes, setCookieName @return wording, quality-report BFF-2 heading, the outline's D2 change note) were not fixed and are candidates for a cleanup pass.
- CodeRabbit's rate limit blocked review of the last three commits until a manual re-trigger after the hourly window; Sourcery refused on diff size throughout.
- 15 candidate-lesson messages (plan-v02-23-cookie-forwarding-policy-r2-001..015) await the epic drain; the plan-marshall tooling ones belong in plan-marshall's lessons-routing inbox.
