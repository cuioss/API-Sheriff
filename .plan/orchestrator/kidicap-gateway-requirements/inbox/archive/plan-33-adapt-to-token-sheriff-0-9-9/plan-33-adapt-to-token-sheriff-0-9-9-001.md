envelope_version=1
sender_type=plan
sender_id=plan-33-adapt-to-token-sheriff-0-9-9
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-24T09:55:09Z

## What landed

plan-33-adapt-to-token-sheriff-0-9-9 shipped as #656 (merged by the platform merge queue as 6ea8bf31).

```landing-facts
schema=landing-facts/1
plan_id=plan-33-adapt-to-token-sheriff-0-9-9
epic=kidicap-gateway-requirements
pr=#656
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=81692289
total_wall_seconds=158809.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2184
executor_version=0.1.2184
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
unresolved_review_threads=0
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
step.functional-review.deliverables_met=1
step.functional-review.deliverables_met_with_residuals=3
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=7
step.code-review.rules_cited=4
```

## Residue

- **Advisory wording left open on purpose (none affects behaviour).** `doc/user/bff-session.adoc` (about lines 1002-1004) and `doc/variants/02-bff-session.adoc` (about lines 207-210) still say a logout revokes "the session it found"; the ADR 0018 updating-write section still describes `persist` as presence-only and omits the basis, `Superseded` and `destroyIfCurrent`; the ADR 0046 per-kind table, summary and count omit `SCOPE_REFUSED`; the refresh-failure table in `doc/configuration/authentication-and-oidc.adoc` has no row for a refresh superseded at the persist; the `RetryingJwksLoader` Javadoc still says an unknown key id "can still claim" a refresh for a degraded issuer (ADR 0063 states the deployed reading); `doc/development/bff-cookie.adoc` names a logout and a widening as the only causes of `Gone` and `Superseded`. Coverage gaps: `CookieSessionBinding.destroyIfCurrent` has no direct test, and `TokenValidatorProducerTest.degradedLoaderRejectsAValidTokenFailClosed` does not cover an issuer admitted and degraded afterwards.
- **`unresolved_review_threads=0` was read directly, not from a `review-gate` fact.** This manifest has no `review-gate` step. The count is the resolvable review threads (non-empty thread id) reported open by `ci pr comments --unresolved-only` immediately before the merge. Nine review-body and issue-comment rows with an empty thread id were also listed; they cannot be resolved. `review_body_findings_total` and `review_body_findings_handled` are `unknown` because no step parsed the review bodies, so this landing reads as incomplete at those two keys.
- **`effort=unknown`.** The plan carries no effort stamp. The `model` value is the self-reported stamp; the dispatched steps ran under execution-context levels 5 and 6.
- **`sonar_open_new_issues=0`** is the Sonar read on the pushed head f9868bde (0 new-code issues, 0 security hotspots), which is the head that merged.
- **Freshness evidence coverage was not audited.** The pre-push freshness gate answered `fresh` with `scope_cross_check: undetermined` (`required_coverage_unknown`): a successful whole-tree build row matched the tree, but its coverage of the change was not checked.
- **The merge passed on `finalize-step-sync-baseline` unconsulted.** The merge gate listed that step's recorded head as an unconsulted stamp (the step declares no head dependence).
- **Owed hint not promoted.** The preference-emitter found one recurrence over its threshold (module `maven`, finding class `test failure`, three load-induced build failures accepted during the run). `maven` is the build tool's attribution tag, not one of the ten architecture modules, so no owed `architecture enrich` call was filed.
- **Run history.** The code review and the functional review each spent three loop-back rounds of five; the wait-region unified triage spent three of five. The PR body was rewritten at finalize and now describes the whole branch.
