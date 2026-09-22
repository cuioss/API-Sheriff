# Landing — PLAN-V02-17: lessons into repository source

epic: api-sheriff-0-2-0 · workstream: WS-02
**PR [#200](https://github.com/cuioss/API-Sheriff/pull/200) · merge commit `95dd566` · merged 2026-08-09**
final reviewed-and-rebased HEAD `e03b6a6` · plan_marshall_plan_id: `plan-v02-17-lessons-into-source`

## Corroboration — verified first-party

| Claim | Verdict | Evidence |
|---|---|---|
| Merged to `main` as `95dd566` | **corroborated** | ancestor of `origin/main` |
| D1 — the gate-discipline page exists | **corroborated** | `doc/development/build-gate-discipline.adoc`, 12,312 bytes |
| D6 — the non-gating rewrite job exists | **corroborated** | `.github/workflows/maven.yml`:185 `rewrite-report`, :186 *"OpenRewrite dirty-tree report (non-gating)"* |
| The goal-prefix defect is fixed **and documented at the site** | **corroborated** | `maven.yml`:214–217 explains full coordinates vs. prefix resolution and names the exact failure |
| The premise correction landed | **corroborated** | `build-gate-discipline.adoc`:46 *"`main` is at the fixed point today"*; :21 and :102–103 frame it as a property of the tree, not a guarantee |

## The plan's central premise was false — and it was mine

**I wrote it.** When I rewrote this spec on 2026-08-08 I resolved the contradiction between lessons
`2026-07-27-09-001` (*keep the formatter's output*) and `2026-08-02-17-001` (*revert the unrelated
churn*) toward **revert**, and I supplied this reason:

> *"the diff reappearing is the cost of `main` not being at the formatter's fixed point, and paying
> it per-PR is cheaper than polluting every scoped PR with ~170 unrelated files"*

and instructed the plan to *"record that bringing `main` to the fixed point is the durable fix nobody
has done."*

**A full-reactor gate run during execution produced zero rewrites. The repository IS at the fixed
point.** The executor escalated rather than landing the specced claim, the operator chose to correct
it, and the shipped page now records the observed condition plus the drift risk.

**What survives and what does not.** The *verdict* — revert unrelated churn — survives. My
*reason for it* does not. The page now holds the better one: the fixed point is **a property of the
tree at a point in time, not a guarantee** (`:21`, `:102–103`), so the rule stands *because* of how
fragile that property is rather than because the tree is broken today. That is a stronger argument
than the one I gave, and it was reached by **running the gate rather than reading the corpus**.

**The plan became the first customer of the rule it was landing** — its own D2 cluster says a build
that reports it did nothing has verified nothing, and *assert positive evidence, never the exit
code*. The two source lessons described a historical state; I inherited it and never re-measured.

## The enforceable check failed on its first real CI run

D6's whole point was to make the gate-mutation cluster **enforceable** rather than merely stated. The
job invoked `rewrite:run` by **goal prefix**, which requires a Maven Central fetch of
`org.openrewrite.maven/maven-metadata.xml` — the prefix-to-coordinates mapping lives only there, so a
fully-declared plugin in an active profile cannot answer it. Central returned **429**, and the job
died with `No plugin found for prefix 'rewrite'` **having never executed the gate at all.**

Fixed with full coordinates, and the reasoning is recorded at the site (`maven.yml`:214–217).

**The reported counterfactual is the valuable part**: *"had I marked `ci-verify` green off the local
evidence, this would have shipped broken."* A check that dies before running is the epic's
clean-looking-zero family wearing a new costume — and this one would have shipped **as the enforcement
mechanism for the lesson about checks that do not run.**

## Two gaps not papered over

- **No bot reviewed the final rebased HEAD `e03b6a6`.** CodeRabbit reviewed at `e7a5c7c` (9 findings,
  6 fixed, the rest answered with recorded rationale); its OSS refusal then permanently consumed the
  range. **The override rests on the rebase being a byte-identical replay onto a disjoint commit, not
  on review at that SHA** — stated plainly rather than recorded as "reviewed". Correct handling of
  the standing rate-limit note; **second consecutive landing with partial bot coverage on the final
  head** (see `PLAN-V02-03`).
- **The Sourcery noise filter has a structural defect.** `automatic-review/standards/sourcery.md`
  lists Sourcery's OSS branding footer in `ignore_patterns`, which is a **whole-comment substring
  drop**. Sourcery appends that footer to **every** review body on an OSS repo — so on any OSS
  repository its Overall Comments **can never be filed**. It cost a real finding here, recovered only
  because an agent read the raw review. The same file contradicts itself at its producer rule.

## Reconciliation actions

- **Queue**: `PLAN-V02-17` → `shipped`; `pr`, `landing`, `plan_marshall_plan_id` stamped.
- **The V02-17 ⇄ V02-01 yield instruction is discharged** — `PLAN-V02-01` never started, so the
  overlap it guarded against never materialised. The carve-out recorded at the emit held for both
  companions.
- **No new Open Defect.** Every finding here is either shipped, filed as a corpus lesson, or already
  tracked. The rate-limit residual folds into the existing note rather than becoming a duplicate.

## The pattern this landing completes

Four landings, and in **three of them the specs I authored carried a defect the plan found by
executing**:

| Plan | Defect in my spec | Found by |
|---|---|---|
| `V02-02` | occurrence count asserted, not derived | measuring during the run |
| `V02-16` | Expected Surface accurate but **insufficient** — named the consumer, not the channel | attempting to implement it |
| `V02-17` | premise about current repo state **stale/false** | running the gate |

**The common shape is not carelessness in any single spec — it is that a spec asserts facts at
authoring time and is executed later, and nothing in between re-measures.** V02-16's was a
*sufficiency* failure, this one a *freshness* failure, V02-02's an *arithmetic* failure. Recorded as
a standing Watch: **every spec sentence asserting current repository state is a measurement with a
timestamp, and the consuming phase re-establishes it rather than inheriting it.**

## Post-merge verification — OWED

`95dd566`'s main-branch `deploy-snapshot` run is unreachable through the CI abstraction. **Recorded
as OWED** — now for four merge commits: `89a3cfe`, `e343404`, `aeb80c5`, `95dd566`.

> **UPDATE 2026-08-09 — POST-MERGE VERIFICATION IS NO LONGER OWED. IT IS DONE AND GREEN.**
> `build / deploy-snapshot` = **success** for `95dd566` (Maven Build run `31330642464`), as is every other job
> in that run. The check was reachable all along via one read-only `gh` call; the abstraction has no
> commit-to-run path, which had been mistaken for the axis being unverifiable. See
> `epic.md` § Post-Merge Verification for the method and the abbreviated-SHA false negative it survived.
