envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:13:48Z

# Candidate lesson: a symmetric enum declared in Java is restated at N schema sites, and the outline named only one

**Source signal**: Q-Gate finding `40875d` (phase 2-refine, type `triage`, severity warning, resolution `taken_into_account`)
**Component**: `api-sheriff` — `config/model/SecurityProfile.java`, `resources/schema/gateway.schema.json`, `resources/schema/endpoint.schema.json`

## What happened

PLAN-28 deliverable 1 added a `PARANOID` arm to `SecurityProfile`. The request described the work
as "update the schema description" and named `gateway.schema.json` only. The value space is in fact
gated by **three** symmetric JSON-Schema enum sites: `gateway.schema.json` `security_defaults.profile`,
the `securityFilter` `$def` in the same file, and `endpoint.schema.json`. Had the refine Q-Gate not
caught it, `profile: paranoid` would have been refused at boot and the new arm unreachable — a
feature that compiles, ships, and cannot be selected.

## Why this is candidate-lesson shaped

The recurrence is not "someone forgot a file". It is that **a closed set defined in one language is
restated in a different language at several sites, and the request-level description of the change
names the site the author happened to be looking at**. The defect is invisible to the compiler
(JSON Schema is data), invisible to unit tests unless a contract test exists, and only surfaces at
boot.

The same plan later re-encountered the identical shape twice more, in prose rather than schema
(see the sibling candidates for finding `2d8a38` and PR comment `5b7752`), which is what makes it
worth carrying past this plan.

## Candidate rule

When a change adds or removes a member of a closed set that is declared in code and restated
elsewhere, the outline's Expected Surface must be derived by **searching for the existing members**
(each of the surviving enum values) rather than by naming the site under edit. The contract test
that binds the restatements is the durable fix; the search is the per-plan guard.

## Disposition in this plan

Recorded and acted on at refine time: the Clarified Request was amended to name
`endpoint.schema.json` and to pin all three enum sites as required for deliverable 1.
