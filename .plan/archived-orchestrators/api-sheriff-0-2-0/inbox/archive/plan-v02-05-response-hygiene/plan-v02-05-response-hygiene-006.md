envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:52Z

# Outline consumer sweep missed test-source callers of changed signatures

**Surface:** `plan-marshall:phase-3-outline` (consumer sweep for signature changes)
**Signal:** Q-Gate records c7da03 (error), 008d09 (error), bf7c98 (warning) — 3-outline, all taken_into_account
**Plan:** plan-v02-05-response-hygiene

## Observation

The first outline pass claimed its consumer sweep enumerated every caller, but missed three test-source call sites that a signature change breaks:

- c7da03: adding record component `keepIdentityHeaders` to `RouteConfig` changes the canonical constructor; `ConfigModelContractTest` calls `new RouteConfig(...)` positionally (two sites) and was in no deliverable.
- 008d09: adding a parameter to `ResponseStage.relay(...)` breaks `GatewayEdgeRouteTest` (two calls), in no deliverable.
- bf7c98: `DispatchStageTest` was listed, but its change entry named only one call and missed a `responseStage.relay(...)` call.

## Candidate rule

For any record-component or method-signature change, the consumer sweep must be run per changed symbol over the full inventory including test sources, and every hit must appear as an explicit change entry (not just as an affected file). Positional record-constructor calls are the easiest to miss.
