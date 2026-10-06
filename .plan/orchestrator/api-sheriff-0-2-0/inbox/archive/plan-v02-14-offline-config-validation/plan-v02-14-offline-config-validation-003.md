envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:28Z

# Candidate lesson (recurrence): two Maven builds in one worktree caused spurious "Failed to start quarkus" errors again

**Source signal:** Q-Gate finding 70a46e (5-execute, partly accepted) plus orchestrator observation
**Component (suggested):** api-sheriff (orchestrator-tier build dispatch)
**Category (suggested):** bug
**Recurrence of:** 2026-10-04-09-002 ("Two Maven builds in one worktree at the same time cause false build failures")

## What happened

- The orchestrator ran module tests (`test -pl api-sheriff -am`) and integration tests at the same time in the same worktree, so both shared `target/`.
- ContextPathDefaultsTest, DefaultProfileReadinessTest, CookieModeBootTest and ExtensionUnqualifiedBeanExclusionTest failed with "Failed to start quarkus". A different set of classes failed in each of the two runs. Serial re-runs were green.
- The finding classified these as concurrency-induced and accepted them, pending a serial re-run. That cost a loop-back.

## Why it matters

The existing lesson did not stop it happening again. The rule is known, but nothing enforces it at dispatch time.

## Suggested direction

Serialize orchestrator-tier Maven builds per worktree (for example with a per-worktree build lock in manage-locks), rather than relying on the orchestrator to remember.
