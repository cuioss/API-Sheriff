envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:39:14Z

# PR body and test rationale went stale after an in-run scope narrowing

**Surface:** `plan-marshall:phase-6-finalize` (PR body refresh, functional review)
**Signal:** Q-Gate records e87de7 and 12b865 — 6-finalize, error, functional-review, taken_into_account
**Plan:** plan-v02-05-response-hygiene, PR #526

## Observation

During the run the forward-all change narrowed from "withhold all five conditional validators" to a two-validator GET/HEAD gate with `headers_deny` folding.

- e87de7: the PR #526 body still described withholding all five validators (false self-report) until the functional review caught it.
- 12b865: the replacement test `forwardAllWithholdsValidatorsWhenToggleOff` no longer named the ADR-0037 `not_modified` decision as its reason; fixed in 3f9b6b62.

## Candidate rule

When a fix round changes the delivered behaviour (not just its implementation), regenerate the PR body from the final diff and re-check that replaced tests keep the rationale comment of the test they replace — before the functional review rather than after it.
