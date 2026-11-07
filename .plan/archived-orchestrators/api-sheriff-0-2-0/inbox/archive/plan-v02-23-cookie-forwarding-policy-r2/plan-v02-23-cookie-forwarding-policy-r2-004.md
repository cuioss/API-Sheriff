envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:09Z

component=plan-marshall:automatic-review
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=automatic-review step outcome

# CodeRabbit "@coderabbitai review" is refused inside its hourly window, and thread-reply reviews at HEAD were mistaken for a fresh review

## What happened

After fix pushes, a manual "@coderabbitai review" trigger was refused by CodeRabbit with "only
applicable when automatic reviews are paused" while inside its hourly review window. Meanwhile
CodeRabbit posted replies on existing review threads that were stamped at the new HEAD; those were
read as a fresh full review of HEAD, although the new commits had not actually been reviewed.

## Why it matters

Neither the manual trigger nor HEAD-stamped activity is a reliable signal that CodeRabbit reviewed
the latest commits. The reliable signal is the coveredCommitId marker in CodeRabbit's summary
(walkthrough) comment: the review covers HEAD only when that marker equals the HEAD SHA.

## Proposed rule / fix

- Do not issue "@coderabbitai review" while automatic reviews are active; it is refused by design.
- Gate "review is current" on the summary comment's coveredCommitId == HEAD, not on any
  comment/review event timestamped after the push.
