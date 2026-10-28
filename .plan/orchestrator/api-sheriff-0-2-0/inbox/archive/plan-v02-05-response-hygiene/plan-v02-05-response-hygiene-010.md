envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:39:09Z

# module_mapping_validator false positives: doc targets and unattributed IT config

**Surface:** `plan-marshall:phase-4-plan` (`module_mapping_validator`) / `plan-marshall:manage-architecture` inventory attribution
**Signal:** Q-Gate records 90b9b3 and 8af987 — 4-plan, both accepted
**Plan:** plan-v02-05-response-hygiene

## Observation

- 90b9b3: implementation task 1 also edited `doc/development/boot-validation-rules.adoc` (module `documentation`) while its test task lives in `api-sheriff`; the validator flagged a test/impl module mismatch although every code target resolved to `api-sheriff`. A documentation target has no test pairing by nature.
- 8af987: task 8 edited `integration-tests/src/main/docker/sheriff-config/endpoints/origin-headers.yaml` and `assets.yaml`; `architecture which-module` resolves no module for them (IT stack config is not attributed to `integration-tests`), so the validator flagged the correct IT pairing as a mismatch.

Both were accepted with rationale; `doc/developer/test-impl-mapping.adoc` (the suggested escape hatch) does not exist in this repo.

## Candidate rule

The validator should ignore documentation-module targets when the task's code targets match the test module, and the inventory should attribute files physically under a module directory (e.g. `integration-tests/src/main/docker/**`) to that module instead of `null`.
