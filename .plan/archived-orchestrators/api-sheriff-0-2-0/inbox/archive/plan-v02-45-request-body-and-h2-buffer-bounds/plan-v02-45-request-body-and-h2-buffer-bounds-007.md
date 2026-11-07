envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:20Z

# Threat-model entry overstated how many tests were red before the fix

- Source: Q-Gate finding ad7b99 (phase 6-finalize, functional-review inline correction, fixed in 44e2f36b)
- Component: doc/security-threat-model.adoc (GW-07)

## Observation

GW-07 said all four slow-request-body tests were red before the deadline existed; the plan's
red-proof record shows three. The functional review raised it as an advisory false self-report.

## Outcome

Corrected on the branch as a review-rubric section 6 inline correction (docs only).

## Candidate rule

Counts of red-before-fix tests in the threat model are copied from the recorded red proof, not
from the deliverable's intent; when one of the planned tests turned out green before the fix, the
doc says so.
