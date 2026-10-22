envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:20:36Z

# Success criterion named a mechanism whose files the deliverable did not declare

- Source: Q-Gate finding cd7417 (phase 3-outline, scope_criterion_validator under_coverage, taken_into_account)
- Component: api-sheriff edge dispatch (DispatchStage, TerminalDispatch, EdgeAdmission)

## Observation

Deliverable 1's criterion required that the failing queued-for-upstream path resets the stream
and releases both its flow-control credit and its admission permit. The deliverable declared only
EdgeAdmission.java, whose change releases the permit alone; the pool-wait handling and the end of a
queued dispatch live in DispatchStage.java and TerminalDispatch.java.

## Outcome

The outline declared both files as conditional on the red test finding a gap and reworded the
criterion to credit plus permit release through the one end rule.

## Candidate rule

For each verb in a success criterion (reset, release credit, release permit), name the file that
performs it; a criterion whose mechanism crosses files the deliverable does not declare is
under-covered.
