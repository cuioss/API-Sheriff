envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:17:05Z

# Candidate lesson: the two defects this PR actually introduced were both OUTSIDE the diff, and only a review-body comment could carry them

**Source signal**: PR #341 `review_body` comment `5b7752` (coderabbitai, resolution `fixed`)
**Component**: `doc/configuration.adoc`, `doc/user/README.adoc`

## What happened

CodeRabbit posted two findings it could not attach inline, because GitHub refuses inline comments
outside the diff range. Both were real defects **this PR introduced**:

1. `doc/configuration.adoc:3058-3060` — "The mode set is *closed*: `strict`, `lenient`, `minimal`,
   and nothing else." The PR added `paranoid` and updated all three schema enum sites, so the guide
   now told an operator that a valid value would fail the boot. It also contradicted
   `doc/user/README.adoc:238`, which already listed four.
2. `doc/user/README.adoc:249-250` — the default-profile prose described `strict` as "the tightest
   posture". With `paranoid` added, that is no longer true; the text needed to describe `strict`
   accurately and say `paranoid` must be selected explicitly given its false-positive profile.

Both landed in the `review_body` kind, which carries an **empty `thread_id`** and cannot be
resolved as a thread at all.

## Why this is candidate-lesson shaped

Two distinct mechanisms, both structural:

1. **Adding a member to a closed set falsifies every prose restatement of that set, including ones
   the diff never touches.** The diff is a poor proxy for the blast radius of a set change; a
   content search for the *surviving* members is the right one.
2. **Outside-diff-range findings are second-class by transport, not by importance.** A workflow that
   treats inline comments as the work list and review bodies as noise will systematically drop the
   findings about *what the change broke elsewhere* — which is exactly the class a diff-scoped
   reviewer cannot otherwise reach. Relatedly, a `review_body` row owes a reply but can never be
   resolved, so it reports unresolved forever; the discriminator is the empty `thread_id`, not the
   row count.

## Candidate rule

Read review-body / outside-diff-range findings as a first-class work list. When a change adds or
removes a member of a closed set, search the whole tree for restatements of the *remaining*
members before submitting.

## Disposition in this plan

Both fixed by TASK-016 (commit `b4ba2bc`). The reviewer's third item (derive the profile-enum
inventory rather than pinning three literal pointers) was taken into TASK-017 as the same sink
class as the `AwaitsTest` comment.
