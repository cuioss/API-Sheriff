envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:17:53Z

# Candidate lesson: the review-completeness guard rejected the caller's bot list, at the pre-merge barrier

**Source signal**: script-failure cluster — `plan-marshall:automatic-review:review_completeness`, `exit_code=1`, `failure_kind=script_internal_failure` (2026-09-23T05:32:32Z, during the pre-merge barrier)
**Component**: `plan-marshall:automatic-review` — `review_completeness`

## What happened

The guard was invoked with `--participated-bots coderabbit` and refused:

> `--participated-bots` expects `bot_kind:evidence_kind` pairs but received the token 'coderabbit',
> which is not a pair. A bare `bot_kind` neither proves participation nor is a valid absence:
> silently dropping it would resolve the bot to absent (a blocking state) and manufacture a false
> merge block, so it is rejected as a caller error.

The refusal reasoning is **correct and well-designed** — it is a textbook fail-loud on an
under-determined input, and it names exactly why silence would be worse. The candidate lesson is not
about the guard's behaviour; it is that the *caller* got the argument shape wrong at the single
highest-stakes moment in the run, nineteen seconds before the pre-merge barrier verdict.

## Why this is candidate-lesson shaped

Two things generalize:

1. **This is the argparse/flag-shape recurrence class showing up at a merge gate.** The same plan
   hit four other flag-shape rejections (`manage-status`, `manage-architecture`,
   `manage-solution-outline`, `manage-references`). Those cost a retry. This one sat on the path to
   a merge decision, where a caller that "handled" the error by falling back to a looser check would
   have converted a caller bug into a false green.
2. **The guard's own error text is the model to copy.** It states what it received, why the value is
   neither proof nor absence, what the silent-drop alternative would have produced, and that the
   rejection is the caller's fault. Guards that fail closed should explain the *direction* of the
   failure this explicitly.

## Candidate rule

For a compound-token flag (`a:b` pairs), the caller constructs the token from the same source that
defines the pair vocabulary, never by joining a bare identifier. Where a guard on a merge path
rejects its input, the correct response is to fix the invocation and re-run — never to substitute a
looser check.

## Disposition in this plan

Recovered in-run: the barrier re-ran and recorded "Pre-merge review barrier: clean — zero pending
pr-comment findings, required-bot participation complete, proceeding to merge" at 05:32:51. No
weakened fallback was used. The underlying invocation-shape defect is a plan-marshall tooling
matter, outside PLAN-28's scope.
