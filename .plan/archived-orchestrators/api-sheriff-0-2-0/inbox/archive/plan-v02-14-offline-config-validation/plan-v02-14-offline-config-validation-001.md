envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:34:21Z

# Candidate lesson: scope-creep guard still fails to persist its finding and counts upstream commits as plan scope

**Source signal:** script-failure cluster (1 distinct notation, recurring across every phase-5-execute envelope)
**Component (suggested):** plan-marshall:phase-5-execute (scope-creep guard)
**Category (suggested):** bug
**Possible recurrence of:** 2026-10-03-06-005 ("the scope-creep guard fails exactly when it has something to report, and it counts upstream merges as plan scope")

## What happened

- In every execute envelope of this plan the scope-creep guard errored with `finding_persist_failed`: the guard tries to write a finding of type `scope_creep_warning`, and `manage-findings` rejects that type. The warning it exists to raise is therefore never stored.
- The guard measures against `plan_creation_sha`. The worktree was created from `origin/main`, which had already moved past that sha, so upstream commits absorbed at worktree creation were counted as plan-authored files, i.e. as scope creep.
- Recording this as a lesson from this repository with `manage-lessons add --component plan-marshall:phase-5-execute` was refused with `wrong_store`, because this repository does not own the plan-marshall bundle. The defect report has to go to the plan-marshall store, or be filed through the orchestrator.

## Suggested direction

- Register `scope_creep_warning` as a valid finding type, or have the guard write a type `manage-findings` accepts.
- Measure against the worktree base commit (the merge-base with the branch the worktree was cut from), not `plan_creation_sha`.

## Evidence

- Plan: plan-v02-14-offline-config-validation, phase 5-execute work log (`[ERROR] ... script_failure` lines from the guard).
