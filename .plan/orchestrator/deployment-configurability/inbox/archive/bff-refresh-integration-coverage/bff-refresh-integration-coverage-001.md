envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-09T11:45:14Z

# Candidate lesson: verify the artifact's bytes, not the publishing job's status

## Category
bug

## Component
build-maven / upstream-dependency-consumption

## What happened

This plan sat blocked for four days on a fix in an upstream snapshot dependency
(TokenSheriff). To unblock local work, an earlier session hand-installed a fixed
jar into the local `~/.m2` repository. Every local green computed after that
point was computed against bytes that no remote repository held.

The result was two simultaneously-true, contradictory facts: the host build was
green and the container build failed. Nothing in either build's output said why,
because neither build reports where its dependencies came from.

Recovery required purging the local snapshot and inspecting the *published*
artifact directly (`javap` on the class in the downloaded jar) to confirm the
fix was actually in the bytes a fresh consumer would get.

## Generalisable rule

When work depends on an upstream fix landing:

- **Do not** treat a green publishing job, a release note, or a version bump as
  evidence the fix is in the artifact. Those are evidence a job ran.
- **Do** purge the local cache entry for the coordinate, re-resolve from the
  remote, and assert on the resolved artifact's own contents (a class member, a
  resource key, a manifest attribute) before trusting any build that consumes it.
- Treat any hand-installed artifact in a local repository as a **poisoned local
  cache**: it silently converts every subsequent local green into an unfalsifiable
  claim. If one is installed as a deliberate stopgap, it must be recorded and
  purged before any verification result is reported as meaningful.

## Recurrence signature

A green local build coexisting with a red container/CI build, on the same commit,
with no diff between them other than dependency resolution.
