envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:29Z

# Script failure: manage-status transition refused twice because commits had no change-ledger row

- Source: script log [ERROR] 97c14d (2026-10-21T13:43:30Z) and d73a75 (2026-10-21T15:40:19Z), notation plan-marshall:manage-status:manage-status transition, exit 1
- Component: phase-5-execute settlement bookkeeping (Step 10a) / phase transition gate

## Observation

`transition --completed 5-execute` returned `error: execute_commits_unrecorded` twice. The
first time one of seven worktree-branch commits had no kind=change ledger row; the second time,
after a loop-back from finalize, eight commits had none. The error message names the remedy (run
the Step 10a settlement bookkeeping for the unrecorded commits, then retry), and the retry
succeeded both times (transition calls e5348b, 2ffb47).

## Outcome

No lasting harm: each refusal cost one transition attempt (about 25-30 s each, mostly the
handshake capture) before the bookkeeping was run.

## Candidate rule

Run the Step 10a settlement bookkeeping before the first `transition --completed 5-execute`, and
always after a loop-back fix pass, since the loop-back commits are the ones that end up without
ledger rows.
