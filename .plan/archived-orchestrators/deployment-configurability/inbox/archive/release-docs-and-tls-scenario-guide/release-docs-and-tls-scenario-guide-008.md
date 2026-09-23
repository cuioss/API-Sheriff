envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:23:27Z

component=plan-marshall:phase-3-outline
category=bug
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# phase-3-outline self-transitioned to 4-plan before q-gate validation; its findings were never consumed

## What happened

The phase-3-outline agent advanced the plan to `4-plan` itself, before the orchestrator's q-gate-validation for 3-outline ran. The 3-outline q-gate findings (eb29c6, 8c18c6, 444e7e) were then filed against a phase the plan had already left, and phase-4-plan does not read 3-outline q-gate findings, so they would have been silently dropped. The orchestrator had to manually re-open 3-outline, resolve the findings, and re-run 4-plan.

## Corrective action

The outline agent must return to the orchestrator without calling the phase transition; the transition to 4-plan belongs after q-gate validation reports zero pending findings. As a structural guard, the 3-outline -> 4-plan transition (or phase-4-plan entry) should refuse while any 3-outline q-gate finding is `pending`.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Affected findings: eb29c6, 8c18c6, 444e7e (all resolved only after the manual re-open)
