envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-09T13:08:26Z

## What landed

plan-v02-01-adr-0005-reversal-quarkus-adoption shipped as #409 (merged), together with #410, #412, #415 and #417.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
pr=#409
merge_state=merged
cleanup_owed=false
deliverables_total=9
deliverables_done=9
total_tokens=54284519
total_wall_seconds=191082.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=13
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- **The change landed in five squash commits, not one.** The plan's PR #409 held 164 files and both review bots refused it on size. It was split into three sequential PRs against `main`: #410 (`1591972`, ADR-0062 and the substitutions), #412 (`862d574`, session management) and #409 (`9f9eeae`, documentation and review fixes). Two follow-ups belong to the same plan: #415 (`b3185ce`, the formatter's import-group spacing, 282 files, merged without a CodeRabbit review because of its size) and #417 (`386f3f7`, the thirteen Sonar findings). `pr` above names the plan's own PR only.
- **One behaviour change was added during review and is not in the plan's deliverables.** The cookie-mode activity cookie is authenticated with HMAC-SHA-256 and is no longer encrypted; its value is 56 characters and its format version is 2. ADR-0018 records the reason. A second one is in the deliverables in a different form than planned: every back-channel logout rejection reason is recorded at WARN once per emitter, and ADR-0051 is amended accordingly.
- **The finalize was closed by the operator.** It stopped at the automatic-review step when the PR was split. The review comments of all five PRs were answered and their threads resolved on the PRs themselves, outside the findings store. `adr-propose` was recorded without a dispatch, because every decision of the plan is already in the ADR corpus.
- **No realized footprint was captured.** At cleanup the worktree had a follow-up branch checked out. `merge_commit_sha` in the plan's references is `9f9eeae` (#409); the full footprint is the union of the five commits above.
- **The Sonar figure is the count at the first scan.** All thirteen findings were fixed in #417, and the scan of that PR reports none.
- **Eighteen candidate-lesson messages precede this landing.** One quality-check record was left out of them, because it describes a residual that was handed to the session audit and not fixed in this plan.
- **Not done here, for the epic to place:** the integration-test job ran within a minute of its 35-minute timeout on this plan's PRs; the user reported that a parallel issue raises the timeout. `marshal.json` was reported stale against the regenerated executor and still needs a `/marshall-steward` run.
