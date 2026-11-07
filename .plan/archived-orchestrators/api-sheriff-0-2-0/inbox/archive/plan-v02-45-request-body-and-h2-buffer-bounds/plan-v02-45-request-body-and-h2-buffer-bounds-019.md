envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-21T22:25:07Z

## What landed

plan-v02-45-request-body-and-h2-buffer-bounds shipped as #506 (merged through the merge queue as 99e9dfd3).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
pr=#506
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=10188347
total_wall_seconds=76121.0
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=4
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=6
step.code-review.rules_cited=5
```

## Residue

- D1 and D2 are measure-first: refused and queued HTTP/2 streams were proven to release their credit with no code change, and a 2 MiB connection window was measured against the derived one and rejected on the numbers (recorded in `doc/architecture.adoc`).
- D3 added a fixed 25 s request-body deadline (408 `REQUEST_BODY_TIMEOUT`, `ApiSheriff-24`, never counted by the breaker, never retried) and D4 a fixed five-minute gRPC stream-idle bound (`ApiSheriff-25`). Both are fixed constants; there is no `gateway.yaml` key and nothing changed under `config/validation/`. ADR-0060 Amendment A2 records them.
- The threat-model entry GW-07 (request body) stays PARTIAL: two steps in `dispatchGrpc` are asserted by no test (`GrpcProxyIT` runs both without asserting them).
- One CodeRabbit comment on the PR (the late-timeout retirement writing request context data from the upstream event loop) was fixed by TASK-15; the final tree differs from the reviewed tree by one Javadoc-only inline-correction commit (24ca7e1a).
- Three test-gap advisories from the last code review stay open (the late-timeout context-data case drives the new method directly, and the per-route log latch of `ApiSheriff-24` and `ApiSheriff-25` is reached by no test).
- Sourcery refused the review on diff size (150000 characters); it is an optional bot and non-blocking.
- Eighteen candidate lessons were sent to this epic's inbox by this plan; messages 014 to 018 and parts of 012 and 013 concern plan-marshall tooling and may belong in its lessons-routing inbox.
