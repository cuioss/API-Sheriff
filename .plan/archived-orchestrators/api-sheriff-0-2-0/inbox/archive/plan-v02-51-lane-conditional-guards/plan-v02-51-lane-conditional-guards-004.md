envelope_version=1
sender_type=plan
sender_id=plan-v02-51-lane-conditional-guards
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-30T00:33:24Z

# Candidate lesson: a task that needs the CI provider stalled on "Not authenticated" mid-plan

**Component:** plan-marshall:tools-integration-ci (ci router auth preflight)
**Category (suggested):** improvement

## What happened

This is the script-failure cluster for this run. TASK-8 (fix for functional-review finding a2475c: close issue 201 with the nine run counts) began with `ci issue view --issue 201`. That call returned `status: error 'Not authenticated'`, and ci_health reported `gh authenticated=false`. The task stopped as its instructions required: nothing was posted and the issue stayed open. The plan then waited roughly 40 minutes, from 13:05 to 13:46, until credentials were restored. On re-dispatch the comment was posted and the issue closed (554749).

The fix round could have known in advance that the task needed the CI provider. Nothing checked CI auth before the envelope was dispatched, so the failure surfaced only inside the leaf, after the dispatch cost had been paid.

## Proposed rule

When a fix or implementation task's steps call the CI router (issue/PR read or write), the orchestrator should check `ci_health` authentication before dispatching that envelope. If auth is down, it should surface the blocker (operator: `gh auth login`) before dispatch, instead of letting the leaf discover it at step 1. This is related to the known post-cleanup "Not authenticated" cwd artefact, but this occurrence happened with a live worktree.

## Evidence

Decision log c2f889 (TASK-8 stop, 2026-10-29T13:05:29Z) and 554749 (re-dispatch success, 13:48:28Z). Q-Gate finding a2475c in plan-v02-51-lane-conditional-guards.
