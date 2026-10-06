envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:57Z

# Candidate lesson (recurrence): the deep-lane outline again left the assessment store empty

**Source signal:** Q-Gate finding 1fb173 (3-outline, accepted by operator)
**Component (suggested):** plan-marshall:phase-3-outline (deep lane, java/documentation domains)
**Category (suggested):** improvement
**Recurrence of:** 2026-10-04-09-003 ("Deep-lane outline left the assessment store empty, so assessment coverage could not be checked")

## What happened

- The assessment store was empty (`findings_store_state: missing`, 0 assessments). None of the 25 write paths across deliverables 1-7 had a CERTAIN_INCLUDE assessment, so the assessment-coverage check failed for every deliverable.
- The decision log shows the two-pass deep-lane outline recorded no assessment step. No assessment producer runs for this java/documentation plan shape.
- The operator accepted the gap. File existence and request alignment were checked separately by the quality check.

## Why it matters

This is the second plan in the epic to hit it. Each time the check fails for structural reasons and needs an operator decision, which costs time and teaches people to wave the check through.

## Suggested direction

Either run an assessment producer on the deep lane for these domains, or have the coverage check notice when no producer ran and report "not applicable" instead of failing every deliverable.
