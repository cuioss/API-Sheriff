envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=landing
created=2026-09-23T15:20:45Z

## What landed

plan-29-final-gap-closure shipped as #348 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-29-final-gap-closure
epic=deployment-configurability
pr=#348
merge_state=merged
cleanup_owed=false
deliverables_total=2
deliverables_done=2
total_tokens=6581259
total_wall_seconds=19930.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:pending,archive-plan:pending
```

## Residue

Spec D1 (gateway-side `TokenRefreshCoordinator.rotate()` scope-delta WARN) was dropped by operator decision: the engine's `RefreshFlow.reportScopeDelta` already logs `TokenSheriffClient-110`/`-111` for scope narrowing/broadening, so the epic's Open Defects entry for the unread `scopeDelta()` can be closed as "already surfaced by the engine" rather than tracked as remaining work.

A pre-existing, out-of-scope repository defect was found and fixed within this plan's PR: duplicate ADR number 0050 (two unrelated files carried the same number). Renumbered the Qute-templates ADR to 0053 and updated its four cross-referencing docs (commit 32c20d1). This was not part of the original spec but would have blocked every future PR merge via the ADR duplicate-number gate.

This finalize run recurred `ci_timeout` findings 5x (all resolved `accepted`, retry-not-failure) — a candidate-lesson about `ci_wait`'s adaptive budget for this repo's slow CI jobs was already routed separately via a `kind: candidate-lesson` inbox message (plan-29-final-gap-closure-006).
