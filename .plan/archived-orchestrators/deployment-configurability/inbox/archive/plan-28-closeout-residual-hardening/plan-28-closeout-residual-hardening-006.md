envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:15:23Z

# Candidate lesson: a stale local Docker image hijacked the integration-tests lane and produced a 13-violation "regression" this branch did not cause

**Source signal**: Q-Gate finding `662b27` (phase 5-execute, type `build-error`, severity error, resolution `fixed`)
**Component**: `integration-tests` — `scripts/start-integration-container.sh`, `scripts/stop-integration-container.sh`, `docker-compose.jfr.yml`

## What happened

`verify -Pintegration-tests` failed at container start: the `api-sheriff-1` container logged
`ApiSheriff-201 Refusing to start -- 13 configuration violation(s)`, spanning eight fixture YAML
files **none of which this plan's 29-file diff touched**.

Root cause: the `integration-tests` lane builds and tags `api-sheriff:distroless`, but
`start-integration-container.sh` prefers **any existing** `api-sheriff:jfr` image with no freshness
check, and `docker-compose.jfr.yml` repoints only the *primary* `api-sheriff` service. A stale
`api-sheriff:jfr` built a week earlier (predating PRs #320 and #337) was therefore started for the
primary gateway. Its baked JSON schema predated optional `base_url`, endpoint scopes, route
redirect, anchor `security_headers` and auth `token_relay` — producing exactly those 13 violations.

The decisive disproof of branch attribution: the **other 11 gateway instances** started the freshly
built `api-sheriff:distroless` against the *same* fixture YAMLs and all reported healthy, and this
branch touches the two schema files only additively.

## Why this is candidate-lesson shaped

A red gate that is not the branch's fault costs more than a red gate that is, because the default
reading is "my change broke it" and the evidence that says otherwise is in container logs nobody
reads by default. The general hazard is an **implicit local-artifact preference with no freshness
check**: any script that says "use image X if present" will silently run last month's build on any
host that ever produced one, and will do it *only on that host* — so CI stays green and one
developer's machine lies.

## Candidate rule

A lane that builds an artifact must run **that** artifact. Image/binary selection is explicit per
lane, never "reuse whatever is tagged". Where a preference exists, it carries a freshness assertion
against the just-built artifact. And when a gate fails on files the diff never touched, establish
provenance against the base branch *before* attributing to the branch.

## Disposition in this plan

Remediated in-run: the compose stack was torn down and the stale `api-sheriff:jfr` image removed,
and TASK-013 made integration-tests image selection explicit per lane
(`integration-tests/pom.xml`, `start-integration-container.sh`, `stop-integration-container.sh`,
commit `9a114f4`).
