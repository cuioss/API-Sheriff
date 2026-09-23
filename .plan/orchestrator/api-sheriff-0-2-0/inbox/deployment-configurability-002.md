envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:23Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): start-integration-container.sh prefers a stale local api-sheriff:jfr image over the freshly built distroless image

Original lesson `2026-09-15-16-001` (component `project:run-integration-tests`, category `bug`,
created 2026-09-15, three recurrences folded in through 2026-09-23). `deployment-configurability` is
closing; handing every local lesson to this epic since it is the one that continues touching this
repository's code. Decide Promote (recreate in the local corpus) / Discard for each.

## Observation

`integration-tests/scripts/start-integration-container.sh` (line 34) starts the compose stack on
`api-sheriff:jfr` whenever an image with that tag exists locally. The `-Pintegration-tests` profile,
however, only rebuilds `api-sheriff:distroless`. A leftover `api-sheriff:jfr` from another branch or
session therefore silently wins over the image the build just produced.

## Why it matters

Any local `-Pintegration-tests` result on a machine holding an `api-sheriff:jfr` image is invalid for
production-code changes: a green run proves nothing about the branch, and a reversion proof of a
production mutation cannot observe red.

## Status: FIXED (third recurrence, 2026-09-23, PLAN-28 / PR #341)

Root-caused and fixed by explicit per-lane image selection: TASK-013 (commit `9a114f4`) made image
selection explicit per lane in `integration-tests/pom.xml`, `start-integration-container.sh`, and
`stop-integration-container.sh` — a lane that builds an artifact now runs that artifact, no "prefer
whatever tag exists" fallback.

## Remaining open half: revision-label discrimination

A second recurrence (2026-09-17, PLAN-26) found that the workaround this lesson originally
recommended — compare the running image's `org.opencontainers.image.revision` label against the
worktree HEAD — cannot pass on a developer machine: local native builds stamp `revision=dev`, so the
label is identical for the fresh image and the stale one. Substitute evidence was used instead (image
creation time inside the build window, `git worktree list` showing no other producer, a
branch-only behavioural marker). **Not fixed**: until the local build stamps the real SHA, a reader
cannot verify by label alone which image a running container came from.

## Candidate rule

Epic/plan specs must not require an exact revision-label match for local native IT runs until the
local image build stamps the commit SHA. Specify substitute evidence up front (image created inside
the build window, single active worktree, a branch-only behavioural marker), or make the build stamp
the revision.
