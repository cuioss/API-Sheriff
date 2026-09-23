envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:16:08Z

# Candidate lesson: the counts on the line were updated, the enumeration on the same line was not

**Source signal**: Q-Gate finding `2d8a38` (phase 6-finalize, `pre-submission-self-review`, defect class `touched_claim_unverified`, severity warning, resolution `fixed`)
**Component**: `api-sheriff/src/test/java/.../config/DocumentedSetsContractTest.java`

## What happened

The plan's swap updated two counts on one Javadoc line (`Six -> Nine`, `one lists the
inbound-filter mode set -> four`), but the **surviving enumeration on the same line** —
"`doc/configuration.adoc` plus the three symmetric profile enum sites the two bundled JSON Schemas
declare" — still omitted `doc/user/README.adoc`, which restates the `SecurityProfile` mode set
twice (a per-mode table, and a bare "the mode set is exactly …" sentence).

This plan hand-edited **both** of those README sites to add `paranoid` — precisely the drift class
the contract test exists to close — yet neither was bound by a test. The test's only
`doc/user/README.adoc` anchor covered the extension list. Net effect: eleven shipped surfaces
restated a Java-defined set while nine were guarded, and the sentence read as complete coverage
over surfaces it did not reach.

## Why this is candidate-lesson shaped

The touched line was *edited and left wrong*, which is worse than untouched-and-stale: the edit is
evidence someone looked at it. The mechanism is that a count and an enumeration on the same line
are two claims, and updating the arithmetic feels like discharging both.

It is the third instance of one shape in this single plan — a closed set restated across code,
schema and prose, with the guard bound to a subset (see candidates for findings `40875d` and PR
comment `5b7752`).

## Candidate rule

When a sentence carries **both** a count and an enumeration, they are two claims and both must be
re-derived. Prefer the stronger remedy the fix actually took: **bind the unguarded surfaces with a
test** rather than narrowing the sentence — and where a count claim has no guard at all, **delete
the count** instead of correcting it, since a deleted count cannot go stale.

## Disposition in this plan

Fixed (TASK-017, commit `4eea7bf`): added `README_PROFILE_MODE_SET_ANCHOR` plus two guards
(`userReadmeEnumeratesTheSecurityProfileModes` — set equality and raw-token count over the bare
enumeration; `userReadmeDocumentsEverySecurityProfileMode` — over the mode table rows). The Javadoc
line now reads "Ten shipped surfaces … five list the inbound-filter mode set …" with 3+5+2=10 and
every named surface tested. The adjacent unguarded heading count `=== The four modes` was deleted
rather than corrected. `DocumentedSetsContractTest` green at 27 tests, up from 25.
