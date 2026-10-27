envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-27T14:25:48Z

## What landed

plan-v02-06-enumeration-hardening shipped as #528 (merged via the merge queue as c957ee51).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
pr=#528
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=57768839
total_wall_seconds=95081.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2010
executor_version=0.1.2010
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=4
step.functional-review.deliverables_met_with_residuals=3
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=3
step.code-review.rules_cited=3
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_commit_sha=c957ee513126fe929dd5b3266212fdb3159f7a3b
```

## Residue

- **Spec split (operator decision at outline).** This plan shipped spec D1 (uniform 404 for every pre-trust route-scoped rejection), D2 (response-timing floor, now with the floor-step release rule) and D9 (`rate_limit` boot WARN ApiSheriff-140 plus doc alignment), with their D5/D6 shares (integration tests, demo SPA/Playwright). Spec **D3, D4, D7 and D8** (per-source 404-rate detection substrate, trip response, substrate contract, HTTP/2 stream-abuse bounds) are NOT shipped and belong to a follow-up plan that still has to be staged. **PLAN-V02-07 must re-point its dependency to that follow-up plan**, not to PLAN-V02-06.
- **Follow-up plan scope additions (operator decisions in finalize).** One review point on the uniform-404 change was deferred by the operator to the follow-up enumeration plan; it is not fixed in #528.
- **ADR proposal deferred to the follow-up plan.** adr-propose found one decision no ADR records: a planned configuration key the gateway does not enforce (`rate_limit`) is kept, parsed and warned at boot rather than removed (contrast ADR-0064, which removed the inert `circuit_breaker`). Needs a new ADR file; #528 was at 98/100 changed files.
- **ADR renumber.** The uniform-404 decision record is **ADR-0073** (main took 0072 for the cookie sealing key file in #527 mid-finalize). Commit messages 9e8b7265 and b46dbc5a on the squashed branch still say ADR-0072.
- **Flaky test (pre-existing, not caused by this plan).** `GatewayEdgeRouteTest$RelayStartFailure.relayThatFailsToStartReleasesTheUpstreamResponse` failed once in CI sonar-build's isolated fork (run 40504680597) and passed on rerun; it should be made deterministic.
- **Squash message.** The merge queue offers no per-merge message override and the repository squash default is PR title + PR body, so the squash commit on main carries the full PR body including the review-bot summary (noted on the PR).
- **Post-merge verification (orchestrator).** The PR-attached post-merge Performance Benchmark run (40517977735) was still in progress when the plan finished; the main-branch Maven Build for c957ee51 was not checked by the plan.
- **Advisory review residue (not blocking).** `RouteConfig.java:58` third `rate_limit` wording; `BffSessionFallbackIT.java:311` Javadoc still says 401; `doc/user/endpoint-routes.adoc:402-405` and `doc/user/anchors.adoc:316-318` scope the pre-trust 404 to unauthenticated callers although pre-auth checks answer it to every caller; `doc/development/declared-limit-assertion-coverage.adoc:535` cites `bff-overlap.yaml:37` (declaration at 38); the floor and jitter values remain reasoned, not measured.
