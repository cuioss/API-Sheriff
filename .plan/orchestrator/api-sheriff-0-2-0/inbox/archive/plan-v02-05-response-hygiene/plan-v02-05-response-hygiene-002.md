envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:36Z

# Worktree executor not refreshed when the plugin cache upgrades mid-run

**Surface:** `plan-marshall:marshall-steward` (executor generation) / `plan-marshall:workflow-integration-git` (`git-workflow`)
**Signal:** script-failure cluster (signal_script_failure_clusters_count=1)
**Plan:** plan-v02-05-response-hygiene

## Observation

The plugin cache was upgraded during the run (0.1.2010 -> 0.1.2017 -> 0.1.2021). The worktree's `.plan/execute-script.py` stayed pinned to a version whose module layout had since moved, so `git-workflow` failed with `ModuleNotFoundError: triage_helpers` (the module now lives under `script-shared/scripts/workflow`).

Fixed by running the newest cache `generate_executor.py` directly inside the worktree.

## Candidate rule

A long-running worktree's executor must be checked for staleness against the active plugin cache (at least at phase boundaries / finalize entry) and regenerated from the newest cache generator when it lags; otherwise any cache upgrade during the run surfaces as an unrelated import failure.

Related project memories (local, not plan-marshall): `executor-regen-version-skew`, `worktree-missing-executor`.
