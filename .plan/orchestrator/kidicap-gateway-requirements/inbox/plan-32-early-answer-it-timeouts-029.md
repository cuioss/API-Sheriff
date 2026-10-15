envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-15T11:54:27Z

## What landed

plan-32-early-answer-it-timeouts shipped as #466 (merged, 73233f1e, closes #455): the gateway now sizes its inbound HTTP/2 connection window and bounds request-body idle time, which removes the intermittent Http2EarlyResponseResetIT timeout.

```landing-facts
schema=landing-facts/1
plan_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
pr=#466
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=10290935
total_wall_seconds=80493.0
harness=unknown
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:pending,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.functional-review.deliverables_met=4
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.acceptance=operator_override
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.count_status=confirmed
```

## Summary of the change

- D1 (reproduce and attribute): the CI timeout was reproduced; the `SO_LINGER 0` hypothesis was refuted (arm M 5/120 vs arm R 3/120, no meaningful difference), and the stall was placed on the gateway side. #448 is most likely not the origin of the timeout.
- D2 (locate): diag round on `diag/plan-32-main` @a660e3eb (run 38603393080), arm J 59/100, arm N 5/100, one signature. Cause: a resource interaction of three bounds - the upstream HTTP/1.1 pool size (Vert.x default 5), the inbound HTTP/2 connection window (65,535-byte default, equal to one stream window), and the upstream request head being written lazily with the first body chunk. Together they could deadlock a batch of concurrent uploads on one connection until the IT's 20 s timeout.
- D3 (fix): `EdgeHardeningOptions` sets the inbound HTTP/2 connection window to max concurrent streams x initial stream window (clamped), with the max concurrent streams cap at 100. Unit cases in `EdgeHardeningTest` plus a deterministic JVM reproduction in `GatewayEdgeEarlyResponseResetTest` that fails without the fix.
- D6 (operator-added): a fixed 10 s request-body idle bound on dispatch. When no request-body byte arrives for 10 s while the gateway is reading the body, the upstream request is reset (its pooled connection is released at once) and the client is answered `408 Request Timeout`, instead of the guard's 30 s `504` that counted against the upstream circuit breaker. Steady slow uploads, upstream backpressure, pool waits, bodyless requests and gRPC routes are unaffected.
- D4 (proof): both diag arms 20/20 green at the plan head, and 10/10 per arm again after the stream cap was added; PR CI green.
- D5 (docs): ADR-0060 amendment, `architecture.adoc`, `endpoint-routes.adoc`, `LogMessages.adoc`.

## Residue

- Rebase awareness: the plan touched `GatewayEdgeRoute.java` (request-body idle bound wiring). PLAN-29, PLAN-V02-23 and PLAN-V02-12 also work in that surface and should rebase onto 73233f1e before outlining or executing.
- code-review closed by operator override at dbf40cda (the final commit only applied two one-line Sonar dispositions on top of a head where code review, functional review, simplify and security audit had closed clean); the quality gate, CI, review bots and Sonar re-ran on the pushed head.
- automatic-review recorded 0 comments (2 empty bot reviews, 1 refused) - no review-bot findings carried forward.
- functional-review graded 2 of 6 deliverables met_with_residuals (10 advisory notes, 0 blocking); see the plan's functional-review record for the named residuals.
- `harness=unknown`: record-metrics found no harness stamp on this plan.
