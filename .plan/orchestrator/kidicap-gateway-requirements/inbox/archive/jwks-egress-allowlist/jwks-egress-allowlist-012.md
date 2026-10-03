envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=landing
created=2026-09-23T00:54:57Z

## What landed

jwks-egress-allowlist (PLAN-17) shipped as #345 (merged, squash via merge queue, c1b09c7).

```landing-facts
schema=landing-facts/1
plan_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
pr=#345
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=5521793
total_wall_seconds=22154.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- Post-merge CI on main (merge commit c1b09c7, run 35802987168 "Integration Tests") failed once in `WebSocketRelayStageTest.relaysBidirectionalTextFrames` (30s echo timeout) — outside this plan's footprint, green on every PR run; suspected runner flake. A re-run of the failed job was requested; its verdict was not yet known when this landing was written.
- One loop-back round (iteration 1/5): two CodeRabbit doc-wording findings fixed (configuration.adoc "absent or empty"; threat-model remaining-host claim).
- cuioss-review-bot required an explicit re-review trigger after the loop-back fix commit (no auto re-review on push; merge-queue path skips trigger A).
- Operator decisions carried out: q1 derive allowance from jwks.url host on absent/empty (ADR-0011 Amendment A1, threat model GW-05/BFF-07); q2 refuse host:port entries at boot; q3 prove mismatch refusal end to end via one-off JwksEgressMismatchIT gateway.
- realized_footprint (45 paths) includes 8 upstream .plan/orchestrator files from #344 because footprint capture diffed against a stale local main; the plan's own diff is 37 files.
