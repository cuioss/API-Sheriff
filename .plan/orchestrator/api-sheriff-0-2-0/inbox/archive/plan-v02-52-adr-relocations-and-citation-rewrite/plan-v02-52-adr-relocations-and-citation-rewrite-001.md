envelope_version=1
sender_type=plan
sender_id=plan-v02-52-adr-relocations-and-citation-rewrite
epic=api-sheriff-0-2-0
kind=landing
created=2026-11-01T19:26:33Z

## What landed

plan-v02-52-adr-relocations-and-citation-rewrite shipped as #554 (merged, squash 48715de4 via the merge queue).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-52-adr-relocations-and-citation-rewrite
epic=api-sheriff-0-2-0
pr=#554
merge_state=merged
cleanup_owed=false
deliverables_total=12
deliverables_done=12
total_tokens=31441024
total_wall_seconds=83544.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2036
executor_version=0.1.2036
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=10
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=3
step.code-review.rules_cited=3
```

## Residue

- Post-merge: the main-branch Integration Tests run on 48715de4 failed in the `generate-mtls-certificates` exec step with "Broken pipe", before any test ran. The merge-group run of the same tree passed. The benchmark run was still pending when finalize ended. Not re-run by the plan.
- ADR-0008's upstream timeout sentence was corrected in this PR (per-attempt guard timeout, Vert.x client defaults). The spec had assigned that sentence to kidicap PLAN-29 D6; that plan's owner should be told it is already done here. Nothing was written to the kidicap ledger.
- Follow-ups not in this PR:
  - "whole dispatch" timeout wording in GatewayEdgeHttp2AbuseTest, GatewayEdgeRequestBodyIdleTimeoutTest, EdgeHardeningOptionsTest and GrpcDispatchStageTest.
  - Code-review advisories: the DispatchStage Javadoc still names a route read timeout; two quotations in test-corpus-integrity.adoc differ by one comma from the relocated text; its "Matched positive controls" paragraph still lists two near misses after a third limit and control were added.
  - The DispatchStage bodyless-send code comment says the inbound stream "stays unsubscribed", but the bodyless watch subscribes it for monitoring; ADR-0008 was corrected, the comment was not.
  - The three cut wording leads (ADR-0070, benchmarks README, compose-sample .env); the unchecked BFF session-sweep 60 s statement; the idempotent-method list not bound by a contract test.
  - Tooling: the -Ppre-commit formatter again rewrote ~380 unrelated files' import spacing (regression vs PR #415); module-tests does not resolve at whole-tree scope in the pre-push gate (DEGRADED).
- The PR description was trimmed to Summary + trailer before the merge so the queue's squash message (PR_BODY) carries no bot summary; the other sections are in a PR comment.
