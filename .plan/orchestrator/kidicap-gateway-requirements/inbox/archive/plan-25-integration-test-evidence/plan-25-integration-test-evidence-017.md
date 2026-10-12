envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-08T21:28:44Z

## What landed

plan-25-integration-test-evidence shipped as #408 (merged), merge commit b7f937d3da1ea999205db62d02add38d25946e7d on main.

```landing-facts
schema=landing-facts/1
plan_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
pr=#408
merge_state=merged
cleanup_owed=false
deliverables_total=11
deliverables_done=11
total_tokens=11691525
total_wall_seconds=126091.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
step.create-pr.pr_number=408
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.branch-cleanup.work_performed=true
step.record-metrics.total_tokens=11691525
step.record-metrics.total_wall_seconds=126091.0
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- `archive-plan` had not run when this message was written; it follows this step.
- `pre-submission-self-review` was switched off for this plan by the operator after six rounds of documentation-wording findings without the review closing; it is not in the composed step list above.
- The finalize loop-back ceiling was raised to 8 for this plan only; two further rounds were individually authorised by the operator (integration-tests job timeout 35 -> 60 minutes; one CodeRabbit comment on joined publish-option forms).
- `adr-propose` was not run before the merge (orchestrator ordering slip). Candidate decisions not recorded as ADRs: the fixed 5-second `Retry-After` on the open-circuit 503, and the host-port publication rule for test-started containers.
- The one production change of an otherwise tests-only plan: `Retry-After: 5` on the gateway-generated open-circuit 503 (`GatewayEdgeRoute`).
- Not verified by this plan: the M-41 pending-login flood has never run against a stack (only the post-merge benchmark job runs it).
- Open follow-up candidates: `doc/architecture.adoc` still says the breaker half-opens after `reset_ms` in one passage while the 503 row states the fixed 5 s (DP-2); `HtmlErrorPageIT.unreachableUpstream` pins exactly 502 and can see 503 on a stack reused across suite runs; `JwksLateIdpReadinessIT` and `JwksEgressMismatchIT` were changed outside the plan's declared files; `reserved-path-overlap/auth-overlap.yaml` sits outside the declared-limit inventory guard; the `DescriptorInventoryWiringTest` display name is wider than its check; other nginx image references in the repository are tag-only.
- Candidate-lesson message 014 of this sender contains one inaccuracy: the rejected `ci ... pr comments` call had `--plan-id` AFTER the verb; the form with `--plan-id` before the verb is the accepted one.
- The finalize token total is slightly high: one subagent's usage (about 130k tokens) was added to the accumulator twice.
