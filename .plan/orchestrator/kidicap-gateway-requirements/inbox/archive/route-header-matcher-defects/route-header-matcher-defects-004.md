envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T20:57:22Z

# Candidate lesson: phase-transition mailbox probe and `orchestrator inbox detect` disagree on orchestration

**Source signal**: script/orchestration inconsistency observed during the run (plan route-header-matcher-defects)

## What happened

For the same plan `source_id` pointer (a `.plan/local/orchestrator/...` path to the epic's staged plan spec), the phase-transition mailbox probe reported `not_orchestrated`, while `orchestrator inbox detect` reported `orchestrated` (epic kidicap-gateway-requirements). Two detectors of the same fact disagree, so phase-transition mailbox delivery can be silently skipped for an orchestrated plan while finalize (which uses `inbox detect`) correctly routes to the epic.

## Candidate rule / defect

Orchestration detection must have one implementation shared by every caller; the mailbox probe should delegate to `orchestrator inbox detect` (or the same resolver) rather than parse `source_id` itself. Likely cause: the probe expects a tracked `.plan/orchestrator/...` prefix and does not accept the `.plan/local/orchestrator/...` form. Add a parity test over both pointer forms.

## Suggested component

plan-marshall:plan-orchestrator (inbox detect) and the phase-transition mailbox probe — plan-marshall bundle defect.
