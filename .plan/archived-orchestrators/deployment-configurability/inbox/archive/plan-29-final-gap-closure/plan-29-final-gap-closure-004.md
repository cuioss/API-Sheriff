envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:12:11Z

# Candidate lesson: qgate list invoked without required --phase

Source signal: script-failure cluster, notation `plan-marshall:manage-findings:manage-findings`.

## What happened

Work log 2026-09-23T11:05:18Z:

> [ERROR] (plan-marshall:execute-script:2) script_failure notation=plan-marshall:manage-findings:manage-findings
> exit_code=2 failure_kind=argparse_rejection detail=Add the required flag(s) to
> `plan-marshall:manage-findings:manage-findings qgate list`: ['phase']

It happened at the 5-execute -> 6-finalize boundary, just before phase-6-finalize loaded. That
points to the orchestrator-side Q-Gate pending check. A `qgate list` call was issued without
`--phase`, which is required. This is the known "missing --phase" argparse-rejection recurrence
signature.

## Candidate rule

Every `manage-findings qgate list` call must carry `--phase {phase}`. There is no cross-phase
form. A caller that needs a cross-phase count loops over the five phases. Recurrence of this
signature suggests the orchestrator workflow's phase-boundary Q-Gate check needs a verbatim
canonical invocation with `--phase` at its call site.
