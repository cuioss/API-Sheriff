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

## Inbox Drain — 2026-10-06 (sender `plan-v02-14-offline-config-validation`)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

Twelve messages, all valid, all consumed and archived; the queue is empty afterwards. Five of the
eleven lessons were recurrences of lessons already filed and were appended to them.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `-012` | landing | reconciled | `landings/PLAN-V02-14.md`; queue row shipped |
| `-001` | candidate-lesson | folded | recurrence appended to `2026-10-03-06-005` |
| `-002` | candidate-lesson | promoted | `2026-10-06-08-001` — the pre-push quality gate does not run the api-sheriff module tests |
| `-003` | candidate-lesson | folded | recurrence appended to `2026-10-04-09-002` |
| `-004` | candidate-lesson | promoted | `2026-10-06-08-002` — LoopbackEphemeralBindArchTest rejects source-level Unicode escapes in test sources |
| `-005` | candidate-lesson | promoted | `2026-10-06-08-003` — a self-review author excused a stale ordinal by citing a numbering that does not exist |
| `-006` | candidate-lesson | promoted | `2026-10-06-08-004` — `ci checks pull-request-runs` reported zero runs while the PR's runs were starting |
| `-007` | candidate-lesson | folded | recurrence appended to `2026-10-03-06-004` |
| `-008` | candidate-lesson | promoted | `2026-10-06-08-005` — not every integration-test config set is mounted by compose; two run as standalone one-off containers |
| `-009` | candidate-lesson | folded | recurrence appended to `2026-10-04-09-003` |
| `-010` | candidate-lesson | folded | recurrence appended to `2026-10-04-09-004` |
| `-011` | candidate-lesson | promoted | `2026-10-06-08-006` — Three review-bot findings on PR #387: a hand-kept coverage list needs a growth guard, line records must escape newlines, and error-grouping claims must be checked against the resolver |

The `-0NN` rows are `plan-v02-14-offline-config-validation-0NN.md`.

## Inbox Drain — 2026-10-05 (sender `fix-four-audit-findings-and-bff-flaky-test`, a plan outside this epic)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

One message, valid, consumed and archived; the queue is empty afterwards.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `fix-four-audit-findings-and-bff-flaky-test-001.md` | finding | observed | Corroborated: #385 merged as `11f9c38a`, 34 PR checks green, no unresolved review thread, post-merge benchmark (run 37315527076) and `main` Maven Build (run 37315523469, `deploy-snapshot` included) green, ADR-0060 `Proposed`. Absorbed: the ADR watch now covers ADR-0059 and ADR-0060; a new watch for the `DispatchStage` decision; `PLAN-V02-06` D8's `gw-02` note narrowed to what is still unpinned; the module-attribution gap appended as a recurrence to lesson `2026-10-03-06-007`. No queue change. |

## Inbox Drain — 2026-10-04 (senders `plan-v02-13-terminal-rejection-contract`, `plan-v02-10-per-client-tls-trust`)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

Nineteen messages, all valid, all consumed and archived; the queue is empty afterwards (no sender has
filed a stream-end marker). Duplicates were clustered before promotion: two pairs and a group of four
became one lesson each, and three recurrences of lessons filed on 2026-10-03 were appended to those
lessons instead of creating new ones.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `plan-v02-13-…-001` | landing | reconciled | `landings/PLAN-V02-13.md`; queue row shipped |
| `plan-v02-10-…-018` | landing | reconciled | `landings/PLAN-V02-10.md`; queue row shipped |
| `-001` | candidate-lesson | promoted | `2026-10-04-09-001` — Prove a "pre-existing" failure by reproducing it on the base, not by arguing from the diff |
| `-002` | candidate-lesson | promoted | `2026-10-04-09-002` — Two Maven builds in one worktree at the same time cause false build failures |
| `-003` | candidate-lesson | folded | recurrence appended to `2026-10-03-06-005` (scope_creep_check) |
| `-004` | candidate-lesson | folded | recurrence appended to `2026-10-03-06-006` (flag at the wrong level) |
| `-005` | candidate-lesson | folded | recurrence appended to `2026-10-03-06-006` (`--number`) |
| `-006` | candidate-lesson | promoted | `2026-10-04-09-003` — Deep-lane outline left the assessment store empty, so assessment coverage could not be checked |
| `-007` | candidate-lesson | promoted | `2026-10-04-09-004` — A search for the removed code literal misses prose that repeats the claim the change makes false |
| `-008` | candidate-lesson | promoted | `2026-10-04-09-005` — A survey list described as "the search result" must be checked against that search |
| `-009` | candidate-lesson | promoted | `2026-10-04-09-006` — Finalize-time obligations written only in Approach prose reach no task |
| `-010` | candidate-lesson | promoted | `2026-10-04-09-007` — keyword_drift flags tool names that a task takes word for word from the outline |
| `-011` | candidate-lesson | promoted (aggregated with -012) | `2026-10-04-09-009` — Derive a guard's stated limit from its matcher, not from intuition, in every copy of the claim |
| `-012` | candidate-lesson | promoted (aggregated with -011) | `2026-10-04-09-009` — Derive a guard's stated limit from its matcher, not from intuition, in every copy of the claim |
| `-013` | candidate-lesson | promoted | `2026-10-04-09-008` — Limit "no instance in this repository" claims to the population that was actually checked |
| `-014` | candidate-lesson | promoted (aggregated with -015, -016, -017) | `2026-10-04-09-010` — A guard's documented coverage must match the population it actually iterates, for every route an argument can take |
| `-015` | candidate-lesson | promoted (aggregated with -014, -016, -017) | `2026-10-04-09-010` — A guard's documented coverage must match the population it actually iterates, for every route an argument can take |
| `-016` | candidate-lesson | promoted (aggregated with -014, -015, -017) | `2026-10-04-09-010` — A guard's documented coverage must match the population it actually iterates, for every route an argument can take |
| `-017` | candidate-lesson | promoted (aggregated with -014, -015, -016) | `2026-10-04-09-010` — A guard's documented coverage must match the population it actually iterates, for every route an argument can take |

The `-0NN` rows are `plan-v02-10-per-client-tls-trust-0NN.md`.

## Inbox Drain — 2026-10-03 (sender `plan-v02-08-fapi-2-0-conformance`, at its landing)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

Nine messages, all valid, all consumed and archived; the queue is empty afterwards (no sender has
filed a stream-end marker). The eight candidate lessons describe plan-marshall tooling observed
while this plan ran; they are filed in this repository's store, where they were observed, for the
lessons mode to route.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `-010` | landing | reconciled | `landings/PLAN-V02-08.md`; queue row shipped |
| `-002` | candidate-lesson | promoted | `2026-10-03-06-001` — pre-submission self-review does not converge on a large prose-heavy change |
| `-003` | candidate-lesson | promoted | `2026-10-03-06-002` — main moves under a long finalize, and the baseline is only checked at entry |
| `-004` | candidate-lesson | promoted | `2026-10-03-06-003` — review-bot size limits are met only after the pull request exists |
| `-005` | candidate-lesson | promoted | `2026-10-03-06-004` — the queue-landing wait is shorter than the queue's own re-test, and its fallback draws on a budget other steps have already spent |
| `-006` | candidate-lesson | promoted | `2026-10-03-06-005` — the scope-creep guard fails exactly when it has something to report, and it counts upstream merges as plan scope |
| `-007` | candidate-lesson | promoted | `2026-10-03-06-006` — the same guessed flag is rejected again in every fresh dispatch |
| `-008` | candidate-lesson | promoted | `2026-10-03-06-007` — module attribution does not resolve this project's Maven modules, so scoped gates silently become whole-tree gates |
| `-009` | candidate-lesson | promoted | `2026-10-03-06-008` — a long integration run can spend its whole budget on an environment stall, and a `timeout` status says nothing about the change |

## Inbox Drain — 2026-09-24 (sender `deployment-configurability`, closing hand-off)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

20 messages, all from the sibling epic `deployment-configurability` as it closed: 2 findings and 18
candidate lessons. Each one was verified against `origin/main` at `05f6ee3` before being
dispositioned, and each has a matching `decision.log` line.

**The headline finding was already stale, and in a worse way than it said.** Message `-001` reported
a duplicate ADR ordinal `0050`. PR #348 (the sibling's PLAN-29) did clear it, by renaming the portal
ADR to `0053`. But PR #346 claimed `0053` for the header-matcher ADR concurrently and merged
**afterwards**, so `main`, and released `0.2.3`, carry a new duplicate, `0053`. The ordinal space has
now collided twice in one week, across two epics, with nothing to catch it. That is why
`PLAN-V02-19` adds a uniqueness contract test as well as the renumber.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `-001` | finding | staged | `PLAN-V02-19` D1+D2. The 0050 claim is refuted as stale; the live 0053 duplicate is fixed and guarded. |
| `-002` | candidate-lesson | promoted | `2026-09-24-15-001`. The stale-image half is fixed (#341); the open half is the local `revision=dev` label, which cannot discriminate images. |
| `-003` | candidate-lesson | folded | `PLAN-V02-19` D3, the module-list contract test. This is open, unowned work rather than a lesson. |
| `-004` | candidate-lesson | discarded | Fixed in PR #314 and mechanically guarded by `TokenClientDslJsonReflectionTest.shouldRegisterEveryEngineDslJsonConverter` (verified on `main`). |
| `-005` | candidate-lesson | promoted | `2026-09-24-15-002` |
| `-006` | candidate-lesson | promoted | `2026-09-24-15-003` |
| `-007` | candidate-lesson | folded | Watch (30), recorded as its third recurrence (tooling, owned upstream). |
| `-008` | candidate-lesson | promoted | `2026-09-24-15-004`, the closed-set-restated-at-N-sites lesson. It also carries `-013` and `-016`. |
| `-009` | candidate-lesson | promoted | `2026-09-24-15-005` |
| `-010` | candidate-lesson | promoted | `2026-09-24-15-006` |
| `-011` | candidate-lesson | promoted | `2026-09-24-15-007` |
| `-012` | candidate-lesson | promoted | `2026-09-24-15-008` |
| `-013` | candidate-lesson | folded | Into `2026-09-24-15-004`, as a recurrence of the same shape in the same plan. |
| `-014` | candidate-lesson | promoted | `2026-09-24-15-009` |
| `-015` | candidate-lesson | promoted | `2026-09-24-15-010` |
| `-016` | candidate-lesson | folded | Into `2026-09-24-15-004`. Its `review_body` half is already binding via `CLAUDE.md` Git Workflow steps 6 and 8. |
| `-017` | candidate-lesson | promoted | `2026-09-24-15-011` |
| `-018` | candidate-lesson | promoted | `2026-09-24-15-012` |
| `-019` | candidate-lesson | promoted | `2026-09-24-15-013`, quantified evidence for any revisit of `re_review_on_loopback: false`. |
| `-020` | finding | folded | `PLAN-V02-19` D4 (Sonar `java:S3398`). The claim is verified: the method is called only from nested `HttpUpstreamFetcher`. |

All 13 promotions were filed with `--allow-foreign-store`. The store's bundle-ownership guard does
not recognise project-local component names (`api-sheriff`, `project:…`, `integration-tests`) as
belonging to this repo, but these lessons are about this repository and lived in this same store
before the sibling epic closed. Bodies are carried verbatim from the payloads, each with a
provenance section.

## Renumbering Map — `api-sheriff-next` → this epic

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

The `api-sheriff-next` backlog was split by target version on **2026-08-04** and retired. Plan specs
were renumbered; **their in-body `PLAN-NN` references were deliberately NOT rewritten**, because many
point at `api-sheriff-roadmap` plans that keep their own numbers. Resolve any in-body reference
through this map first; if the number is not listed here, it belongs to `api-sheriff-roadmap` or to
`api-sheriff-0-3-0` and is unchanged.

| Was | Now | Plan |
|---|---|---|
| PLAN-38 | **PLAN-V02-01** | ADR-0005 reversal / Quarkus adoption |
| PLAN-39 | **PLAN-V02-02** | Java idiom sweep |
| PLAN-40 | **PLAN-V02-03** | Documentation restructure |
| PLAN-47 | **PLAN-V02-04** | ADR corpus cleanup |
| PLAN-17 | **PLAN-V02-05** | Response hygiene |
| PLAN-18 | **PLAN-V02-06** | Enumeration hardening |
| PLAN-19 | **PLAN-V02-07** | Threat classification |
| PLAN-49 | **PLAN-V02-08** | FAPI 2.0 conformance |
| PLAN-46 | **PLAN-V02-09** | Token-Sheriff integration fidelity |
| PLAN-48 | **PLAN-V02-10** | Per-client TLS trust |
| PLAN-44 | **PLAN-V02-11** | Resource-exhaustion test home |

Moved to `api-sheriff-0-3-0`: PLAN-20 → PLAN-V03-01, PLAN-21 → PLAN-V03-02, PLAN-22 → PLAN-V03-03,
PLAN-41 → PLAN-V03-04.

## Open Defect 13 — applyJwks orphan, folded into PLAN-V02-09 (closed 2026-08-09)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

13. **LOW — CLOSED AS AN ORPHAN 2026-08-09: FOLDED INTO `PLAN-V02-09`.** `TokenValidatorProducer.applyJwks` qualifies for a switch conversion but sat outside
    `PLAN-V02-02` D5's declared surface and was correctly left alone. **Natural home: `PLAN-V02-09`**,
    which already owns that file. Fold it there rather than carrying it as standalone work.

## Open Defect 15 — snapshot deploy 401 after the Maven 3.10.0 bump (resolved 2026-10-03)

_Relocated 2026-10-06 by `cleanup` (operator-confirmed)._

15. **RESOLVED 2026-10-03 — snapshot deploy to Central failed with HTTP 401 on `main` after the Maven
    3.10.0 wrapper bump.** Maven 3.10.0 only sends a server's credentials to origins associated with its
    id, and the `central` server written by the organisation workflow declared none beyond
    `https://repo.maven.apache.org`. Fixed in `cuioss-organization` #304 (v0.35.0), which declares
    `https://central.sonatype.com` in the `central` server's `<repositoryOrigins>` in both
    `reusable-maven-build.yml` and `reusable-maven-release.yml`; rolled out here by #381 (`082d2a13`).
    Verified: `Maven Build` run 37112992150 — `deploy-snapshot` success, `Verified
    de.cuioss.sheriff.gateway:api-sheriff-parent:0.2.4-SNAPSHOT published: snapshot timestamp
    20261003.095107` (previous 20261002.131138), Central's snapshot metadata carries the same
    timestamp, and the "Not using credentials" warning is gone. cuioss-parent-pom and TokenSheriff
    are green on the same fix. The release path uses the same fix but has not run yet.
