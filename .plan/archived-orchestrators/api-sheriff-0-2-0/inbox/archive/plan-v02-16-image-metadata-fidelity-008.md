envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:52:08Z

component=integration-tests
category=bug
title=The -Pjfr lane was unrunnable, and running it exposed a second pre-existing defect (#201)

# The -Pjfr lane was unrunnable, and running it exposed a second pre-existing defect (#201)

## Defect 1 — fixed in this PR: root-owned bind-mount directory

`integration-tests/docker-compose.jfr.yml` bind-mounts `./target/jfr-recordings` at
`/tmp/jfr-output`. Whichever compose command touched the service first created that
host directory, and it was created **root-owned**, so the uid-1001 container could
not write the recording and the gateway died at startup. The JFR integration-test
lane therefore could not start at all.

Fixed by `integration-tests/scripts/prepare-jfr-output-dir.sh`, which creates the
directory with the right ownership before compose does.

The general shape is worth keeping: a bind-mount whose host path does not exist is
created **by the daemon, as root**, regardless of the container's declared user. Any
compose bind-mount into a non-root container needs the host directory prepared
explicitly, or the first run silently poisons it for every subsequent run.

## Defect 2 — filed as issue #201, NOT fixed: MtlsHandshakeIT fails under -Pjfr only

With the lane finally able to start, `MtlsHandshakeIT` fails **2 of 3** under `-Pjfr`
while passing **3 of 3** under `-Pintegration-tests` on the identical tree. The
failure mode is fail-open on handshake rejection — a connection that should be
refused is accepted. That is a security-relevant behaviour difference between two
build profiles of the same gateway.

Deliberately not fixed here: out of scope for an image-metadata plan, and diagnosing
a profile-dependent mTLS fail-open is not a drive-by change.

## Residual risk the epic should weigh

`-Pjfr` is **not in CI's gating set**. So this defect was invisible until someone
ran the lane by hand, and it will stay invisible after #201 is filed unless either
the lane is added to a gating run or the issue is scheduled. A profile that is
never run in CI provides no evidence about the artifact it builds, and the JFR
image is a published artifact.
