envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:02Z

# Decision-log hand-off paragraph left stating behaviour that a later fix task changed

- Source: Q-Gate finding 739fe0 (phase 6-finalize, functional-review residual at D3.R1, taken_into_account)
- Component: plan decision log (D3 hand-off entry c9e032)

## Observation

The D3 hand-off paragraph in the decision log described the request-body deadline as it behaved
before fix task 11. After the fix no corrected paragraph was recorded, so the log's D3 result
contradicted the shipped behaviour and the functional review raised it as a residual.

## Outcome

Closed at 44e2f36b: the verifier accepted a verdict with no blocking finding; a later decision
(4239e4) supersedes the paragraph.

## Candidate rule

When a fix task changes behaviour that a decision-log entry reports as a deliverable's result,
append a superseding entry in the same task that restates the result against the new behaviour.
