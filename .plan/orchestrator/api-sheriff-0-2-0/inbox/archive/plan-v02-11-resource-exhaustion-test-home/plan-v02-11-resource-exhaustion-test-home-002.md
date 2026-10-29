envelope_version=1
sender_type=plan
sender_id=plan-v02-11-resource-exhaustion-test-home
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-28T20:37:05Z

# Landing: PLAN-V02-11 — resource-exhaustion test home

PR #534 merged through the merge queue as `c1ded6c1`.

- **D1:** ADR-0075 records that the gating Failsafe suite owns resource-lifecycle and
  cumulative-exhaustion detection through shrunken-budget exhaustion tests. The benchmark lane stays
  non-gating.
- **D2:** `WebSocketProxyIT` gains two committed negative controls, for the relay sub-budget and the
  general admission budget. Each holds the budget, observes the probe fail at its first upgrade with
  a `503`, releases the budget, and observes the probe pass. `WsAdmissionActivationWiringTest` pins
  the overlay caps to the held counts.
- **D3:** the development README states which test lane owns which defect class, and how to add a
  resource bound. It names PR 427 as the pending-login precedent.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-11-resource-exhaustion-test-home
epic=api-sheriff-0-2-0
pr=534
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=13760713
total_wall_seconds=48023
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2028
executor_version=0.1.2021
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
```

## Residue

- The local `-Pintegration-tests` run before the push was environmentally blocked. CI's integration
  lane was the gate, and it was green on both PR commits and in the merge-queue run.
- The pre-push gate needed a re-run after machine load dropped. The two failures were in
  `GatewayEdgeEarlyResponseResetTest`, which this plan did not touch.
- The post-merge benchmark run and the `main` Maven Build for `c1ded6c1` belong to the orchestrator
  and were not checked by the plan.
- Follow-ups were sent separately as inbox finding `plan-v02-11-resource-exhaustion-test-home-001`.
