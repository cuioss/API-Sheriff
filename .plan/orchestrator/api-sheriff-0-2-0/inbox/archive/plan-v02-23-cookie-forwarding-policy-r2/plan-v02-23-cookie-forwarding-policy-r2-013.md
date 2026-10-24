envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:48Z

component=plan-marshall:manage-change-ledger
category=anti-pattern
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=script_failure plan-marshall:manage-change-ledger:manage-change-ledger (argparse_rejection, 2 occurrences)

# manage-change-ledger invoked with an invented `list` verb, then with a top-level --plan-id

## What happened

Two consecutive rejections at 2026-10-22 17:03:

1. `manage-change-ledger list ...` - `list` is not a registered verb; the verbs are
   append, classify-outcome, query, worktree-sha.
2. The retry passed `--plan-id` at the router level (before the verb), which the router does not
   declare ("unrecognized arguments: --plan-id ...").

## Proposed rule / fix

- The read verb is `query`, and `--plan-id` belongs after the verb on this script.
- The read-verb canonicalization standard (list/read/get/query) should either converge this script
  on the common read verb or the manage-change-ledger SKILL.md canonical-invocations block should be
  cited wherever workflows read the ledger.
