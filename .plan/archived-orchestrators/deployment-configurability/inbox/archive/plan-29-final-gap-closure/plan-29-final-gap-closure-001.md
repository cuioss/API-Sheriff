envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:12:02Z

# Candidate lesson: outline deliverable omitted existing doc coverage (Q-Gate caught)

Source signal: Q-Gate finding 32932b (phase 3-outline, type triage, resolution fixed).

## What happened

Deliverable 2 of PLAN-29 planned a new contributor doc
(`doc/development/local-test-environment-caveats.adoc`) about the macOS loopback
port-collision flakiness. The outline neither named nor constrained the new doc against
the existing in-depth coverage in `doc/development/build-gate-discipline.adoc`
(section "The condition is macOS-local: a wildcard ephemeral bind collides with a
foreign loopback listener" and "What a contributor does about it").

The outline Q-Gate flagged the duplication risk: execute would likely restate the
wildcard-bind mechanism and the reading convention in a second place, where the two
could drift or contradict.

## Resolution in-run

Fixed at execute: the landed doc (commit 613c380) links to the existing section instead of
restating it, and has a dedicated section distinguishing the foreign-holder mechanism
from the wildcard-bind class PLAN-13 fixed. Residual: no back-link from
build-gate-discipline.adoc (outside declared affected-files scope).

## Candidate rule

When an outline deliverable adds a new documentation page on a topic, the outline should
search the existing doc corpus for the same topic and state explicitly: link-not-restate
for existing coverage, how the new content differs, and whether the existing page gets a
back-link (and if so, list it in Affected files).
