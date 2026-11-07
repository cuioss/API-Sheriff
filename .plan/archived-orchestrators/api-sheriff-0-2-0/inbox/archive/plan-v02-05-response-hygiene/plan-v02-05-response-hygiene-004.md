envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:43Z

# main moved twice during finalize; second move conflicted semantically

**Surface:** API-Sheriff finalize practice (extends existing local memory `finalize-main-drift`); secondary: `plan-marshall:phase-6-finalize` code-review test-integrity check
**Signal:** orchestrator observation + Q-Gate record 0f0914 (6-finalize, code-review, taken_into_account)
**Plan:** plan-v02-05-response-hygiene, PR #526

## Observation

1. `main` moved twice during finalize. The second move (#528, uniform 404 for untrusted callers, ADR-0073) did not conflict textually but semantically: this PR's `ErrorContractIT` still expected 405/401 for those callers. Resolved by merging `origin/main` into the branch — one resolution pass instead of replaying 22 commits via rebase; the squash merge makes the merge commit irrelevant.
2. Q-Gate 0f0914: the code-review test-integrity check reported `weakened_test at ConfigLoaderTest.java` — an assertion present on `origin/main` was missing on the branch. It was base drift (PR #527 merged after the branch's merge base), not a weakened test; resolved by syncing with `origin/main`.

## Candidate rule

- Project: when `main` moves during finalize, re-run the affected ITs against the merged result, not only the textual merge — a sibling plan can change a cross-cutting contract (status codes for untrusted callers) without touching the same lines. For a long branch, prefer one `merge origin/main` resolution over a many-commit rebase replay. This extends the existing `finalize-main-drift` memory rather than creating a new one.
- Tooling: a weakened-test comparison against a stale merge base produces false positives; compare against current `origin/main` (or report base drift distinctly).
