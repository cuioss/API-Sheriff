envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:52:18Z

component=api-sheriff
category=improvement
title=Release version sweep is doc-scoped, so a version literal in a build input escapes it

# Release version sweep is doc-scoped, so a version literal in a build input escapes it

## Observation

The root cause of this plan's first deliverable is a mechanism gap, not an
oversight. `Dockerfile.native.jfr` carried a hard-coded `0.1.0-SNAPSHOT` in its
`org.opencontainers.image.version` label. The release skill's version sweep is
**doc-scoped**: it covers prose and documentation, so a version literal living in a
*build input* — a Dockerfile, a compose file, a workflow — is outside its reach by
construction.

That is why Open Defect (54) survived a release sweep rather than being caught by
one, and why the published JFR image would have kept asserting `0.1.0-SNAPSHOT`
through every subsequent release.

## Rule

A "no stale version literals" sweep must cover the file classes that can *ship* a
version string, not only the ones that *describe* it. Build inputs that end up
baked into a published artifact — `Dockerfile*`, `docker-compose*.yml`, workflow
files, resource bundles — belong in the sweep's scope.

The general principle: scope a sweep by *where the fact can appear*, not by the
document type it is usually written in. A sweep whose scope is narrower than the
defect class it names will report clean forever.

## Suggested epic action

Either widen the release skill's version sweep to build inputs, or make each such
literal unreachable by construction — which is what this plan did for the two
Dockerfiles by replacing the literal with `ARG APP_VERSION` / `${APP_VERSION}`
supplied at build time. The second approach is strictly stronger: there is no
literal left for a sweep to miss.
