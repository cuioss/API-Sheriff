envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:18:33Z

# Candidate lesson: `manage-status` rejected twice — an unregistered verb, then a flag that lives on a sibling verb

**Source signal**: script-failure cluster — `plan-marshall:manage-status:manage-status`, `exit_code=2`, `failure_kind=argparse_rejection` (2026-09-22T07:27:12Z and 2026-09-22T21:33:03Z)
**Component**: `plan-marshall:manage-status`

## What happened

Two rejections, at opposite ends of the run, of two different shapes:

1. **Unregistered verb** (phase 2-refine re-dispatch): `phase-handshake` is not a registered verb
   for `manage-status`. The rejection listed the real verb set
   (`aggregate-confidence, archive, assert-step-recorded, census, …, mark-step-done, metadata, …`).
   The invoked name is one that *phase documentation discusses at length* — the phase-entry protocol
   is described as a "phase handshake" — so the plausible-sounding verb was read off surrounding
   prose rather than from the script's own surface.
2. **Verb-scoped flag** (phase 6-finalize): `--field` is not declared for `manage-status read`
   (`['plan-id', 'store']`); it is declared on the siblings `metadata --field` and
   `update-field --field`.

## Why this is candidate-lesson shaped

Both are instances of the documented **verb-paraphrase / verb-scoped-flag** recurrence class, and
both are cheap-but-recurring: `exit_code=2` bypasses the script body entirely, so nothing is read
and nothing is written, and the only cost is a retry — which is exactly why the class survives.

The second one is the more interesting: `--field` *does* exist on the same script, one verb over.
A reader who knows the script supports field-scoped reads has every reason to assume `read --field`
works. The error message's "declared on sibling verb(s)" hint is the single most useful thing here
and is worth preserving in any similar surface.

## Candidate rule

Quote verb and flag names from the script's own `--help` or the executor mapping, never from
surrounding workflow prose, and never by analogy with a sibling verb that has the flag. Where a
flag's presence on one verb makes its absence on another surprising, that is a signal about the
*script's* surface design, not only about the caller.

## Disposition in this plan

Both recovered by retry with the correct surface; no state was corrupted (argparse rejections never
reach the script body). Recorded as evidence of the recurrence class rather than as plan defects.
