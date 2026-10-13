envelope_version=1
sender_type=plan
sender_id=plan-v02-21-unread-review-findings
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-13T14:14:27Z

# Candidate lesson: deep-lane outline wrote no CERTAIN_INCLUDE assessments for its affected files

**Source signal:** Q-Gate finding `3823b3` (phase 3-outline, severity warning, resolution fixed in-run)

## What happened

The 3-outline Q-Gate (check 2.2) found that `assessment list --certainty CERTAIN_INCLUDE` returned 0 rows and the plan's findings store did not exist (`findings_store_state: missing`). None of the affected files of deliverables 1-4 was backed by an assessment, even though the plan ran on the deep lane, whose discovery normally records per-file assessments.

## Fix applied in-run

Recorded 10 CERTAIN_INCLUDE assessments, one per written path of D1-D4. Every existing path was verified on disk first; the one write-new path (`passthrough-backend/Dockerfile`) was verified absent.

## Process context (orchestrator observation)

The finding was caught only because the outline leaf self-transitioned out of 3-outline before the orchestrator-level q-gate-validation ran. The orchestrator had to `set-phase` back to 3-outline and re-dispatch. Without that recovery the outline would have advanced with no assessment substrate.

## Candidate rule

A deep-lane outline should record its per-file CERTAIN_INCLUDE assessments before it writes the deliverables, rather than having the Q-Gate catch their absence afterwards. A phase leaf should also not self-transition its phase ahead of the orchestrator's q-gate-validation.
