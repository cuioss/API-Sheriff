envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:43Z

# Candidate lesson: diagnostic branch had no owner for deletion on early-return paths

- Source: Q-Gate finding 5b9cc9 (4-plan, false_positive_check: TASK-2 states no disposition for diag/plan-32-main on its return paths, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

TASK-1 ended with "delete the diag branches afterwards"; TASK-2 said nothing. The outline gave deletion to D4, but on TASK-2's blocked/STOP returns D4 never runs, so nothing owned the cleanup. Fixed in the dispatch prompt: keep the branch on every return, report its name and head SHA, and the orchestrator deletes it if D4 never runs.

## Candidate rule

Any task that creates a throwaway branch (or worktree, or CI run) states its disposition on every return path, including early ones, and reports the branch name and head SHA in its return payload. "A later deliverable deletes it" only holds if that deliverable is guaranteed to run.
