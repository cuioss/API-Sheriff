envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:26Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): The counts on a Javadoc line were updated, the enumeration on the same line was not

Original lesson `2026-09-23-07-007` (component `api-sheriff`).

## What happened

A plan's swap updated two counts on one Javadoc line ("Six -> Nine", "one lists the inbound-filter
mode set -> four"), but the surviving enumeration on the SAME line still omitted
`doc/user/README.adoc`, which restates the mode set twice (a per-mode table, and a bare "the mode
set is exactly..." sentence). The plan hand-edited both README sites to add the new mode — precisely
the drift a contract test exists to close — yet neither was bound by a test. Net effect: eleven
shipped surfaces restated a Java-defined set while nine were guarded, and the sentence read as
complete coverage over surfaces it did not reach. This is the third instance of one shape in a
single plan: a closed set restated across code, schema and prose, with the guard bound to a subset.

## Candidate rule

When a sentence carries both a count and an enumeration, they are two claims and both must be
re-derived — updating the arithmetic feels like discharging both, but does not. The touched line was
edited and left wrong, which is worse than untouched-and-stale, because the edit is evidence someone
looked at it. Prefer the stronger remedy: bind the unguarded surfaces with a test rather than
narrowing the sentence — and where a count claim has no guard at all, delete the count instead of
correcting it, since a deleted count cannot go stale.

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `2d8a38` (phase 6-finalize
`pre-submission-self-review`, defect class `touched_claim_unverified`, resolution `fixed`). Fixed by
adding a derived-inventory anchor plus two guards (set-equality over the bare enumeration, and a row
guard over the mode table); the adjacent unguarded heading count was deleted rather than corrected.
