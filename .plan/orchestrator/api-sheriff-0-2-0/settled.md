# Settled narrative: API Sheriff 0.2.0

Narrative whose subject is closed, moved VERBATIM out of `epic.md` by the `cleanup` ledger-compaction stage. Each section's origin in `epic.md` carries a pointer naming its heading here. Nothing here is regenerated; this is a live-epic sibling of `history.md`, not the close-time freeze.

## Post-Merge Verification — `deploy-snapshot` (four V02 landings, 2026-08-09)

_Relocated 2026-09-24 by `cleanup` (operator-confirmed)._

**Performed 2026-08-09 on operator instruction. All four merge commits are GREEN.**

| Merge commit | Plan | Maven Build run | `build / deploy-snapshot` |
|---|---|---|---|
| `89a3cfe` | PLAN-V02-03 (#197) | 31289270907 | **success** |
| `e343404` | PLAN-V02-02 (#198) | 31294044453 | **success** |
| `aeb80c5` | PLAN-V02-16 (#199) | 31327146763 | **success** |
| `95dd566` | PLAN-V02-17 (#200) | 31330642464 | **success** |

Every other job in each run is `success` too, including `build (25)`, `build (26)`, `sonar-build`
and `conclusion`.

**Where the job actually lives, because this is what made it look unreachable.** `deploy-snapshot`
is **not defined in this repository**. `.github/workflows/maven.yml` declares only `build`,
`supply-chain-scan` and `rewrite-report`; its `build` job delegates to the organisation's reusable
workflow (`cuioss/cuioss-organization/.github/workflows/reusable-maven-build.yml@v0.18.0`), and
`deploy-snapshot` is a job *inside* that. It surfaces as **`build / deploy-snapshot`** within the
**Maven Build** run — so grepping this repo for the job name returns nothing, which is why the axis
read as structurally unreachable rather than merely awkward.

**Method, and the false negative it survived.** `gh run list --commit <sha>` returns an **empty
array for an ABBREVIATED sha** and the correct runs for the full 40-character one. The first pass
returned `[]` for all four commits and looked like proof that no runs existed. **A control query —
`gh run list` with no `--commit` — reached the repo and returned a run whose `headSha` was the full
form of one of those very commits**, which is what exposed the truncation. **Fifth member of this
project's clean-looking-zero family, and the second caught by control-query discipline in two days.**

**Standing method for the next landing:** `git rev-parse <sha>` for the full form → `gh run list
--repo cuioss/API-Sheriff --commit <full-sha>` → take the **Maven Build** run whose `event` is
`push` → `gh run view <id> --json jobs` and read `build / deploy-snapshot`. The `issue_comment` runs
share the commit and crowd the default `--limit`, so filter by event.

## Open Defect 5 — BFF secrets missing from the env-var page (closed 2026-08-09)

_Relocated 2026-09-24 by `cleanup` (operator-confirmed)._

5. ~~**MEDIUM, documentation — the BFF secrets missing from the env-var page.**~~ **CLOSED 2026-08-09
   by `PLAN-V02-03` D2 (PR #197, `89a3cfe`).** Verified first-party: `environment-variable-overrides.adoc`
   now carries `SHERIFF_CLIENT_SECRET` and `SHERIFF_SESSION_KEY`, against a previous count of zero
   with a passing control. 0.1.0 and 0.1.1 both shipped with the gap; it is closed for 0.2.0. The
   original entry follows for the audit record.
   <details><summary>original entry</summary>

   **MEDIUM, documentation — RE-VERIFIED LIVE 2026-08-08, DO NOT STRIKE.**
   `doc/user/environment-variable-overrides.adoc` omits `SHERIFF_CLIENT_SECRET` and
   `SHERIFF_SESSION_KEY` although `doc/user/README.adoc` tells operators to read that page before
   assuming an env var exists. The ledger made striking this conditional on PLAN-08B D1 closing it;
   **it did not** — the page carries 18 `SHERIFF_`/`QUARKUS_` entries and neither of those two, with
   a passing control query, while both names appear in five other files. OWNER: `PLAN-V02-03`.
   Preserve the distinction the fix must not lose: fixed runtime keys (`QUARKUS_*`) versus
   author-chosen placeholders — `SHERIFF_CLIENT_SECRET` is a convention shown in examples, not a
   fixed name. Do **not** "fix" the page's deliberate exclusion of issuer identity, audience and
   JWKS location; that is policy, not a deployment-bound value.

## Decision 2026-08-08 — lessons corpus audit and PLAN-V02-17 re-clustering

_Relocated 2026-09-24 by `cleanup` (operator-confirmed)._

- **2026-08-08 — the lessons corpus was audited and `PLAN-V02-17` re-clustered.** Operator-requested
  read-only audit of all 25 archived lessons (the live `manage-lessons` store is empty — the
  close-out drained it). Six defects found and all six resolved **directly in the spec**, not
  deferred:
  - **A contradiction between two active lessons is resolved toward REVERT.** `2026-07-27-09-001`
    said *keep the formatter's output, commit it as-is*; `2026-08-02-17-001` said *revert the
    unrelated churn wholesale*. Same situation, opposite prescriptions, and the original clustering
    put them in different groups so nobody would have seen the conflict at landing. Resolved toward
    revert on the operator's standing note and `2026-08-08-11-001`'s corroborating aside; `09-001`'s
    counter-argument (the diff reappears) is true and is recorded as the cost of `main` not being at
    the formatter's fixed point.
  - **`2026-08-02-15-003`'s prescription is refuted by shipped code** and is re-scoped to its
    diagnosis. *"Never bind teardown to `post-integration-test`"* is contradicted by
    `demo-client/pom.xml`:144–159 plus a 15-line rationale, the `if: always()` CI teardown at
    `demo-client-e2e.yml`:83, and `playwright-suite.adoc`:269–276. **This is the second refuted
    prescription in the corpus; the plan previously knew about only one.**
  - **`2026-08-02-15-004` is already in repository source** (`start-dev-environment.sh`:31–37, :221)
    → disposition changed to already-covered.
  - **`2026-08-05-10-001` is settled as a discard with evidence** — the unqualified-refspec construct
    it prescribes a fix for no longer exists in the release skill.
  - **`2026-08-02-15-002` was double-dispositioned** (successor ledger *and* named by V02-17).
    Adopted into V02-17 **deliberately**, with the reason recorded: the ledger copy is
    orchestrator-facing, the `CLAUDE.md` copy is implementer-facing and is the parent of D3's
    specific case.
  - **Three lessons (`2026-08-07-18-001/-002/-003`) have no body** while claiming one. All are
    correct discards, so no work is owed; recorded in V02-17's Appendix because the reasoning behind
    two durable release-lane rules is now unrecoverable, and the files are in a closed epic's frozen
    tree that no write boundary permits editing.

  **The central re-clustering**: `2026-08-02-17-001` was filed as a false-green lesson and is not one
  — the gate *passes*, it just mutates the tree while exiting 0. Its real siblings are
  `2026-07-27-09-001` and standing rule (4) below. One mechanism, three faces, and the most
  enforceable group in the corpus: a post-gate `git status --porcelain` assertion covers all three.
