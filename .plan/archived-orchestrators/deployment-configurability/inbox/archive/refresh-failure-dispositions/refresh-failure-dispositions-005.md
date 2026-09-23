envelope_version=1
sender_type=plan
sender_id=refresh-failure-dispositions
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-17T03:49:51Z

component=api-sheriff
category=bug

# verify -Ppre-commit rewrites two files the branch never touched on every run (SniFrontListener, LoopbackEphemeralBindArchTest)

## What happened

Every whole-tree quality gate run in this plan (`verify -Ppre-commit`, at least six runs between 2026-09-16 15:56 and 2026-09-17 01:59 UTC) finished green but left the same two non-branch files rewritten:

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java`
- `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/LoopbackEphemeralBindArchTest.java`

Each time the churn had to be identified with `git status --porcelain`, attributed, and reverted by hand. It has a real cost in finalize. `pre-push-quality-gate` is `mutates_source: true`, so the dispatcher's commit instrumentation would have committed that unrelated churn onto the feature branch. One pre-push run therefore reused an earlier gate job at the same HEAD instead of re-running, purely to avoid the churn.

## Rule

- The gate is not idempotent on `main` for these two files. Land one `main`-side commit that applies the gate's rewrite to both files (or fix the recipe/formatter disagreement behind it) so a clean `main` stays clean after the gate.
- Until then, after every gate run in a plan, revert these two paths before any `mutates_source` step records `done`. Never let finalize commit instrumentation pick them up.

## Coverage note

The auto-memory "Pre-commit Formatter Churn" says the destructive `-Ppre-commit` rewrites were resolved by PR #242. That holds for the `<release>`/underscore/178-file churn it describes, but it is stale for these two files, which churned on every run at base 3c68ee4.

## Evidence

- Plan refresh-failure-dispositions, decision log 2026-09-16T15:56:45Z, 16:28:12Z, 16:31:38Z, 16:48:18Z, 21:04:41Z; 2026-09-17T00:58:19Z, 01:59:27Z.
