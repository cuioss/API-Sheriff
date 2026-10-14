envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-14T12:56:13Z

## What landed

plan-30-early-answer-upstream-port-exhaustion shipped as #448 (merged by the merge queue as b7b9869989656b9ee32c06ff80e05a930f4f7910).

```landing-facts
schema=landing-facts/1
plan_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
pr=#448
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=6454680
total_wall_seconds=59876.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
step.functional-review.deliverables_met=5
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=3
step.code-review.rules_cited=3
```

## Residue

Narrative-only items the epic should track. None of them is a required fact.

1. **Unexplained intermittent integration-test hang (follow-up issue #455).** `Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway` timed out (about 21 s) in 2 of 4 CI attempts at head bc002caf, and passed in the other two attempts (2.7 to 3.0 s) and in every other run, including the merge-queue run for #448. It was not reproduced locally. The cause is NOT established: the SO_LINGER 0 change is neither attributed nor cleared. The PR merged on the green merge-queue run; the hang remains an open question.
2. **Unrequested production change.** `GatewayEdgeRoute.guardFor` no longer registers the circuit breaker under a SmallRye name, because that registry is JVM-global and rejected a reused upstream port once the abortive close freed server-side TIME_WAIT. The breaker's identity is now its guard (shape name `scheme://host:port:h2=<flag>:retry=<flag>`). A draft ADR for that decision was deferred by the operator to the follow-up; nothing is recorded in the ADR corpus yet (draft kept under `.plan/temp/adr-propose-plan-30-breaker-identity-draft.adoc` in the main checkout, git-ignored).
3. **Advisory items left for a follow-up PR (not fixed here):** stale breaker-name comments in `UnreachablePort.java` and `GatewayEdgeErrorPageTest.java`; the now-false comment in `early-answer.yaml` that the backend never reads upload bodies; documentation and decision-log text calling the ~51,200 uploadSmall iterations "derived, not measured" although k6 measured 51,198; `UpstreamFailureMapper.recordBreakerTransition` parameter still named `routeId`; the `api-sheriff/pom.xml` comment still advertising `CircuitBreakerMaintenance`; and the class Javadoc of `DispatchStage` and ADR-0066 amendment A1 decision 3 giving only the HTTP/1.x rationale for dropping `StreamResetException`.
4. **Post-merge benchmark run still pending at the time of emission** (benchmark.yml, run 38565585855). This run did not wait for it; verification of the post-merge state belongs to the orchestrator.
5. **Benchmark result (before the fairness-backend change):** uploadSmall 51,198 k6 iterations, error rate 0, against 47,441 iterations and error rate 0.40 in PLAN-28. After the nginx fairness-backend change (upload paths read the body): uploadSmall 80.86 req/s and uploadLarge 1.80 req/s, both error rate 0.
6. **Process notes.** Sourcery was refused on quota and is optional, so it did not block; CodeRabbit had no included review left in that hour. One metrics-accumulator call was made with placeholder numbers and reversed with matching negative values, so the accumulator's `samples` counter is about 2 too high. `archive-plan` follows this step and is therefore not listed in `steps`.
