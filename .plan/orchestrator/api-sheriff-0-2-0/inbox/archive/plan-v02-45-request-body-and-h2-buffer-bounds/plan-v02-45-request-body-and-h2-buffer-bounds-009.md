envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:36Z

# Threat-model coverage status said COVERED while part of the wiring was untested

- Source: Q-Gate finding 5ec4bc (phase 6-finalize, code-review round 2 inline correction, fixed in f6734ab3)
- Component: doc/security-threat-model.adoc (GW-07); ADR-0060 Amendment A2

## Observation

GW-07 was marked COVERED and ADR-0060 A2 said only one wiring step was untested. The test in
question also supplied its own dispatch function, so two steps of the production wiring were not
exercised by it.

## Outcome

Corrected as a section 6 inline correction (docs only): status PARTIAL, both untested steps named.

## Candidate rule

A threat-model entry is COVERED only when the tests drive the production wiring end to end; a test
that substitutes its own dispatch function proves the unit, and the entry names the substituted
steps and stays PARTIAL.
