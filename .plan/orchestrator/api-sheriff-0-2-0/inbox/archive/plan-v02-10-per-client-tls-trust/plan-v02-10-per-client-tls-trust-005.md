envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:36Z

component=plan-marshall:manage-solution-outline
category=anti-pattern
source=script failure cluster plan-marshall:manage-solution-outline:manage-solution-outline (work log 6a94f5, a1bb69)

# get-deliverable takes --deliverable-number; --number is rejected

## What happened

During TASK-4 (2026-10-03T09:43:37Z), `manage-solution-outline get-deliverable --number 3` failed with exit_code=2 (argparse_rejection). The verb accepts only `--deliverable-number` and `--plan-id`.

## Rule

Copy flag names exactly from the canonical-invocation block. A shortened flag name is the usual way invented flags creep in.
