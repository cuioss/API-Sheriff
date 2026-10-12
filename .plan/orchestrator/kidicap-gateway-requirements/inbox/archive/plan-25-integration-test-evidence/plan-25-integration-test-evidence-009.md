envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:08Z

component=plan-marshall:phase-6-finalize
category=anti-pattern
created=2026-10-08

# adr-propose was not run before branch-cleanup merged in the final round

## What happened

Plan plan-25-integration-test-evidence (PR #408). In the finalize step order `adr-propose` comes before `branch-cleanup`. In the final round the orchestrator, after several manually driven loop-back rounds, went from `sonar-roundtrip` straight to `branch-cleanup`. The PR was merged; `adr-propose` was recorded as skipped one second after `branch-cleanup` completed.

Work log, 2026-10-08:

- 19:49:59Z sonar-roundtrip done
- 21:11:28Z branch-cleanup loop_back
- 21:23:39Z branch-cleanup done
- 21:23:40Z adr-propose skipped

A step that can write tracked files can no longer contribute to the PR once the merge has happened, so the skip was forced, not chosen.

## Rule

When finalize rounds are driven by hand (re-fired steps, inline fixes, rounds authorised one at a time), re-read the manifest's step list before the merge step and confirm that every step ordered before it has a record for the current head. The merge step is the one that cannot be repeated.

## Open point for the pickup

Whether this plan owes any architecture decision record (candidates named during the run: fixed `Retry-After: 5` on the open-circuit 503 with `circuit_breaker.reset_ms` parsed but unread; the host-port publication rule for test-started containers) was never evaluated.

## Evidence

- Work log entries 953c21, 172b47, 41d1f6, d5c603; decision log entries 3ef69c, 9f9df9.
