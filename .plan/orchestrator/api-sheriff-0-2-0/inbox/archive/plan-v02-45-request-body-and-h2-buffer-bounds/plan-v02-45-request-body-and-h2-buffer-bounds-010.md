envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:44Z

# Status corrected in the threat model but not in the validation matrix that restates it

- Source: Q-Gate finding a72844 (phase 6-finalize, functional-review round 4 blocking correction, fixed in 3a37e720)
- Component: validation matrix row gw-07-request-body; doc/security-threat-model.adoc

## Observation

After GW-07 was changed to PARTIAL with the untested steps named (finding 5ec4bc), the
validation-matrix row for gw-07-request-body still gave the earlier reason. Functional review round
4 raised it as a blocking correction, which cost one more review round.

## Outcome

Corrected as a section 6 inline correction (docs only): the row names the two untested gRPC
dispatch steps.

## Candidate rule

A coverage status for a threat id is stated in several places (threat-model entry, validation
matrix row, ADR amendment). When one changes, search for the threat id and update every
restatement in the same commit.
