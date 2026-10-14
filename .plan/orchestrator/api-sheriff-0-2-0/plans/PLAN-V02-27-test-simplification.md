# PLAN-V02-27: Simplify the Tests After the Edge/Config Refactor

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 by operator decision: the refactor (`PLAN-V02-24`, `-25`, `-26`) is proven by the
> existing tests unchanged; once it has merged, a separate plan simplifies those tests.

## Objective

The refactor deliberately leaves the tests as they were — moved with the code, not rewritten — so that
"the existing suite still passes" proves the refactor kept behaviour. Afterwards the tests carry the old
structure: positional and hand-built fixtures, tests of one behaviour spread over classes that no longer
match the production code, near-duplicates that each grew one extra assertion. Simplify them against the
new structure without weakening what they prove.

## Deliverables

1. **Inventory first**, scoped to the refactored areas: `config/` resolution and model, `edge/` and its
   new sub-packages, `routing/`, `config/validation/`. List duplicate and near-duplicate tests, tests whose
   class no longer matches the production class they exercise, fixture code repeated across classes, and
   tests that construct far more configuration than the behaviour under test needs. Settle the scope with
   the operator at outline.
2. **Align test classes with production classes** (one test class per extracted class or rule class)
   and move shared fixtures into a small set of builders, using the records' `@Builder`.
3. **Remove duplicates only after carrying their extra assertions into the survivor**, so the surviving
   test is at least as strict. Report removed pairs.
4. **Prove nothing was lost:** JaCoCo per class before and after is identical or higher for every touched
   area; integration tests are touched only where the inventory shows a genuine duplicate. Report test
   counts and the unit-test run time before and after.

Split guard: 4 deliverables. Keep each PR under 100 files and 150,000 diff characters; split by area
(`config`, `edge`, `validation`) if needed.

## Claim Labels

- HYPOTHESIS: the refactor leaves duplicated and mis-aligned tests behind — confirm by deliverable 1 against the merged refactor (verify-at-outline)

## Expected Surface

- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/`, `edge/`, `routing/`, `config/validation/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` only for genuine duplicates (verify-at-outline)

## Dependencies and Sequencing

- Depends on: `PLAN-V02-24`, `PLAN-V02-25` and `PLAN-V02-26` having merged.
- After the re-grounded `PLAN-V02-23` (the cookie bug goes first, and it adds tests in the same areas).
- Holds no feature plan; but a feature plan running at the same time in these areas collides on tests,
  so pair it only with plans off `config/` and `edge/`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-27-test-simplification.md" plan_id=plan-v02-27-test-simplification
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
