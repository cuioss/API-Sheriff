envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-14T20:03:51Z

# Candidate lesson: pre-push-quality-gate step is marketplace/pyproject-oriented and degenerates on a Maven project

**Source signal:** orchestrator-observed run behaviour (finalize pre-push quality gate).

**Observation:** The `pre-push-quality-gate` finalize step doc is written around the plan-marshall marketplace / pyproject layout. On this Maven project:

- `derive_gate_bundles` yielded 0 bundles for the changed Maven module (`api-sheriff`), because bundle derivation keys on `marketplace/bundles/...` paths.
- The `module-tests` canonical command was absent at the default scope, so the gate had no module-test command to resolve for the module the plan changed.

The gate therefore had nothing marketplace-shaped to run and had to fall back to the project's own Maven gate (`verify -Ppre-commit` plus `test -pl api-sheriff -am`) by operator/agent judgement rather than by the step's documented contract.

**Why it matters:** On a non-marketplace project a 0-bundle derivation can read as "nothing to gate", which is the silent-skip failure mode; the actual gate came from repo knowledge, not from the step.

**Candidate corrective direction (for orchestrator classification):** The step should resolve its gate commands from the architecture-resolved canonical commands for the changed modules (build-system agnostic) and treat a 0-bundle / absent-canonical result as an explicit could-not-derive state, not as an empty pass.

**Suggested component:** plan-marshall:phase-6-finalize (pre-push-quality-gate)
