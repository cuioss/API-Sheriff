envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:16:27Z

# Candidate lesson: a test that pins three literal fields stays green when a fourth is added — and its own Javadoc claimed otherwise

**Source signal**: PR #341 inline review comment `19a92e` (coderabbitai, resolution `fixed`)
**Component**: `api-sheriff/src/test/java/.../testsupport/AwaitsTest.java`

## What happened

`AwaitsTest.pinsTheTierCeilings()` asserted three literal `*_CEILING_SECONDS` values. Adding a
fourth ceiling field to `Awaits` would leave the test green with the new field entirely unpinned.

The sharper half of the finding — and the reason it is worth carrying — is that **the test's own
Javadoc claimed it would report a tier added without a pinned ceiling**, which the three literal
assertions did not do. The test was not merely incomplete; it carried a written guarantee it did
not implement, so a reader checking "is this covered?" got a yes.

## Why this is candidate-lesson shaped

Two independent defects that travel together:

1. **A guard that enumerates members instead of deriving them does not guard the set.** It guards
   the members that existed when it was written. The failure mode is silent and appears at the
   moment someone *extends* the thing the guard exists to protect.
2. **A Javadoc claim about what a test detects is a claim, and it can be false.** Nothing checks it.

Both were flagged in the same review pass as the sibling `DocumentedSetsContractTest` finding —
the reviewer named them "the same sink class" — so within one plan the pin-vs-derive shape appeared
in two unrelated tests.

## Candidate rule

A contract test over a declared inventory derives the inventory (reflection over the declaring type,
a walk of the schema roots) and asserts the derived name→value map. Where the test's Javadoc states
what it detects, that statement is part of the contract and is re-read when the assertions change.

## Disposition in this plan

Fixed by TASK-017 (commit `4eea7bf`): the ceiling-field inventory is derived from `Awaits` and the
derived map asserted, the strict-ordering assertion kept, and the now-retired "extend it with every
tier" instruction corrected. The sibling `DocumentedSetsContractTest` was converted from literal
pointers to derived profile-enum pointers in the same task.
