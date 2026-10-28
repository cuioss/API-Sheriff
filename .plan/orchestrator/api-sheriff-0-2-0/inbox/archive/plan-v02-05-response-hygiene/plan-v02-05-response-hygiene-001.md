envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:30Z

# CodeRabbit thread acknowledgements consume the hourly review allowance

**Surface:** `plan-marshall:automatic-review` / `plan-marshall:workflow-integration-github` (`post_responses` vs the re-review trigger)
**Signal:** automated-review (signal_automated_review_count=1)
**Plan:** plan-v02-05-response-hygiene, PR #526

## Observation

CodeRabbit (1 included review per hour on this repo) answers each resolved thread reply with an in-thread acknowledgement. Those acknowledgements were charged against the hourly allowance: twice an `@coderabbitai review` posted ~90 minutes after a batch of replies came back "rate limited".

Posting the held replies AFTER requesting the head review (not before) got a review on the first try.

## Candidate rule

When a review round has both held thread replies and a pending re-review request, issue the re-review trigger first and post the replies afterwards. Batch-posting replies before the trigger can exhaust the bot's review quota with acknowledgement traffic.
