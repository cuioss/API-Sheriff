envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:53:03Z

# Candidate lesson (tooling bug): two orchestration detectors disagree on the `.plan/local/orchestrator/` source_id form

Source: orchestrator observation 1 (this run). Likely a plan-marshall bundle defect, not a project defect.

## What happened

For source_id `.plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-17-...md`:

- `manage-status transition`'s mailbox probe reported `not_orchestrated` with `detection=not_orchestrator_pointer`.
- `plan-orchestrator orchestrator inbox detect` on the same source_id returned `orchestrated=true epic=kidicap-gateway-requirements`.

The two detectors apply different path-form recognition to the same pointer, so the mailbox probe silently skips a plan
that is in fact orchestrated (delivered messages would not be surfaced at transition).

## Corrective rule / suggested fix

There should be one shared orchestrator-pointer parser; `manage-status transition` should delegate to the same predicate
`inbox detect` uses (which accepts the `.plan/local/orchestrator/{slug}/plans/...` form), with a test pinning both
detectors to the same verdict over each accepted path form.

## Components

plan-marshall:manage-status (transition mailbox probe); plan-marshall:plan-orchestrator (inbox detect).
