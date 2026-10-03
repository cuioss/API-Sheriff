envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T20:57:33Z

# Candidate lesson: derive_gate_bundles yields zero bundles for a Maven reactor; maven tests_run reports only the last module

**Source signal**: script-failure cluster / verification-evidence gap (plan route-header-matcher-defects)

## What happened

Two separate evidence defects in the build/verification path:

1. `derive_gate_bundles` returned zero gate bundles for this plan's footprint (Java sources across the api-sheriff and integration-tests Maven modules). The derivation appears shaped for marketplace-bundle repositories (`marketplace/bundles/{bundle}/...` paths) and has no mapping for a Maven reactor, so a real code footprint derived an empty gate set.
2. The build-maven wrapper's `tests_run` field reports only the test count of the last reactor module executed, not the reactor total. A multi-module run therefore under-reports how many tests ran, which weakens "tests actually ran" evidence.

## Candidate rule / defect

- Gate-bundle derivation must fail loud (or fall back to the project's module list from the architecture inventory) when a non-empty code footprint derives zero bundles; an empty set must never read as "nothing to gate".
- The maven result parser should sum `Tests run:` across all reactor modules (or expose a per-module breakdown plus total).

## Suggested component

plan-marshall:build-maven (result parsing) and the finalize/verification gate-bundle derivation — plan-marshall bundle defects.
