envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:33:27Z

# Candidate lesson: the architecture inventory indexes no YAML/JSON, so a carrier sweep over it is incomplete

- Source: Q-Gate findings fb4754 (3-outline, coverage gap) and 1dd6d0 (3-outline, sweep result), resolution taken_into_account
- Suggested component: plan-marshall:manage-architecture / plan-marshall:phase-3-outline

## What happened

`architecture find '*.yaml'` returned 0 and `architecture search --content` missed gateway.schema.json and cookieboot/gateway.yaml, which both carried `encryption_key`. The outline's carrier search for a removed config key therefore did not cover test/main resources, workflows, benchmarks, deployment or demo-client. A tracked-file sweep outside the inventory found one uncovered carrier: .github/workflows/config-validation.yml (it set SESSION_ENCRYPTION_KEY for the offline validation of the cookie overlays).

## Corrective rule

When retiring a configuration key, do not treat an inventory content search as exhaustive: the inventory does not walk *.yaml/*.yml/*.json or .github/**. Add an explicit tracked-file sweep (or a named file list read directly) over non-inventoried carriers to the outline verification and to the exit condition.
