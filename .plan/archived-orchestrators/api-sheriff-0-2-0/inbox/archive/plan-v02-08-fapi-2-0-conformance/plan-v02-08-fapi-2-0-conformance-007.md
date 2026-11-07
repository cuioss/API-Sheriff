envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:07:28Z

# Candidate lesson: the same guessed flag is rejected again in every fresh dispatch

## Pattern

A dispatched step starts with no memory of the previous one. When a script's flag name differs from the name its neighbours use, each new dispatch guesses the neighbour's name, is rejected by argparse, recovers through `--help`, and continues. The rejection is harmless each time (exit 2, no state change) and is logged each time, but nothing carries the correction to the next dispatch, so the same wrong call is made again. Over a long plan this is a steady tax and it inflates the script-failure signal with entries that are not defects of the run.

## What the record shows

Three caller-side clusters, all `failure_kind=argparse_rejection` or the equivalent exit 2:

- `manage-solution-outline get-deliverable --number N`. The declared flag is `--deliverable-number`. Rejected in six separate dispatches: an outline re-entry and execute envelopes 1, 2, 4, 5 and 6, nine log lines in total (work log 6a94f5, repeated on 2026-10-01 at 09:02Z, 10:21Z, 14:01Z, 21:04Z, 21:56Z and on 2026-10-02 at 07:28Z; 97fd7a, b1e24a, ec5d5b). The sibling `manage-adr read --number N` takes exactly the guessed spelling, which is the likely source of the guess.
- `architecture search` with undeclared narrowing flags: `--module` (75f9f3), `--module` and `--lines` (285308), `--path-glob` (0696a5). The verb declares `category, content, ignore-case, literal, pattern` and the routing flags only. Each time the caller fell back to reading a known file.
- `manage-references set-list --values ""`: a quoted empty string is rejected with "expected one argument"; the working form to clear a list is `--values=` (work log aee31e, decision log 57787b).

None of these changed state. Together they account for 13 executor-emitted or caller-emitted rejection lines in the work log (nine, three and one).

## How to recognise it next time

- The same `argparse_rejection` detail string appears under the same hash id in more than one dispatch of one plan.
- The rejected flag is the spelling a sibling script declares.

## Suggested direction (for the orchestrator to classify)

- Tooling: accept `--number` as an alias on `get-deliverable`, or rename for consistency with `manage-adr`; state in the `architecture search` help that scoping by module or path is not available and name the verb that does it; document `--values=` as the way to clear a list, or accept an empty string.
- Process: when a dispatch logs an argparse rejection, the orchestrator can carry the corrected invocation in the next dispatch's prompt. One line would have saved five of the six repeats.
- For the signal gate: argparse rejections with no state change are a different class from script-internal failures and could be bucketed separately, so the cluster count reflects defects of the run.
