envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:26:54Z

component=plan-marshall:build-maven
category=anti-pattern
created=2026-10-08

# A build result of status timeout does not mean the build process stopped

## What happened

Plan plan-25-integration-test-evidence (PR #408), 2026-10-07. The orchestrator ran the native integration build (`verify -Pintegration-tests -pl integration-tests -am`) on the uncommitted tree. The first attempt returned `status: timeout` at 1800 s. The orchestrator read that as "the build has ended" and tore the compose stack down. The build was still running; the stack vanished under it and the results had to be discarded. The rerun with `--timeout 3600` succeeded (281 tests, 2292 s).

## Rule

`status: timeout` reports that the *wait* ended, not that the build ended. Before any teardown, cleanup or restart after a timeout status, establish whether the build process (or build-server job) is still alive, and either wait for it or stop it explicitly.

Give the native integration build a wait budget that covers its real duration (about 38 to 50 minutes on this host; `--timeout 3600` was used from then on).

## Evidence

- Decision log entry 696e9f (2026-10-07T14:31:06Z), plan plan-25-integration-test-evidence.
