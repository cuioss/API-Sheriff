# Landing — PLAN-V02-02: Java idiom sweep

epic: api-sheriff-0-2-0 · workstream: WS-01
**PR [#198](https://github.com/cuioss/API-Sheriff/pull/198) · merge commit `e343404` · merged 2026-08-09 03:53 UTC**
plan_marshall_plan_id: `plan-v02-02-java-idiom-sweep`

## Corroboration — what the orchestrator verified first-party

Every material claim below was checked against ground truth, not taken from the landing narrative.

| Claim | Verdict | Evidence |
|---|---|---|
| Merged to `main` as `e343404` | **corroborated** | `git merge-base --is-ancestor e343404 origin/main` → yes; `origin/main` head is `e343404` |
| Issue #178 closed | **corroborated** | `ci issue view --issue 178` → `state: closed` |
| Warning gate is real | **corroborated** | `pom.xml`:163 `<showDeprecation>true</showDeprecation>`, :178 `<failOnWarning>true</failOnWarning>` |
| `Require` enum shipped | **corroborated** | `config/model/Require.java` exists |
| The fail-open fix is in the tree | **corroborated** | `AuthenticationStage.java`:124 `case null -> throw`, with the load-bearing rationale at :112 |
| Deprecated property migrated across all carriers | **corroborated** | zero hits for the bare old spelling; 19 hits for `…enabled` across the same six files |

## Deliverable fidelity — the spec said four, nine shipped

The spec staged **4** deliverables; the plan shipped **9**. This is decomposition, not scope creep:
D4(a) *"switch on the lint first"* became D1, and the spec's D2 (deprecations) split into research,
property migration and Java-site retirement. **The spec's named line items all survive** — the epic's
standing NAMED-LINE-ITEMS clause is satisfied, and D4(b)'s research half, the one most at risk of
being dropped, shipped as its own deliverable 8.

**D4(a) ran first inside the plan, as the spec instructed** — so D2's enumeration came from the
compiler rather than from grep. That instruction paid: the measured deprecation set was **5 sites
across 4 files**, not the 1–2 the spec implied, and `integration-tests` and `benchmarks` were clean,
which is what made a reactor-wide `failOnWarning` safe.

## The finding that matters most: a fail-open auth path, caught by self-review

The plan removed `AuthenticationStage`'s trailing `throw` believing an enum switch is
exhaustiveness-checked. **It is not.** A constant-only `switch` *statement* is a *legacy* switch and
`javac` applies no exhaustiveness requirement to it. A future fourth `Require` constant would have
made `process()` a silent no-op while `effectiveAccessLevel` still reported the route
`AUTHENTICATED` — a fail-open authentication decision produced by a compiler guarantee that does not
exist.

Fixed by `case null -> throw`, which promotes the construct to an *enhanced* switch and restores the
check. **Caught by `pre-submission-self-review`, not by any gate** — the quality gate, the full
verify and CI were all green over the fail-open form. Promoted to the corpus.

## Two premises refuted, and one operator ruling rested on one of them

- **The `require` enum is not a security improvement.** Both schemas already enum-gate the value and
  `ConfigLoader` validates before bind, so the illegal-value path was already closed and there is no
  behavioural delta. **The operator had approved the conversion partly on security grounds.** The
  plan re-asked rather than proceeding on a rationale it had just refuted; the operator re-confirmed
  on **type-safety-only** grounds, and that narrowed rationale is recorded in the PR body so a
  reviewer cannot read more into it. *This is the verify-first contract working as designed.*
- **A missing `ConfigModelReflection` registration is NOT invisible to JVM gates.**
  `ConfigModelReflectionTest` walks the record-component graph. The residual is **package scope
  only**. The orchestrator's own cross-session memory carried the falsified headline and **has been
  corrected**; the body had been right all along, which is precisely how the stale form propagated.

## Reconciliation actions

- **Queue**: `PLAN-V02-02` → `shipped`; `pr`, `landing`, `plan_marshall_plan_id` stamped.
- **Spec correction owed and applied**: the plan reports D3's occurrence count as **20** where the
  spec said **19**. The orchestrator re-measured at `e343404`: **19** across six carrier files. The
  two counts are not reconciled and the discrepancy is recorded rather than resolved by fiat — see
  the epic's Watches. **The candidate-lesson's rule stands regardless of whose number is right**: a
  spec-asserted count is a claim, and this one propagated spec → clarified request → outline
  unchallenged.
- **No parallelization collision.** `PLAN-V02-03` ran concurrently and landed first (`89a3cfe`); the
  predicted soft overlap on `CLAUDE.md` did not materialise as a conflict. The disjointness call
  recorded at emit held.

## Residue carried to the epic

1. `RouteRuntimeAssembler` allocates a per-tuple `HttpClient` **and** a resilience `Guard` for
   `WEBSOCKET` routes that no longer read them. Collapsing it is a behavioural change to boot-time
   allocation plus a nullability-contract change reaching `RouteRuntime` and `DispatchStage` — a
   design task, not a sweep edit. **Reported, not swept** — the epic's standing rule applied
   correctly.
2. `TokenValidatorProducer.applyJwks` qualifies for a switch conversion but sat outside D5's declared
   surface, so it was left alone. Natural home: `PLAN-V02-09`, which already owns that file.

## Process notes worth keeping

- **An operator dependency approval went unused.** `vertx-core` 4.5.30 already ships
  `WebSocketClient`, so no artifact was added. The agent checked the classpath before spending the
  permission — the right order, and the interaction could have been avoided entirely by checking
  first.
- **A mid-finalize premise error was caught by the agent, not by the orchestrator.** The orchestrator
  told branch-cleanup that no upstream commits had landed; PR #197 had merged since. The agent
  **refused to rebase** rather than lapse the merge authorization and destroy the CI/Sonar evidence
  keyed to that SHA. Re-rebased, re-gated, re-ran CI, re-granted. CodeRabbit's rate window reset in
  that cycle and it reviewed the final head with zero new findings. **The guard worked; the
  orchestrator's stale premise is the defect** — it is the same shape as the epic's standing rule
  that a claim needs a read behind it.

## Post-merge verification — OWED, not complete

Per the epic's standing Watch (16)+(20), the main-branch `deploy-snapshot` check has **no sanctioned
execution path** from here: it is skipped on every PR by design, requires a by-merge-commit lookup,
`ci checks status` accepts only `--pr-number`/`--head`, and the carve-out forbids `gh`. **Recorded as
OWED for `e343404`, never as complete.** The PR-attached benchmark run (`pull_request: closed`,
gated on `merged == true`) is likewise unverified here.

> **UPDATE 2026-08-09 — POST-MERGE VERIFICATION IS NO LONGER OWED. IT IS DONE AND GREEN.**
> `build / deploy-snapshot` = **success** for `e343404` (Maven Build run `31294044453`), as is every other job
> in that run. The check was reachable all along via one read-only `gh` call; the abstraction has no
> commit-to-run path, which had been mistaken for the axis being unverifiable. See
> `epic.md` § Post-Merge Verification for the method and the abbreviated-SHA false negative it survived.
