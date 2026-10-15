envelope_version=1
sender_type=plan
sender_id=plan-v02-28-validator-limit-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T15:18:47Z

component=plan-marshall:phase-6-finalize
category=bug

# Pre-push quality gate records module-tests DEGRADED in a Maven project although the gate runs the tests

## What happened

In this Maven project the pre-push quality gate's per-bundle arm derives no bundles, and `module-tests` has no default-scope canonical command. The gate therefore records `module-tests` as DEGRADED, even though `verify -Ppre-commit` (the quality-gate canonical that actually ran) executes the tests.

## Lesson

The per-bundle arm is a marketplace-bundle concept. In a project with no bundles it should be skipped as not applicable, not reported as degraded. Where `module-tests` has no default-scope canonical, the gate should check whether the canonical it did run already covers the test phase (for Maven, `verify` includes `test`) and record coverage accordingly, instead of a DEGRADED status.

## Expected fix

- Treat "no bundles derived" as not applicable for the per-bundle arm.
- Map `module-tests` coverage to the executed canonical when that canonical includes the test lifecycle phase, or let the project declare a default-scope `module-tests` canonical (for example `test -pl api-sheriff -am`).

## Impact

A permanent false DEGRADED status in every Maven-project finalize trains operators to ignore the gate's status.
