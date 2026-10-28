envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-26T22:37:39Z

## What landed

plan-v02-20-cookie-key-file-model shipped as #527 (merged, merge commit 6ec1393).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
pr=#527
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=37069851
total_wall_seconds=38181.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2010
executor_version=0.1.2010
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=4
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
step.code-review.acceptance=operator_override
```

## Residue

- Code review was closed by operator override after non-converging rounds (2 findings accepted). The pre-merge HEAD-binding gate then refused the merge because simplify, security-audit and functional-review were stamped on older heads; the operator chose delta re-checks, which ran clean at 008f7f7 (functional review: full round, 0 blocking, 7 advisory).
- Advisory follow-up candidates: the `OidcClientRules.nameSameFile` Javadoc and `doc/user/offline-config-validation.adoc` overstate that the offline verdict never differs from the boot verdict. For a `..` or linked pair that locates one file, offline validation may pass while the boot refuses. The outline change note and TASK-7 description still name `a/../b` vs `b` as refused offline.
- The merge-queue squash used the repository default (PR title + PR body), so the landed commit body includes the CodeRabbit summary section; the repository squash-message setting was not changed.
- Candidate lessons 008-014 in this epic's inbox concern plan-marshall tooling and await routing to the plan-marshall lessons-routing inbox.
