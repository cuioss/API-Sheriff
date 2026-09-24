envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:25Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): Share one function across modules collides with a documented copy-out distribution boundary

Original lesson `2026-09-23-07-003` (component `api-sheriff`).

## What happened

PLAN-28 deliverable 4 asked that three shell root-path trims be routed through "one shared
function/idiom". The three call sites live in three different Maven modules, and no shared shell
library spans them. The blocking fact is a product property, not a code-layout property:
`deployment/compose-sample` is documented operator-copyable reference material, and `start-sample.sh`
is invoked directly by an operator following `doc/user/compose-sample.adoc`. A `source` of a file
under `integration-tests/` would have broken the copy-out use case that module exists to serve — the
obvious DRY refactor would have silently damaged a documented product contract.

## Candidate rule

Deduplication requests are phrased as if they were internal refactors, but a duplicate that crosses a
distribution boundary (a copy-out sample, a published snippet, a vendored file) is load-bearing
duplication. Before consolidating N duplicates into one artifact, check each site for a distribution
boundary — is this file documented as copyable, vendored, or published independently? Sites behind
such a boundary keep an inline copy of the documented identical idiom rather than a `source`, and the
outline records that split explicitly rather than assuming one shared file.

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `34a7e9` (phase 2-refine, resolution
`taken_into_account`). Disposition: operator ruled the boundary at refine time — `integration-tests`
and `demo-client` share one artifact (`lib-docker-compose.sh`); `deployment/compose-sample` keeps its
own inline copy.
