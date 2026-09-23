envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:23:15Z

component=integration-tests
category=bug
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# jfr profile lane silently reuses a stale local api-sheriff:distroless image

## What happened

The `-Pjfr` integration-tests lane went red on its first run. The `jfr` profile builds `api-sheriff:jfr` on top of a local `api-sheriff:distroless` image but does not rebuild that base image; the locally cached one predated the current cookie layout (v2 vs v3), so the IT failed on a stale-binary mismatch unrelated to the change under test. Rebuilding via `-Pintegration-tests` first made the jfr lane green.

## Corrective action

Either make the `jfr` profile build (or verify the freshness of) its `api-sheriff:distroless` base image, or have the lane fail fast with an explicit "base image missing/stale — run -Pintegration-tests first" message. Until then, any plan verification step that runs `-Pjfr` must run `-Pintegration-tests` in the same session first, and a jfr-lane failure should be checked against base-image age before it is attributed to the change.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305), deliverable 9 verification
- Related q-gate finding 8c18c6 (the outline had stated this precondition only after q-gate prompted it)
