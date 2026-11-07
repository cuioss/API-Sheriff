envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:04Z

component=plan-marshall:phase-6-finalize
category=improvement
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=finalize review rounds

# review_support attribution delta scope equalled the full plan footprint, so "clean delta then closing full round" doubled review cost

## What happened

Three times during finalize, the review_support attribution delta scope covered all 8 deliverables
and all 93 changed files, i.e. it was identical to the full-plan scope. The rule "run a clean delta
round, then a closing full round" therefore ran the same full review twice each time, with no
narrower scope to gain from.

## Why it matters

The delta/full two-step only saves cost when the delta is strictly narrower than the full scope.
When attribution resolves to the whole footprint (wide cross-cutting fixes, rebases, or attribution
that cannot narrow), the closing full round is a pure duplicate.

## Proposed rule / fix

- When the computed delta scope equals the full scope (same deliverable set and file set), treat
  the delta round as the closing full round and skip the second pass.
- Surface the delta-vs-full size on the review dispatch so the collapse is visible in the log.
