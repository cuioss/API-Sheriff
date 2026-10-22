envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:38Z

# Script failure: git-workflow commit refused in 6-finalize for a docs fix with no sanction recorded

- Source: script log [ERROR] e36750 (2026-10-21T16:46:10Z), notation plan-marshall:workflow-integration-git:git-workflow commit, exit 1
- Component: phase-6-finalize source-commit sanction

## Observation

A docs-only correction to doc/security-threat-model.adoc (the GW-07 red-test count, finding
ad7b99) was committed in 6-finalize before its review-rubric section 6 inline-correction record
existed. The commit verb refused with reason `no_sanction`: no composed phase-6 step id, no open
loop-back fix task and no inline-correction record. The record was then filed and the retry
(7690c7) succeeded.

## Outcome

One refused call; the guard worked as designed.

## Candidate rule

In 6-finalize, file the inline-correction Q-Gate record (or open the fix task, or pass --step)
before calling git-workflow commit; the sanction has to exist first.
