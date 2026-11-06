envelope_version=1
sender_type=plan
sender_id=plan-v02-56-final-cleanup
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-06T15:16:45Z

# Candidate lesson: review_completeness rejects bare bot names in --participated-bots

- component: plan-marshall:phase-6-finalize (caller of plan-marshall:automatic-review:review_completeness)
- category (suggested): anti-pattern
- signal source: script_failure marker in the work log and the script execution log (13:49:14)

## Observation

During the branch-cleanup pre-merge review barrier, the following call exited 1 with
`error: malformed_bot_flag`:

    review_completeness check --plan-id ... --required-bots coderabbit --optional-bots sourcery --participated-bots coderabbit --refused-bots sourcery ...

The verb expects `bot_kind:evidence_kind` pairs (for example `coderabbit:inline`). It
rejects a bare `coderabbit` on purpose, because dropping it silently would make the bot
count as absent and block the merge for no real reason. The retry with a pair passed
three seconds later.

## Defect shape

The caller wrote `--participated-bots` with the same bare-name shape as
`--required-bots` / `--optional-bots` / `--refused-bots`. In the same argument list,
the participation flag alone needs pairs. Nothing in the call site shows this
asymmetry.

## Suggested corrective direction

In the phase-6-finalize pre-merge barrier instructions, put the pair form
(`bot_kind:evidence_kind`, for example `coderabbit:inline`) next to the
`--participated-bots` flag in the documented invocation. Or let
`malformed_bot_flag` list the accepted evidence_kind values, so the retry can be
built directly from the error.
