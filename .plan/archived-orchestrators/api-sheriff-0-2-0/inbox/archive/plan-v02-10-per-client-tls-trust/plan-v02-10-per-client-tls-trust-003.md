envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:32Z

component=plan-marshall:phase-5-execute
category=bug
source=script failure cluster plan-marshall:phase-5-execute:scope_creep_check (work log 5b10d1 x3, c63d53, 42c9dd, d0f34b)

# scope_creep_check cannot save its finding: manage-findings rejects type scope_creep_warning

## What happened

`plan-marshall:phase-5-execute:scope_creep_check` failed three times in this run (after TASK-6, TASK-8 and TASK-9), each with exit_code=1 and failure_kind=script_internal_failure: `Invalid finding type: scope_creep_warning`. The check tried to save a `scope_creep_warning` finding. manage-findings only accepts bug, improvement, anti-pattern, triage, tip, insight, best-practice, build-error, test-failure, lint-issue, sonar-issue, arch-constraint, pr-comment and pr-comment-overflow. The check's result was therefore never saved, and every run logged a script failure.

## Second defect seen in the same output

The leftover files the check reported (10, then 47) were mostly files from upstream main commits that the finalize rebase pulled in. They counted because the check compares against plan_creation_sha, which is older than the rebase. Files changed upstream are counted as scope creep against the plan.

## Fix direction

Either add `scope_creep_warning` to the manage-findings type list or have the check save its finding under an existing type. After a rebase, compute the scope from the merge base with the current main, not from plan_creation_sha.
