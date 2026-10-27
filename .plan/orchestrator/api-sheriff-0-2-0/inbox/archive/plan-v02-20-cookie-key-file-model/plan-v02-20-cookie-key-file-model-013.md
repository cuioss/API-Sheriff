envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:43Z

# Candidate lesson (plan-marshall tooling): execute-phase transition refused for a commit without a change-ledger row

- Source: script-failure cluster, notation plan-marshall:manage-status:manage-status, script_internal_failure at 16:40:20Z (end of 5-execute)
- Suggested component: plan-marshall:phase-5-execute (Step 10a settlement bookkeeping) / plan-marshall:manage-change-ledger. Route to the plan-marshall lessons-routing inbox.

## What happened

The phase transition refused: 1 of 7 worktree-branch commits since diverging from main (515fc89b...) carried no `kind=change` ledger row. The transition succeeded after the missing `manage-change-ledger append --kind change` was run.

## Corrective rule / improvement

Every commit made during execute (including fix-up commits outside a task's normal commit step, such as a verification-driven fix) must be followed by the Step 10a ledger append in the same step. Worth checking which path produced the unrecorded commit, so that path appends the row itself rather than relying on the transition guard.
