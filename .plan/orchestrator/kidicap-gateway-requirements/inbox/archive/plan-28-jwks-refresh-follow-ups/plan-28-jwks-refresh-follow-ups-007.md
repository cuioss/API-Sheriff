envelope_version=1
sender_type=plan
sender_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-13T14:02:24Z

# Candidate lesson: script calls rejected by argparse because agents invented verbs or flags

**Source:** plan-28-jwks-refresh-follow-ups, work log `[ERROR] ... script_failure ... failure_kind=argparse_rejection` markers. Every rejection was recovered within the same step, and none changed any outcome.

## Records

| Time (UTC) | Phase | Notation | Rejection |
|---|---|---|---|
| 07:27:53 / 07:27:54 | 4-plan | plan-marshall:manage-findings:manage-findings | `qgate add` usage error (required flags missing or mis-named; two attempts) |
| 07:30:46 | 4-plan | plan-marshall:manage-findings:manage-findings | `qgate query` is not a registered verb (valid: add, clear, list, resolve, resolve-by-rule, resolve-evidenced) |
| 08:20:06 | 5-execute | plan-marshall:manage-solution-outline:manage-solution-outline | `get-deliverable --number` is not declared; the flag is `--deliverable-number` |
| 09:28:48 | 5-execute | plan-marshall:manage-architecture:architecture | `--plan-id ... search --content --pattern '...'` rejected (a `--plan-id` placement or acceptance problem on `architecture search`) |

## Pattern

These are all cases of the documented "never invent script subcommands" signatures: a paraphrased verb (`query` for `list`), a paraphrased flag (`--number` for `--deliverable-number`), and a `--plan-id` placement or declaration error. The persona rule exists, and it still did not stop the calls from being guessed.

## Proposed improvement

When argparse rejects an unknown verb or flag, the executor could suggest the closest valid option (for example "did you mean `list`" or "did you mean `--deliverable-number`") in the rejection detail. Each recovery would then take one step. Alternatively, the call sites that phase-4 and phase-5 workflow docs prescribe could quote the canonical invocation blocks verbatim.

## Scope hint

Component candidates: plan-marshall:tools-script-executor and plan-marshall:persona-plan-marshall-agent. These belong to the marketplace, a foreign store from this repository.
