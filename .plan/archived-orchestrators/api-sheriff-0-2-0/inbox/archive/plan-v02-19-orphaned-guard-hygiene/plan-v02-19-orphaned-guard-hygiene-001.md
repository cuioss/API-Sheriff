envelope_version=1
sender_type=plan
sender_id=plan-v02-19-orphaned-guard-hygiene
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-01T11:26:06Z

## What landed

plan-v02-19-orphaned-guard-hygiene shipped as #367 (merged, squash commit 6bb9076562b3a680060bfc26180362ed4b5727cd on main).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-19-orphaned-guard-hygiene
epic=api-sheriff-0-2-0
pr=#367
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=5002915
total_wall_seconds=19519.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.finalize-step-sync-baseline.action=noop
step.finalize-step-sync-baseline.upstream_commit_count=0
step.create-pr.pr_number=367
step.sonar-roundtrip.count_status=confirmed
step.sonar-roundtrip.new_code_issue_count=0
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.record-metrics.total_tokens=5002915
step.record-metrics.total_wall_seconds=19519.0
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- The header-matcher decision record now carries ordinal 0056 (0055 was already taken on main); the portal record keeps 0053. Later plans citing the header-matcher record must use 0056.
- One loop-back happened during finalize. The first PR head (534f449a) failed the Sonar quality gate on java:S9398 (a class nested in an interface, pre-existing code counted as new because the file was touched). It was fixed by moving UpstreamTimeoutException out of the UpstreamFetcher interface into UpstreamAssetSource (commit ffca5593). The exception's qualified name changed from UpstreamAssetSource.UpstreamFetcher.UpstreamTimeoutException to UpstreamAssetSource.UpstreamTimeoutException.
- Tooling gap: ci-verify filed red-CI findings under producer ci-verify-policy, which the verification-feedback workflow rejected as unknown_producer. The orchestrator diagnosed the failure from the job log and the Sonar gate directly and resolved those five findings as taken_into_account.
- Tooling gap: the pre-push gate derived no gate bundles for Maven paths and the whole-tree module-tests canonical did not resolve at the reactor root; a full verify was run instead and the gate row reads DEGRADED for that reason.
- Tooling gap: manage-status transition's own mailbox probe reported the plan as not orchestrated while orchestrator inbox detect reported orchestrated; the latter was followed.
- cuioss-review-bot does not re-review on push and re_review_on_loopback is false, so a /review comment was posted by hand after the fix commit to obtain a review of the final head.
- CodeRabbit's one inline suggestion (derive the AGENTS.md / CLAUDE.md module lists from the POM instead of testing them) was declined with a reply on the thread and the thread resolved; it contradicts the plan's stated design.
- Post-merge verification is still owed by the orchestrator: the PR-attached "Run Integration Benchmarks" run (36853680718) was still in progress when this plan stopped waiting, and the Maven Build run for merge commit 6bb90765 on main (including deploy-snapshot) was not checked by this plan.
- Not closed by this plan, by design: both new contract tests run only when a Maven build runs, so a documentation-only change can still land a duplicate ordinal or a drifted module list until the next build-triggering change.
