envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:49Z

component=integration-tests
category=anti-pattern
source=Q-Gate finding cc41f5 (6-finalize self-review, fixed) - operator-surfaced event (c)

# Derive a guard's stated limit from its matcher, not from intuition (declared-limit doc copy)

## What happened

`doc/development/declared-limit-assertion-coverage.adoc` (line 962) repeated the same overstatement as the guard's Javadoc (finding 5dada9): that any run-time concatenation gets past the launch-site scan. The scan matches the literal `-Djavax.net.ssl.` prefix anywhere in a launch-site file, so only a split or computed prefix gets past it. Fixed together with the Javadoc as a cohort of two.

## Rule

A declared limit is usually stated twice, in the code and in the coverage document. Fix and re-check both copies against the matcher together. The self-review's cohort grouping (1 of 2 / 2 of 2) is what kept the second copy from going stale.
