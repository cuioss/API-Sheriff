envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-20T20:32:28Z

## What landed

plan-v02-12-idp-addressing-model shipped as #492 (merged, squash 03cb4755 via the merge queue).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
pr=#492
merge_state=merged
cleanup_owed=false
deliverables_total=9
deliverables_done=9
total_tokens=10535763
total_wall_seconds=unknown
harness=unknown
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=7
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
```

## Residue

- Deliverables D1-D4 (/bff/* relocation) landed earlier in #450; #492 carries D5-D9 (back_channel_origin key, one Keycloak identity on 26.8.1, ADR-0069, Dependabot keycloak group with group-by: dependency-name, release-runbook Keycloak line).
- The final code-review record is an operator override at b32b4679: after the triaged PR-comment fixes the operator waived the re-fire of simplify, security-audit, functional-review and code-review at that HEAD. The last full code-review round (at e38d81b1) had 0 blocking, 2 advisory. No code-review counts are transcribed above for that reason.
- Spec drift for the epic ledger: PLAN-V02-12's spec still calls D1/D2 unproven and says "no version bump"; D1 shipped in c9fc294c, the 26.8.1 Failsafe/Playwright/compose-sample runs settle D2, and the Keycloak test image was bumped 26.5.7 -> 26.8.1.
- cuioss-review-bot is dead (Vertex billing disabled); main removed it in #493/#494, and this plan's automatic-review step-params were reclassified to required_bots=coderabbit. CLAUDE.md § Git Workflow still names it as a merge gate (candidate-lesson 005 in this inbox).
- Remaining advisory: BackChannelOriginActivationWiringTest does not read Keycloak entrypoint/env_file overrides (neither compose file uses them).
- Post-merge main CI for 03cb4755 was green except one Integration Tests run still in progress when the run ended; the orchestrator owns the post-merge verification.
