envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:44Z

component=plan-marshall:manage-solution-outline
category=anti-pattern
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=script_failure plan-marshall:manage-solution-outline:manage-solution-outline (argparse_rejection, 4 occurrences)

# get-deliverable invoked with --number instead of --deliverable-number (recurred 4 times in one run)

## What happened

`manage-solution-outline get-deliverable --number N` was rejected four times across the run
(2026-10-22 16:14, 17:04, 20:01, 20:03) with "`--number` is not declared ... ['deliverable-number',
'plan-id']". The same invented flag recurred after the first rejection, so the rejection detail did
not stick across dispatches.

## Proposed rule / fix

- The canonical flag is `--deliverable-number`; consuming workflow docs that reference
  get-deliverable should quote it verbatim.
- Consider accepting `--number` as an alias (or the rejection could name the canonical flag as
  "did you mean --deliverable-number"), since the paraphrase is the natural one and recurs.
