envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:47Z

# Hand-maintained lists mirroring a code set are re-raised until bound by a test

**Surface:** API-Sheriff review practice (CodeRabbit path instructions in this repo)
**Signal:** automated-review (pr-comment d419f9 fixed; the configuration.adoc doc-list thread)
**Plan:** plan-v02-05-response-hygiene, PR #526

## Observation

The repo's CodeRabbit path instructions classify a hardcoded list that mirrors an authoritative code set as a defect.

- d419f9: `ForwardPolicyStageTest` hardcoded the conditional-header names in `@ValueSource` literals; fixed by streaming `ForwardPolicyStage.CONDITIONAL_HEADERS` via `@MethodSource` plus a separate test pinning the five RFC 9110 names.
- `doc/configuration.adoc` hand-maintained the identity-header set and protocol tiers. Declining the comment as out of scope only got it re-raised on the next review. Resolved by adding `DocumentedHeaderSetsContractTest`, which binds the documented sets to their constants.

## Candidate rule

In this repo, do not decline a "hand-maintained mirror of a code set" review comment as out of scope — it will be re-raised. Either derive the list from the constant (tests) or bind the documented list with a contract test (docs), in the same PR.
