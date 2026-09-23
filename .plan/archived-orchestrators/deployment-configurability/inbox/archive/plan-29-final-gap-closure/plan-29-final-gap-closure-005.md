envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:12:12Z

# Candidate lesson: review_completeness --participated-bots given a bare bot_kind

Source signal: script-failure cluster, notation `plan-marshall:automatic-review:review_completeness`.

## What happened

Work log 2026-09-23T14:26:03Z, during the branch-cleanup pre-merge review barrier:

> [ERROR] script_failure notation=plan-marshall:automatic-review:review_completeness exit_code=1
> failure_kind=script_internal_failure detail=status: error / error: malformed_bot_flag /
> "--participated-bots expects bot_kind:evidence_kind pairs but received the token 'coderabbit',
> which is not a pair. ..."

The caller passed a bare bot_kind (`coderabbit`) where the flag needs `bot_kind:evidence_kind`
pairs. The script correctly refused it rather than guessing absence. The barrier was then
re-run correctly and reported clean ("zero pending pr-comment findings, required-bot
participation complete").

## Candidate rule

When the pre-merge barrier composes `--participated-bots`, build every token as
`{bot_kind}:{evidence_kind}` (for example from the participation evidence the review fetch
already recorded), never a bare bot name. The branch-cleanup barrier doc could show a
concrete worked example of the pair format at its call site.
