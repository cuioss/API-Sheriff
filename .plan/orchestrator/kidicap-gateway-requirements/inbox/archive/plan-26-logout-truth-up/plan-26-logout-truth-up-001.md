envelope_version=1
sender_type=plan
sender_id=plan-26-logout-truth-up
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-10T16:14:10Z

## What landed

plan-26-logout-truth-up shipped as #592 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-26-logout-truth-up
epic=kidicap-gateway-requirements
pr=#592
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=45599308
total_wall_seconds=101249.0
harness=claude
model=claude-sonnet-5-5
effort=low
plugin_version=0.1.2088
executor_version=0.1.2088
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=1
review_body_findings_handled=1
sonar_open_new_issues=0
step.functional-review.deliverables_met=6
step.functional-review.deliverables_met_with_residuals=0
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=4
step.code-review.rules_cited=4
```

## Residue

- The logout revocation WARN landed as `ApiSheriff-147`, not 146. While this plan was open, #589 took `ApiSheriff-146` for the accept-time front-connection refusal; the collision surfaced in the second rebase and the plan's own record, tests, ADR-0080, the BFF-09 threat-model entry and the operator docs were renumbered in one commit. Later plans that quote the logout record should cite 147.
- The merge landed through the platform merge queue as a squash. The queue offers no per-enqueue commit message, and this run did not change the repository's merge-commit-message default; the landed commit carries the PR title plus the platform default body.
- Two rebases onto origin/main were needed because main moved three times during finalize (#590/#591, then #589 with a real `doc/LogMessages.adoc` conflict). Each rebase rewrote the change ledger's commit shas, so later review rounds read every deliverable as `declared_only`; the reviews were run as full rounds on the live tree and all closed with zero blocking findings.
- Advisory items left open on purpose: the test method `LogoutEndpointTest.shouldRevokeNothingWhenTheSessionWasGoneAtDestruction` asserts the resolved refresh token IS revoked (its name says the opposite, and BFF-09 cites it with a history aside); `RpInitiatedLogoutTest` asserts the WARN only through its self-resolved identifier, so a renumber stays green without a test edit; the `BffLogMessages.WARN` Javadoc keeps a hand-written identifier inventory its class Javadoc says is not kept by hand; commit messages 1c96f242, eb3bba06, 49fc12e6 and e68a0e7c (rewritten shas now) over-claim or are stale; the rationale at `RpInitiatedLogout.java:58-59` says no other session-ending path revokes, but `TokenRefreshCoordinator` does.
- The post-merge Performance Benchmark workflow (merge-triggered) was still running when cleanup finished and was not awaited; verifying it is the orchestrator's job.
- Known open gaps found by the reviews, outside this plan's scope: the boot does not check the callback, logout, logout-return and back-channel-logout reserved paths for canonical form, and `step_up_url` echoes the request's own query back to the client.
