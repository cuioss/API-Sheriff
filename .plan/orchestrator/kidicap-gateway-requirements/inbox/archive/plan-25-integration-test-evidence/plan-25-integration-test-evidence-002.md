envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:26:58Z

component=integration-tests
category=bug
created=2026-10-08

# Rancher Desktop/WSL2: a host port number goes dead after removing a container with two loopback publications or one loopback publication on two networks

## What happened

Plan plan-25-integration-test-evidence (PR #408). A native integration run failed with `java.net.SocketException: Connection reset` on published ports of test-started containers (quality-check finding b87705, then 6 failures in a later run). Two successive explanations were acted on and both were wrong or incomplete:

1. "A fixed loopback port keeps forwarding to the first container's address" - answered by pinning a subnet and moving the port. The next run still failed.
2. "Docker-assigned (ephemeral) ports never fail" - false: the TLS-relaxation proxy was already on `-p 127.0.0.1::8443` and was reset.

Only a controlled reproduction with plain nginx containers (no gateway) found the trigger.

## Measured trigger

On this host (Rancher Desktop, WSL2) a host port number stays dead after removal of a container that had

- two loopback publications, or
- one loopback publication while attached to two networks.

At most one loopback publication on one network leaves no residue. A dead port answers with a reset after exactly 5.0 s. Dead at the time of measurement: 10456, 10457, 10458, 10460, 10461. Retired, never to be reused on this host.

## What the plan put in place

- Test-started gateways publish the fixed application port on all interfaces and the management port on a docker-assigned loopback port, on one network.
- The TLS-relaxation identity-provider proxy publishes no host port; its serving check runs from inside the docker network.
- Harness rule `OneOffGatewayContainers.assertLeavesNoDeadHostPort` in every argument builder, pinned by `OneOffGatewayContainersTest`; `assertApplicationPortAnswers` names the dead-port condition.
- The host-port table in `doc/development/integration-test-topology.adoc` is checked against the sources by `OneOffGatewayContainersTest`.

## Rule

When a container port resets on this host, measure with throwaway containers before changing the harness: diagnose first, then rework. A new test-started container must not combine two loopback publications, or a loopback publication with two networks. Probe a new fixed port alive before assigning it.

## Evidence

- Decision log entries f1ca4e, e9b913, 40d4d6, 98ce61, 50b3dd, 48a9cd; quality-check finding b87705 (5-execute).
