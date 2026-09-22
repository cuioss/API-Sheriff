envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:51:08Z

component=api-sheriff
category=anti-pattern
title=Plan spec Expected Surface named the consumer, not the channel that reaches the image

# Plan spec Expected Surface named the consumer, not the channel that reaches the image

## Observation

PLAN-16's spec listed an Expected Surface of `Dockerfile.native` +
`.github/workflows/release.yml` for the revision-label deliverable. That surface is
**not implementable**: `release.yml` performs no `docker build` of its own and
explicitly forbids adding one. The single image build in the release lane is the
integration-test lifecycle's `docker compose build api-sheriff`, so a build arg
reaches the published image only via `integration-tests/docker-compose.yml`
`build.args`.

Implementing the literal Expected Surface would have declared `ARG APP_REVISION`
in the Dockerfile, referenced it in a label, and shipped an **empty**
`org.opencontainers.image.revision` on every published image — substituting a new
falsehood for the missing value the plan existed to remove. The one-file-wider
surface was not scope creep; it was the minimum surface that makes the claim true.

## Rule

When a spec's Expected Surface names a *workflow* as the place a build input is
supplied, verify that the workflow actually performs the build. If it delegates
the build (to Compose, to a Makefile, to a lifecycle plugin), the delegate's
argument-passing file is part of the surface, and omitting it produces a change
that compiles, passes text-level review, and ships an empty value.

Corollary for verification design: assert on the **built image**, never on the
Dockerfile text. A Dockerfile-text assertion is green in exactly the failure mode
above.

## Where it was caught

phase-2-refine Q-Gate finding `b4ca14` (Scope Realism), raised before any code was
written and absorbed by the 3-outline pass. The gate did its job here — this
candidate records the spec-authoring rule so the next spec does not need the gate
to catch it.
