envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:25Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A symmetric enum declared in Java is restated at N schema sites, and the outline named only one

Original lesson `2026-09-23-07-002` (component `api-sheriff`).

## What happened

PLAN-28 deliverable 1 added a `PARANOID` arm to `SecurityProfile`. The request described the work as
"update the schema description" and named `gateway.schema.json` only. The value space is in fact
gated by three symmetric JSON-Schema enum sites: `gateway.schema.json` `security_defaults.profile`,
the `securityFilter` `$def` in the same file, and `endpoint.schema.json`. Had the refine Q-Gate not
caught it, `profile: paranoid` would have been refused at boot and the new arm unreachable — a
feature that compiles, ships, and cannot be selected. The same plan later re-encountered the
identical shape twice more, in prose rather than schema (see the sibling lessons for `2d8a38` and PR
comment `5b7752`).

## Candidate rule

When a change adds or removes a member of a closed set that is declared in code and restated
elsewhere, the outline's Expected Surface must be derived by searching for the existing members
(each of the surviving enum values) rather than by naming the site under edit. A bound contract test
is the durable fix; the search is the per-plan guard.

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `40875d` (phase 2-refine, resolution
`taken_into_account`). Disposition: the Clarified Request was amended to name `endpoint.schema.json`
and pin all three enum sites as required for deliverable 1.
