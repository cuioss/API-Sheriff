envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:22:11Z

component=plan-marshall:phase-3-outline
category=anti-pattern
source_finding=qgate 3-outline 8c18c6 (taken_into_account)

# Deliverable verification command omitted the profile lane its own criteria required

## What happened

Deliverable 9 edited `api-sheriff/src/main/docker/Dockerfile.native.jfr`. Its criteria required the `jfr` profile lane to build `api-sheriff:jfr` and `ImageMetadataJfrIT` to pass, but its Verification Command was only `verify -Ppre-commit` — a gate that builds neither that image nor runs that IT. The lane was left as prose ("profile invocation taken from integration-tests/pom.xml"), so phase-4 could derive a verification step that never exercised the deliverable's only changed build input.

## Corrective action

When a deliverable's criteria name a build lane (profile, image, IT), its Verification Command must be the concrete executor invocation for that lane (here `verify -Pjfr -pl integration-tests -am`), with the profile id read from the owning pom and any image precondition stated. Check each criterion against the command: a criterion no command exercises is unverified.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Finding hash: 8c18c6, phase 3-outline
- Related operational follow-on: the jfr lane itself then went red on a stale local distroless image (separate candidate).
