envelope_version=1
sender_type=plan
sender_id=plan-v02-15-bff-compose-sample
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-24T19:48:37Z

## What landed

plan-v02-15-bff-compose-sample shipped as #516 (merged, 053e3d51d679f0522983e8c07b701f79a0f1903b); issue #176 closed with a comment naming the PR and the merge commit.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-15-bff-compose-sample
epic=api-sheriff-0-2-0
pr=#516
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=7397584
total_wall_seconds=170491.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1986
executor_version=0.1.1986
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=5
step.functional-review.deliverables_met_with_residuals=1
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.branch-cleanup.merge_mechanism=merge_queue
```

## Residue

- **Release owed.** The sample's `.env` still pins `ghcr.io/cuioss/api-sheriff:0.2.4`, which predates the BFF keys the sample now sets. The default sample needs the release after 0.2.4; until then it runs only against a local build through the `API_SHERIFF_IMAGE` override. Not cutting a release was an operator decision for this plan.
- **Overlap with V02-23 accepted at init** (operator decision); check that plan's spec against the sample this plan landed.
- **CI scope.** CI proves the base (HTTPS) variant's login against the PR-built image; the plain-HTTP variant was proven locally only.
- **Security.** One security note was recorded locally during this plan.
- **Review-bot state.** Sourcery's optional check failed on quota for the whole run; CodeRabbit (required) reviewed every head, the last one with no new comments.
- **Operator closures during finalize.** The code-review loop was operator-closed at 9ed3d6bf, with re-fire waivers for simplify, the security audit, the functional review and the pre-push gate covering the later small CodeRabbit-driven fixes (8c921bbd..074b7d22), which were gated by `verify -Ppre-commit` plus full `verify` locally and by CI.
- **Candidate lesson (plan-marshall, not filed: this plan's write boundary allows one inbox message).** At the pre-merge barrier, `github_pr fetch_findings` re-filed an already-handled CodeRabbit review-summary body from an earlier round (492b9888) as a new pending `pr-comment` finding stamped with the current HEAD, so it had to be resolved by hand before the merge gate. The dedup keyed on the comment did not recognise it as already handled once the review body was re-read at a new HEAD.
- **Candidate lesson (plan-marshall).** After the worktree is removed, `ci --plan-id` calls fail with "Not authenticated"; `--project-dir <main checkout>` works. Already a known pattern locally; it recurred here at the issue-comment step.
