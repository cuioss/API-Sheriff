envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:40Z

component=plan-marshall:phase-6-finalize
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=Q-Gate 6-finalize finding bf07b4 (functional review, taken_into_account)

# Operator-ruled removal was not recorded against the frozen success criterion that required the removed item

## What happened

Frozen criterion D5.C5 required a specific LogRecord Javadoc and template. During execution an
operator ruling (9331d8) removed that item, but the ruling was never recorded against the
criterion. The finalize functional review then flagged a residual: the frozen criterion still
demanded something the operator had deliberately removed, and the criterion had to be rewritten
and re-authorized late in finalize.

## Why it matters

Frozen criteria are the contract the functional review verifies. An operator ruling that changes
scope without touching the criterion leaves a guaranteed residual for the reviewer and costs a
review round.

## Proposed rule / fix

- When an operator ruling removes or changes an item that a frozen success criterion names, rewrite
  that criterion and run criteria-authorize in the same step as recording the ruling.
- Tooling could check, when a ruling is recorded, whether any frozen criterion text references the
  affected artifact, and prompt for the rewrite.
