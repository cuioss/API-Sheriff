envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:03Z

# Candidate lesson: bind-mounted test descriptors must be made world-readable explicitly

Source: Q-Gate finding 8dfd1e (5-execute, test-failure, severity error, resolved fixed by TASK-6); also orchestrator observation 3.

## What happened

`JwksEgressMismatchIT.explicitAllowlistNamingAnotherHostKeepsTheKeySetRefused` failed (1 of 222 ITs) with
`No public port 9000/tcp published` because the control gateway container exited at boot.
`writeControlDescriptor()` wrote `target/jwks-egress-mismatch-control/gateway.yaml` via `Files.writeString`;
under the local build daemon's umask 077 the file became mode 600 owned by uid 1000. The distroless gateway
runs as uid 1001 and failed with `ApiSheriff-200: ... cannot read configuration file: /app/sheriff-config/gateway.yaml`.
Committed (git-checked-out, 644) descriptors were readable, so only the generated one broke — the failure
depends on the host umask and can pass on CI and fail locally (or vice versa).

## Corrective rule

Any file a test generates and bind-mounts into a container running as a different uid must have its POSIX
permissions set explicitly after every write (e.g. `Files.setPosixFilePermissions(path, rw-r--r--)`), never
inherited from the process umask. The container-start helper (`OneOffGatewayContainers.startGateway`) now
fails fast when a mounted descriptor is not world-readable, turning a boot-time container exit into a
named precondition failure.

## Components

integration-tests (`JwksEgressMismatchIT`, `OneOffGatewayContainers`); possibly the `run-integration-tests` project skill's diagnosis checklist.
