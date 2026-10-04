envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:56Z

component=integration-tests
category=anti-pattern
source=pr-comment finding 3973cf (PR #382, CodeRabbit review_body nitpick @ ItProfileConfigBindingWiringTest.java:381, fixed by TASK-6)

# Same gap reported a second time: one-off launch sites had no guard against a re-added JSSE argument

## What happened

The review summary separately noted that `noGatewayInstancePassesAJsseSystemProperty` checks only compose services, while the PR said the one-off sites had been cleaned up as well. The same fix as e26116 (TASK-6) resolved it. The same review called the `bffDescriptors()` strictness deliberate, and it was kept.

## Rule

A PR description that says "all sites cleaned" needs a guard covering all of those sites, or else a description limited to the sites that are guarded. Treat this record as a recurrence of e26116 when deduplicating.
