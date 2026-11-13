envelope_version=1
sender_type=plan
sender_id=plan-sec-06-tier-2-quick-wins
epic=security-hardening-tier-1
kind=landing
created=2026-11-13T11:46:26Z

## What landed

plan-sec-06-tier-2-quick-wins shipped as #607 (merged through the platform merge queue as 898e0100).

```landing-facts
schema=landing-facts/1
plan_id=plan-sec-06-tier-2-quick-wins
epic=security-hardening-tier-1
pr=#607
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=48983061
total_wall_seconds=101210.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2106
executor_version=0.1.2106
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
step.functional-review.deliverables_met=1
step.functional-review.deliverables_met_with_residuals=6
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=6
step.code-review.rules_cited=6
```

## Residue

Narrative items the epic may want to track; none of them blocked the merge.

- The branch was rebased once at branch-cleanup (operator-authorized at the content-conflict gate: main had gained a formatter-only commit, a flaky-test fix and a ledger commit; one import-block conflict in `ShippedApplicationPropertiesTest`). The step record shows no rebase fact because `record-merge` refuses an action fact on the merge-queue route; the decision log carries the real sequence (rebase, force-push with lease, `pr safe-merge` refused because `main` requires the merge queue, then queue landing). Every head-dependent finalize step was re-fired after the rebase and after two later review-fix commits.
- Open code-review advisories left for a follow-up: no test pins that the WebSocket Authorization-origin boot rule leaves non-WebSocket and non-proxy routes alone (`ForwardingRules`); the threat-model GW-09 entry and summary row do not name the new refusal; `doc/architecture/observability-and-errors.adoc` and `doc/user/container-image.adoc` still call HTTPS the management default; the ApiSheriff-115 remedy text in `ConfigLogMessages` does not mention supplying key material; `RetryingJwksLoaderTest.refreshHoldingAnExpiredRetiredKeyIdIsPublished` asserts the retired delegate's close without waiting for it (use `awaitClosed(0)` as the sibling test does).
- The local `verify -Ppre-commit` formatter disagrees with main's current style (main's formatter commit removed a blank line between import groups in about 440 files; the local formatter re-adds it in 451 files). The plan reverted that churn each time; it needs its own change to settle which style is canonical.
- The live compose bring-up of the narrowed `/auth`-fronted sample was never run (no Docker daemon); the native integration tests ran only in CI.
- Operator-accepted test removals (three, recorded in `accepted_test_removals`): `RetryingJwksLoaderTest.UnknownKeyIdRefresh::refreshWithoutTheTriggeringKeyIdChangesNothing`, `IdpFrontingCanonicalPathTest.DeliberateCuts::broadWellKnownPrefixIsReported`, `JwksKeyRotationIT::rotatedKeyIsAcceptedTheFloodIsBoundedAndALoginRecovers`.
- Metrics: the finalize token total counts one review-agent return at an estimated 230000 tokens instead of its reported 209912, so it is about 20k high.
