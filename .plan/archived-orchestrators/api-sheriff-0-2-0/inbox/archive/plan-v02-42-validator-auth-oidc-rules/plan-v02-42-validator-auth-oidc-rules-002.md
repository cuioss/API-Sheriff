envelope_version=1
sender_type=plan
sender_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T11:36:46Z

# Candidate lesson: pre-push-quality-gate resolution fails in a Maven repo (plan-marshall tooling)

**Routing:** plan-marshall tooling candidate. Send it to the plan-marshall lessons-routing inbox, not to the local store.
**Suggested component:** plan-marshall:phase-6-finalize (pre-push-quality-gate / derive_gate_bundles) and plan-marshall:manage-architecture (resolve)
**Category:** bug
**Source:** orchestrator observation during PLAN-V02-42 finalize. The drain should verify it against the plan work log.

## What happened

On API-Sheriff, a multi-module Maven reactor with no marketplace bundles, `derive_gate_bundles`
reported every Java module path as `unresolved`. That function backs the pre-push-quality-gate
step and reads the footprint per bundle. Separately, `architecture resolve` had no
test-compile or module-tests canonical command at the default scope. The arms of the gate
document assume a pyprojectx / marketplace-bundle layout, so they did not map cleanly onto a
Maven project. The orchestrator had to work out the correct Maven gate command by hand.

## Corrective action

derive_gate_bundles should fall back to module resolution through the architecture inventory
(`which-module`) when the project has no bundles. It should not return the whole footprint as
`unresolved`. `architecture resolve` should expose a test-compile/module-tests canonical at
default scope for Maven, or the gate document should name its Maven arm explicitly.
