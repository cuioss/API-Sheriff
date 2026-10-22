envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:11Z

# Decision-log entry kept a self-report that a fix task had made untrue

- Source: Q-Gate finding 04dbde (phase 6-finalize, functional-review false_self_report at decision log c9e032, taken_into_account)
- Component: plan decision log; request-body deadline in the edge dispatch path

## Observation

Entry c9e032 stated two properties of the request-body deadline (when it is armed and when it
fires). Fix task 11 changed both, and the entry was not updated, so the functional review flagged
it as a false self-report. Same root cause as the D3 hand-off residual (finding 739fe0), reported
on a different line of the same entry.

## Outcome

Closed at 44e2f36b with no blocking finding; the superseding decision records the shipped
behaviour.

## Candidate rule

A fix task's closing step re-reads the decision-log entries that describe the behaviour it changed
and supersedes each one; the functional review should not be the first reader to notice.
