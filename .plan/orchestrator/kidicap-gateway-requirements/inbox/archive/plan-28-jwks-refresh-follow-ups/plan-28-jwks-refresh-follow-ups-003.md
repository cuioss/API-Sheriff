envelope_version=1
sender_type=plan
sender_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-13T14:01:32Z

# Candidate lesson: a flaky integration test traced to a docker-assigned loopback port

**Source:** plan-28-jwks-refresh-follow-ups, TASK-11 (establish whether BffReservedPathPrecedenceIT is flaky within two full integration runs). Fixed in bf0dbdaa and confirmed by full integration run 2.

## What happened

BffReservedPathPrecedenceIT failed now and then. The cause was not the test's own logic. Test-started gateways published their application port on a port docker assigned on loopback, and that assignment is not stable across runs. The fix publishes the application port on every interface at a fixed port and the management port on loopback (OneOffGatewayContainers). ADR-0065 records the decision, and doc/development/integration-test-topology.adoc now describes the topology.

## Proposed rule / fact

When an integration test that starts its own container is intermittently red, check how the container's ports are published (docker-assigned, loopback only, fixed) before looking at the assertion. A docker-assigned host port is a source of nondeterminism for any test that reaches the container through it.

## Shape note (for orchestrator classification)

This is mostly a durable project fact, already codified in ADR-0065. It may belong in the architecture hints for the integration-tests module rather than in a defect lesson. The plan does not classify it.
