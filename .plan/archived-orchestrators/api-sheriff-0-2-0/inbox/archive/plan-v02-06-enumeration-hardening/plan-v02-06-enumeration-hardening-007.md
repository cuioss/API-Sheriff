envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:23:25Z

component=api-sheriff
category=bug

# GatewayEdgeRouteTest$RelayStartFailure.relayThatFailsToStartReleasesTheUpstreamResponse is nondeterministic

During PR #528, `GatewayEdgeRouteTest$RelayStartFailure.relayThatFailsToStartReleasesTheUpstreamResponse` failed once in the isolated fork of the CI sonar-build job, then passed on rerun. The plan did not change the test. The failure looks like a race with the upstream client's reset handling.

## Corrective action

Make the test deterministic. Replace timing-dependent observation of the upstream release with an explicit synchronization point or an awaited condition, then confirm stability under a constrained CPU count (for example `-XX:ActiveProcessorCount=1`). Do not answer a red run with a retry.

## Related existing lessons

- 2026-10-15-12-002: a timing race in a refactored concurrency test only showed on a 2-core CI runner
- 2026-10-26-11-001: a latch or barrier release does not start racing threads together
