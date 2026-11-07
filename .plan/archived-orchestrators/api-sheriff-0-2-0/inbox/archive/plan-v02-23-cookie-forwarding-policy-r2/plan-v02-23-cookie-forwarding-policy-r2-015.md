envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:57Z

component=plan-marshall:manage-execution-manifest
category=anti-pattern
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=script_failure plan-marshall:manage-execution-manifest:manage-execution-manifest (argparse_rejection, 1 occurrence)

# manage-execution-manifest invoked with an invented loop-back verb

## What happened

At 2026-10-23 05:56, `manage-execution-manifest loop-back` was rejected: not a registered verb
(compose, lanes, read, reconcile, record-step, refire-report, step-params, validate,
validate-loadable). The call was made while looping back from finalize into execute for an
additional task.

## Proposed rule / fix

- Loop-back is a phase-transition concern owned by the finalize/execute workflow (manage-status
  phase transitions plus manage-tasks), not a manifest verb; the workflow step that describes the
  loop-back should name the exact sanctioned calls so the verb is not extrapolated from the prose.
