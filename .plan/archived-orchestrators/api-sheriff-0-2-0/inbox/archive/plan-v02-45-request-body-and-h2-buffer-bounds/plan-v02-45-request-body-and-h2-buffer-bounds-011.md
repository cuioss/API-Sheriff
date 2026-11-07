envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:54Z

# "Run by no test" versus "asserted by no test": a coverage claim worded more strongly than the evidence

- Source: Q-Gate finding 677474 (phase 6-finalize, code-review round 4 advisories F1-F3, fixed in c62180a7)
- Component: doc/security-threat-model.adoc (GW-07), validation matrix, ADR-0060 A2; GrpcStreamIdleBound

## Observation

Three advisories in one round:

- F1: GW-07, the validation-matrix row and ADR-0060 A2 said two gRPC wiring steps are run by no
  test, although GrpcProxyIT runs them; what is missing is an assertion on them.
- F2: a cross-reference to "the same edge class" pointed at the wrong test class.
- F3: no test reached the refusal of a non-positive timeout in GrpcStreamIdleBound.withTimeout.

## Outcome

Corrected as a section 6 inline correction: wording now "asserted by no test", the reference
fixed, and a test added for the non-positive-timeout refusal.

## Candidate rule

Distinguish "executed by a test" from "asserted by a test" when stating coverage gaps, check the
integration suites before claiming a path is not run, and give every argument-guard branch of a
new bound type its own test when the type is introduced.
