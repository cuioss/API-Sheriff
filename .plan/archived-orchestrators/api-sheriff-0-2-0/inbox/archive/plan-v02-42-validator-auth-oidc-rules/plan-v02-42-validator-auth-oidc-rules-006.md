envelope_version=1
sender_type=plan
sender_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-21T11:40:50Z

## What landed

plan-v02-42-validator-auth-oidc-rules shipped as #502 (merged, merge commit df84699d): AuthRules, OidcClientRules and OidcSessionRules (13 rules) extracted from ConfigValidator into `config/validation/rule/`, tests moved with them (139 tests before and after), concern map and member references updated, and the CLAUDE.md / AGENTS.md Git Workflow bot wording corrected (CodeRabbit required, Sourcery optional, PR-Agent removed).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
pr=#502
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=5900684
total_wall_seconds=21168.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
```

## Residue

- Operator-closed review findings: `functional-review` and `code-review` each reported 3 blocking `weakened_test` findings (per-path assertion count on the moved tests). They are false positives of a per-path counter: independent verification showed 139 = 139 tests, 257 = 257 assertions and identical method bodies before and after the move. The operator accepted both closes (`operator_override`).
- Stale prose left unchanged on purpose (move-only plan, outside the spec's surface): `README.adoc` (~line 361) still says the rule "lives in ConfigValidator" where the owner is `ForwardingRules`; `doc/development/re-review-on-loopback.adoc` and `doc/development/README.adoc` still describe PR-Agent, which #493/#494 removed. Worth a small docs follow-up.
- Not verified by this plan: the post-merge `maven.yml` run on `main` (deploy-snapshot) for merge commit df84699d. That check belongs to the orchestrator.
- Backgrounded post-merge `ci checks wait` failed with "gh CLI not found" after the plan worktree was removed (cwd gone). Non-blocking, the PR had already merged.
- `effort` was not stamped in the plan metadata, so it is written as `unknown`.
