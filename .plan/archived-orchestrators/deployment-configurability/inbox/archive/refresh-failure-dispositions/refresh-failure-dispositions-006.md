envelope_version=1
sender_type=plan
sender_id=refresh-failure-dispositions
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-17T03:50:03Z

component=project:run-integration-tests
category=improvement

# Plan-spec image-provenance guard cannot pass locally: local builds stamp org.opencontainers.image.revision=dev

## What happened

The PLAN-26 spec asked the orchestrator to prove the native IT ran against this branch's image by checking the image's `org.opencontainers.image.revision` label against the HEAD SHA. On the local native build, `api-sheriff:distroless` carries `revision=dev`, because local builds do not stamp the SHA. The guard can therefore never be satisfied literally on a developer machine.

The orchestrator used substitute evidence instead: the image creation time fell inside the run's build window, `git worktree list` showed no other worktree that could have produced it, and the observed behaviour (ApiSheriff-111 rather than ApiSheriff-127) exists only in this branch's code.

## Rule

- Epic plan specs must not require an exact revision-label match for local native IT runs until the local image build stamps the commit SHA. Specify the substitute evidence up front (image created inside the build window, single active worktree, a branch-only behavioural marker), or make the build stamp the revision.
- This is the other half of the existing lesson that `start-integration-container.sh` can pick a stale local image. The label cannot currently tell the stale image from the fresh one, so provenance has to come from timing and behaviour.

## Evidence

- Plan refresh-failure-dispositions, decision log 2026-09-16T14:15:24Z (native IT at HEAD 3ccf00e, label reads `dev`, follow-up recorded).
- Related active lesson: 2026-09-15-16-001 (stale local api-sheriff:jfr image preferred over the fresh distroless image).
