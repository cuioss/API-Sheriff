envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:14:06Z

# Candidate lesson: "share one function" across modules collides with a copy-out boundary

**Source signal**: Q-Gate finding `34a7e9` (phase 2-refine, type `arch-constraint`, severity warning, resolution `taken_into_account`)
**Component**: `integration-tests` / `demo-client` / `deployment` — shell bring-up scripts

## What happened

PLAN-28 deliverable 4 asked that three shell root-path trims be routed through "one shared
function/idiom". The three call sites live in three different Maven modules, and no shared shell
library spans them. The only precedent, `integration-tests/scripts/lib-docker-compose.sh`, is
scoped to `integration-tests`.

The blocking fact is a **product property, not a code-layout property**: `deployment/compose-sample`
is documented operator-copyable reference material, and `start-sample.sh` is invoked directly by an
operator following `doc/user/compose-sample.adoc`. A `source` of a file under `integration-tests/`
would have broken the copy-out use case that module exists to serve — i.e. the obvious DRY refactor
would have silently damaged a documented product contract.

## Why this is candidate-lesson shaped

Deduplication requests are phrased as if they were internal refactors, but a duplicate that crosses
a **distribution boundary** (a copy-out sample, a published snippet, a vendored file) is load-bearing
duplication. The signal for that is not in the code: it is in the docs that tell an operator to copy
the file.

## Candidate rule

Before consolidating N duplicates into one artifact, check each site for a distribution boundary —
is this file *documented as copyable*, vendored, or published independently? Sites behind such a
boundary keep an inline copy of the *documented identical idiom* rather than a `source`, and the
outline records that split explicitly rather than assuming one shared file.

## Disposition in this plan

Operator ruled the boundary at refine time: `integration-tests` and `demo-client` share one artifact
(`lib-docker-compose.sh`, established precedent — `start-dev-environment.sh` already sources it);
`deployment/compose-sample` keeps its own inline copy of the identical documented idiom.
