envelope_version=1
sender_type=plan
sender_id=sec-02-upstream-attribution-forward-headers
epic=security-hardening-tier-1
kind=landing
created=2026-11-10T02:23:50Z

## What landed

sec-02-upstream-attribution-forward-headers shipped as #590 (merged via the merge queue, merge commit 81b46785).

```landing-facts
schema=landing-facts/1
plan_id=sec-02-upstream-attribution-forward-headers
epic=security-hardening-tier-1
pr=#590
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=40945209
total_wall_seconds=50508.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2090
executor_version=0.1.2090
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
step.functional-review.deliverables_met=5
step.functional-review.deliverables_met_with_residuals=0
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=6
step.code-review.rules_cited=6
```

## Residue

- Review cycles: CodeRabbit reviewed three heads (3ab4fd7b, 732d68ec, 64f72e5b); its 15 actionable comments were fixed (two fix-task loop-backs, TASK-11 to TASK-13, plus inline doc edits) and every review thread was answered and resolved. Sourcery declined throughout on diff size (optional bot). The first CodeRabbit request hit its review quota and was retried after a 90-minute wait.
- Open operator decisions, not in this plan's scope: `forwarded.trust_scheme_host` is accepted but read by no production code (wire it or remove it); and whether to widen the gRPC first-message bound beyond the first forwarded request byte (this plan delivers the headers-only case decided at refine).
- Advisory review findings left as logged (not fixed): README provenance wording, unit-level provenance-list completeness, a few Javadoc lines still saying "no request message", and two UpstreamGuardsTest/GrpcDispatchStageTest Javadoc claims broader than what the new test binds.
- Integration-test evidence is the native-image run at 3bf6075a content (444 tests, 0 failures); later commits touched no integration-tests path or production behaviour beyond Javadoc and unit tests.
