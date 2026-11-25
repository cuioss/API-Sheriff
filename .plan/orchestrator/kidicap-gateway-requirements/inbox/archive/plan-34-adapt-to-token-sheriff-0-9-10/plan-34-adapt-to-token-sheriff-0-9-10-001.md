envelope_version=1
sender_type=plan
sender_id=plan-34-adapt-to-token-sheriff-0-9-10
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-25T14:44:41Z

## What landed

plan-34-adapt-to-token-sheriff-0-9-10 shipped as #663 (merged, squash 5b95b60b) through the platform merge queue.

```landing-facts
schema=landing-facts/1
plan_id=plan-34-adapt-to-token-sheriff-0-9-10
epic=kidicap-gateway-requirements
pr=#663
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=41222553
total_wall_seconds=95887.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2184
executor_version=0.1.2184
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
step.functional-review.deliverables_met=1
step.functional-review.deliverables_met_with_residuals=5
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=2
step.code-review.blocking_count=0
step.code-review.advisory_count=14
step.code-review.rules_cited=8
```

## Residue

- **The functional review closed by operator override, not on its own evidence.** The operator accepted 15 re-raised findings (the D2.C2 and D5.C5 criterion gaps with their residuals, and ten weakened-test rows for tests deleted together with their subjects) at the 3f9aa527 and 4a449490 heads, and re-confirmed the same acceptance at 4a449490. Two further items (a stale PR-body paragraph and a missing logout-token rationale) were corrected in the PR body and closed as fixed, not accepted. The code review closed clean (full round, 0 blocking, 14 advisory, verifier accepted).
- **Kept back from the library, by decision:** the step-up PAR re-drive (two ParClients), the insufficient-scope challenge builder (throws above 64 scopes), the PEM loader, the readiness probe and the logout-token classes (the library events check refuses the JSON-string `events` form). The planned JWKS-loader adoption was dropped: the library rule can be exploited with made-up key ids to block a real rotation.
- **Accepted residual:** the refreshed ID token's issuer is not compared with the session's original issuer (the engine verifier checks it against the trusted issuers and the refreshed access token only). Recorded in ADR-0084 and the threat model.
- **Follow-ups not done (advisory code-review items):** `doc/configuration/authentication-and-oidc.adoc` (rows around 251-257, 564-565, 672-673, 1207-1262) and ADR-0080 still describe discovery as retried on the next engine use and omit the `refresh-result-refused` disposition and the wider egress-host refusals; stale "foreign issuer" wording in the `TokenRefreshCoordinator` Javadoc and test names; `ClientSigningKeyTest` log-capture cannot see DEBUG/TRACE; `BffRuntimeProducerTest` TTL/grace tests drive a self-built cache; no JVM test drives the two `BffRuntimeProducer` bindings (signed sid-only logout token, user-info read) end to end.
- **CodeRabbit cd316e (accepted, not fixed):** 19 api-sheriff test files build a `TokenValidator` without closing it or disabling the access-token cache; the pattern predates 0.9.10. Needs one shared test-setup change; no issue is filed yet.
- **Gate churn:** `verify -Ppre-commit` reformats ~400 unrelated files on a clean main (import blank-line drift). Reverted each run; not part of this PR.
- **Library behaviour changes now visible at the gateway:** 8192-character token limit, `SIGNATURE_ONLY_VERIFIED` metric, up to 5 s wait while a key set loads, empty-payload claim map, 30 s discovery retry, user-info claim order, `sub`-less access token fails closed, and one accepted library WARN that logs the raw `iss` on the logout path (`TokenSheriff-114`).
- **Not recorded as facts:** `effort` is `unknown` because the run stamped none. The post-merge `checks wait` hung past its 600 s tool bound although every PR check was already green and was stopped (non-gating). The squash message is the PR body, per the repository's `squash_merge_commit_message: PR_BODY` setting; no repository setting was changed.
