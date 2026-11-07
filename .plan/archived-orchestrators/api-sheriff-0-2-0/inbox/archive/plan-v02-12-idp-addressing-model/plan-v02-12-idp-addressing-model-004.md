envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-20T20:30:25Z

# Candidate lesson: the published api-sheriff container image is amd64-only

Plan plan-v02-12-idp-addressing-model (PR #492, merge 03cb4755), lessons-capture step. Component: release / container image (deployment compose sample).

## Observation

Running the production-shaped compose sample on an arm64 host (Apple Silicon) against the published image failed to use it natively: the image is published for linux/amd64 only. The run had to override `API_SHERIFF_IMAGE` with a locally built image (which in turn required the in-process build mode — see the companion candidate on daemon env inheritance).

## Why it matters

Any arm64 developer or deployer following the compose sample hits this. Either the release should publish a multi-arch image, or the compose sample / deployment docs should state the amd64-only constraint and the local-image override.

## Destination suggestion

Project fact for the epic: decide between a multi-arch release follow-up and a documentation note in the deployment sample.
