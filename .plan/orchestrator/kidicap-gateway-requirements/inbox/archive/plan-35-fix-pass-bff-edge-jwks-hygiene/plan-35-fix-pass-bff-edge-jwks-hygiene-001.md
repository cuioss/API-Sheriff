envelope_version=1
sender_type=plan
sender_id=plan-35-fix-pass-bff-edge-jwks-hygiene
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-27T13:01:37Z

## What landed

plan-35-fix-pass-bff-edge-jwks-hygiene shipped as #665 (merged by the platform merge queue as 3e530b3e).

```landing-facts
schema=landing-facts/1
plan_id=plan-35-fix-pass-bff-edge-jwks-hygiene
epic=kidicap-gateway-requirements
pr=#665
merge_state=merged
cleanup_owed=false
deliverables_total=11
deliverables_done=11
total_tokens=40182099
total_wall_seconds=177783
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.2184
executor_version=0.1.2184
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=unknown
step.functional-review.deliverables_met=8
step.functional-review.deliverables_met_with_residuals=3
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=5
step.code-review.rules_cited=5
```

## Residue

Narrative items no step recorded as a fact:

- **Measurements the lifecycle does not publish.** `review_body_findings_total` and `review_body_findings_handled` have no producing step, so they are `unknown`. For the reader: CodeRabbit's first review header read 7 inline comments plus 2 review-body nitpicks; all 9 carry a recorded disposition (7 thread replies resolved on the provider, the nitpicks answered in one batched comment). `sonar_open_new_issues` is `unknown` because the post-merge Sonar read was undecidable (the head branch was gone, `head_revision_unavailable`); the pre-merge scan of the merged head 581df2da confirmed 0 new-code issues, hotspots not read separately.
- **Post-merge CI on main was still running when this landing was written.** Maven Build (with the snapshot deploy) and Integration Tests at 3e530b3e were in progress; the merge-group CI that gated the merge passed. The orchestrator verifies the main-branch run.
- **One transient CI failure on the PR.** The first CI run of 581df2da failed only in the org's Quarkus alignment check (HTTP 429 from Maven Central, "CANNOT DETERMINE"); the failed jobs were re-run and passed. Not branch-related.
- **Two scope deviations from the plan spec, both logged.** ApiSheriff-147 is latched per two reasons (provider-discovery, revocation-request), not three, because token-sheriff-client 0.9.10 reports a non-success answer, an I/O failure and an egress refusal through one TransportException. Deliverable 10 retargeted from PortalIT to HtmlErrorPageIT (the outline text still names PortalIT).
- **Operator decisions kept the review loop honest.** Three test removals were accepted (inventedKeyIdPublishesARotatedKeySet, the LogoutEndpointTest rename, two JwksKeyRotationIT identities); four CodeRabbit comments were held by decision (the hand-kept token lists in doc/LogMessages.adoc, the independent RefreshKey oracle list, two review-body nitpicks).
- **Advisory follow-ups, not fixed in this plan** (final functional and code reviews, non-blocking): ADR-0085 says every other source is refused at saturation but a source at its own share is still admitted by own-oldest eviction; a failed RetryingJwksLoader publication leaves the replaced delegate unclosed until the next publication; RpInitiatedLogoutTest builds the expected ApiSheriff-147 text from the record's own template; the SheriffMetrics Javadoc still describes the session-events series as lifecycle events although the pending store is now its only recorder; PendingAuthorizationStoreTest matches ApiSheriff-154 by its self-resolved identifier only.
- **The landing precedes emit-landing and archive-plan** in the steps list by design; both run after it.
