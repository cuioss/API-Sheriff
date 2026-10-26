envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:01:11Z

# Candidate lesson: a plugin-cache version move mid-run breaks the worktree executor with "Unknown notation"

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in executor generation and staleness detection for worktree copies of `.plan/execute-script.py`.

**Source:** plan-v02-27-test-simplification, observed by the orchestrator. The plan logs record no specific entry for it. This item is also one of the script-failure clusters the dispatcher counted (`signal_script_failure_clusters_count: 6`).

## What happened

The plugin cache moved from 0.1.1986 to 0.1.2010 while the plan was running. The worktree's copy of the executor still carried the old notation mapping, so calls began failing with "Unknown notation". They kept failing until the executor was regenerated. Nothing flagged the skew ahead of time: the first sign was a failing script call in the middle of a phase.

## Corrective direction

- Have the executor detect that its pinned cache version is no longer the active one. It should fail with a single actionable "executor stale, regenerate" error, not a per-notation "Unknown notation".
- Regenerate, or at least check, the worktree executor at each phase boundary. Do the same at loop-back re-entry, not only at worktree creation.
- This relates to the project's existing memories "Executor Regen Version Skew" and "Worktree Missing Executor". Those cover regenerating through a stale executor and a missing executor; this covers an executor made stale mid-run.
