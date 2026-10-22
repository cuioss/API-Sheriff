envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:20:54Z

# User-doc update conditioned on one deliverable while several could change the stated bounds

- Source: Q-Gate finding c89296 (phase 3-outline, scope_criterion_validator under_coverage, taken_into_account)
- Component: doc/user/endpoint-routes.adoc

## Observation

The documentation deliverable promised that no doc would still state a value or behaviour D1-D5
changed, but it planned to edit doc/user/endpoint-routes.adoc only if D2 changed the request-body
window. That user doc states the edge transport bounds and their status codes, so a whole-body
bound from D3 or a gRPC stream-idle bound from D4 would have left it stating the old behaviour.

## Outcome

D6 now updates endpoint-routes.adoc whenever D2, D3 or D4 changes a stated window or bound, and
leaves it unchanged only if none did.

## Candidate rule

Condition a doc edit on every deliverable that can change a value the doc states, not on the one
the outline first thought of; a content search over the docs for the bound's name and status code
gives the list of docs to check.
