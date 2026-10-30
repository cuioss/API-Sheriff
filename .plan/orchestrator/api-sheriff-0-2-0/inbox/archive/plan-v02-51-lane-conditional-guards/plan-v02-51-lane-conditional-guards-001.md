envelope_version=1
sender_type=plan
sender_id=plan-v02-51-lane-conditional-guards
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-30T00:32:45Z

# Candidate lesson: an operator-accepted test removal is re-raised as blocking at every new head

**Component:** plan-marshall:phase-6-finalize (functional-review / code-review weakened_test check)
**Category (suggested):** improvement

## What happened

Deliverable 1 removed `TlsEdgeActivationWiringTest.failsafeWiresMtlsSystemProperties` on purpose and replaced it with `MtlsLaneSelectionContractTest`. The weakened_test check flagged the 7 removed assertions. The operator accepted the removal at the first review prompt.

The same finding still came back as a new blocking Q-Gate finding at every later head:

- functional-review: 02cf35 (round 1), a876ff and ba4c05 (delta rounds). Each was re-closed as `accepted` with the same operator rationale.
- code-review: 3422d9 and 643067, each marked `taken_into_account` / superseded by the next round.
- verdict_refused a94280: in one code-review round the verifier refused the grading only because it read the accepted removal as mis-graded. F1-F8 were supported.

Two delta rounds found nothing blocking apart from this one finding. Even so, each round needed its own accept-close before it could close.

## Proposed rule

An operator-accepted weakened_test disposition should be keyed to the removed test identity, here the file plus the method `failsafeWiresMtlsSystemProperties` against origin/main, not to the reviewed head. Later rounds should carry it forward as already accepted, as long as the removed set is unchanged. Only a different or larger removal should re-open it. The verifier should also be told about the carried acceptance, so it does not refuse a grading because of it.

## Evidence

Q-Gate findings 02cf35, 3422d9, a876ff, 643067, ba4c05 and a94280 in phase 6-finalize of plan-v02-51-lane-conditional-guards. Decision log 01c22b and bb669f.
