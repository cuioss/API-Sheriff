envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:54Z

component=integration-tests
category=anti-pattern
source=pr-comment finding e26116 (PR #382, CodeRabbit inline @ declared-limit-assertion-coverage.adoc:955, fixed by TASK-6)

# A document's guard claim promised more than the guard checked (compose services only)

## What happened

The coverage document said no gateway instance passes a JSSE system property and that ItProfileConfigBindingWiringTest guards this. The guard built its gateway set from `docker-compose.yml` only. It never checked the two one-off launch sites (OneOffGatewayContainers, NoCertificatePlainHttpOptInIT), so re-adding the argument there would not turn it red. The operator chose to extend the guard rather than narrow the claim. TASK-6 added a launch-site scan with a minimum count and both files named as members.

## Rule

When a document states that something is guarded, list the population the guard actually iterates over and compare it with the claim before writing. This was the first of three review rounds on the same guard that each found its claim covered more than it checked (see also 37183c and a2e942). One upfront pass listing every way the argument can reach the gateway would have saved two loop-backs.
