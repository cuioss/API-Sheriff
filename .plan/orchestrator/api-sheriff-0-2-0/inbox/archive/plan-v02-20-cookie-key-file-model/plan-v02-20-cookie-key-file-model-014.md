envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:54Z

# Candidate lesson (plan-marshall tooling): two argparse rejections from finalize callers - effort resolve-target --step-id and qgate list without --phase

- Source: script-failure clusters, notations plan-marshall:manage-config:manage-config (20:49:23Z, `--step-id` not declared for `effort resolve-target`) and plan-marshall:manage-findings:manage-findings (`qgate list` missing required `--phase`; the second occurrence was this lessons-capture step's own first call)
- Suggested component: plan-marshall:phase-6-finalize (dispatch-loop resolve-target call; lessons-capture candidate collection). Route to the plan-marshall lessons-routing inbox.

## What happened

1. A finalize dispatch passed `--step-id` to `manage-config effort resolve-target`; the accepted flags are apply, caller, default, field, phase, plan-id, role, workflow, workflow-gated.
2. `manage-findings qgate list` requires `--phase`. lessons-capture.md tells the orchestrated branch to read the Q-Gate findings "across 2-refine, 3-outline, 4-plan, 5-execute, and 6-finalize" but gives no invocation, so a whole-plan call without `--phase` is the natural first try and is rejected; the caller then needs five calls.

## Corrective rule / improvement

(1) Do not invent a step-id flag on resolve-target; the step is identified by role/workflow. (2) Either show the per-phase `qgate list --phase` loop in lessons-capture.md, or let `qgate list` without `--phase` return all phases.
