envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:13Z

component=plan-marshall:automatic-review
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=automatic-review step outcome / pre-merge barrier

# Automatic-review participation credited thread replies at HEAD as participation, so the pre-merge barrier could not see unreviewed commits

## What happened

The automatic-review participation check counted CodeRabbit's replies on existing review threads,
posted at the new HEAD, as bot participation for that HEAD. The pre-merge barrier therefore reported
the review as current while the new commits had in fact not been reviewed.

## Why it matters

A thread reply is a conversation event, not a review of the commit range. Crediting it as
participation makes the barrier fail open exactly in the case it exists for: commits pushed after
the last real review.

## Proposed rule / fix

- Participation for a HEAD must be derived from a review that covers that HEAD (for CodeRabbit: the
  summary comment's coveredCommitId marker; generally: a review object whose commit_id is HEAD),
  never from thread replies or issue comments.
- Add a regression case: thread replies at HEAD with an older coveredCommitId must yield "not
  reviewed at HEAD".

Related: the candidate-lesson about the CodeRabbit manual trigger and coveredCommitId from the same
plan.
