# Landing Analysis: PLAN-23 — Session Routes Obtain the Scopes They Declare

epic: kidicap-gateway-requirements
workstream: WS-04
pr: #369 (merged as e445e299ae381fe2a894fb9b7251a5fb456aec00)

> Landing record for one shipped plan. Lives at `landings/PLAN-23.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the operator's pasted finalize report plus the plan's on-disk artifacts — NOT an inbox landing
message, because none was filed.** `emit-landing` reports `SKIP — not orchestrated, no landing emitted`.
That skip is diagnosed below and is this landing's most consequential finding, since it is the first
break in the plan→epic channel in nine plans. Corroborated 2026-10-02: `ci pr view 369` reports
`state: merged` with `merge_commit_sha` e445e299; `ci checks status` reports 34 checks, overall `success`,
including the PR-attached post-merge benchmark (run 37007316288). 103 files in the merged diff. Plan
archived at `.plan/local/archived-plans/2026-10-02-plan-23-session-route-scope-parity`.

## Deliverable Fidelity vs Spec

9 of 9 shipped. The spec's four acceptance rows, including the `session_fallback` row folded in from
PLAN-19, are all covered by `BffSessionScopeParityIT`.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 Session-route scope comparison before relay | shipped-as-specified | enforcement on every session route (and on the session branch of a `session_fallback` bearer route) before relay |
| 2 Refresh when the scope is inside the grant | shipped-as-specified | scope-driven refresh leg on the refresh coordinator; granted set `S` added to the session record and both bindings (deliverable 1 of the shipped list) |
| 3 Widen the live session when outside the grant | shipped-as-specified | silent widening first, then **one** interactive retry |
| 4 Machine-readable step-up answer for non-navigation | shipped-as-specified | `403 problem+json` carrying `missing_scopes` and a `step_up_url`, on a new reserved step-up path |
| 5 IdP refusal terminal, no loop, never an under-scoped relay | shipped-as-specified | stated terminal in the shipped behaviour |
| 6 ADR amendment | shipped-modified, better | **ADR-0057 supersedes ADR-0049's session half** rather than amending it — the cleaner record, and it respects the spec's instruction not to take ordinal 0053 |
| 7 Tests per acceptance row | shipped-as-specified | `BffSessionScopeParityIT` |
| 8 Documentation | shipped-as-specified | session-route scope behaviour documented |
| 9 Retire the three PLAN-19 caveats | shipped-as-specified | the `configuration.adoc`, `bff-session.adoc` and BFF-16 caveats are retired, as the fold required |

**Scope added during review, all merged, each with a test** — and two of them fix defects that were
already on `main` rather than anything this plan introduced:

- **A logout race in server mode**: a session ended by logout or back-channel logout could be recreated by
  an in-flight refresh or widening. The refresh half of that race predates this plan.
- `account_selection_required` now gets the one interactive retry a silent widening is allowed.
- Stricter boot validation on `oidc.step_up.path`, `oidc.login.path` and `oidc.user_info.path` (query,
  fragment, backslash, control characters, whitespace all refused).
- Six Sonar issues fixed; the hand-kept log-reason lists now point at the authoritative entry.

## Metrics and Anomalies

- **26,636,749 tokens and 174 h 2 m wall** (10 h 39 m worked) — by far the most expensive plan of the
  nine, roughly 3.3x the next highest (PLAN-15's 10.4M). Finalize alone consumed 18.0M tokens across
  **9 fix-and-recheck rounds**; execute's 147 h wall is mostly idle.
- Sonar new-code issues: 0 (confirmed). Quality gate and full verify green on the final commit, 4442 tests.
- `pre-submission-self-review` was **skipped by operator decision after round 5 of 5** — its budget was
  exhausted before the PR existed, and the later fixes were reviewed by the bots and Sonar instead. The
  mechanism behind that exhaustion (pre-PR self-review and post-PR review-fix rounds sharing ONE loop-back
  counter) is filed as a plan-marshall lesson.
- `pre-push-quality-gate` reports **0 bundles** — the gate-bundle derivation returned an empty gate set for
  a 103-file Java footprint. Already a filed lesson; recorded here because the whole-tree gate ran anyway,
  so nothing went unverified.
- **Surface: declared 18 entries, realized 103 files — 61 undeclared, 1 declared-but-untouched.** The
  largest expansion of the epic (~5.7x) and the largest footprint. Most of it is the review-driven scope
  above, which is honest growth rather than under-declaration — but the declaration was never updated to
  match it, so the gate's view of this plan stayed 18 files wide while it worked across 103.

## Routing and Merge Behavior

- Review: 0 comments on the final commit; both review threads resolved. One reviewer slot came back
  `refused-structural` and Sourcery never reviewed (over its size limit, optional). The
  `triage pending` text in the `automatic-review` row is fixed step wording, not an open item.
- CI/merge: merge queue; `main` up to date, worktree removed, working tree clean.
- **Post-merge verification:** the operator reports all post-merge runs green — main-branch build
  **including snapshot deployment**, integration tests, and the benchmark. The benchmark is independently
  corroborated here (run 37007316288). **This is the first merge commit in the epic whose main-branch
  Maven Build is reported green**; the earlier six remain unobserved through the CI abstraction.

### Why no landing message was filed — the orchestrator's own doing

`emit-landing` resolves orchestration from the plan's stored `source_id`, which `phase-1-init` persisted on
2026-09-25 as the path the spec had then:

```text
.plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-23-session-route-scope-parity.md
```

Reproduced at 2026-10-02 on the current executor:

| `--source-id` | `detection` | `orchestrated` |
|---|---|---|
| `.plan/local/orchestrator/…/PLAN-23-….md` (what the plan stored) | `unrecognised_id` | **false** |
| `.plan/orchestrator/…/PLAN-23-….md` (where the ledger is now) | `orchestrated` | true |

Two orchestrator-side acts during PLAN-23's run caused it, and the order matters:

1. **Regenerating the executor** (0.1.1719 → 0.1.1826, 2026-10-01, at the operator's request) brought in
   the grammar that classifies `.plan/local/orchestrator/**` as the RETIRED address — `unrecognised_id`.
   Under the old executor the same pointer resolved `orchestrated: true`. **This step alone was
   sufficient**: the reclassification does not depend on the move.
2. **Relocating the ledger** into the tracked store made the new address correct, but nothing rewrote the
   pointer the running plan had already persisted.

So the plan behaved correctly on the evidence it had: it asked whether it was orchestrated, was told no,
and skipped the emit rather than writing into a path it could not resolve. Nothing was lost — the operator
pasted the report and the plan's artifacts are intact — but the automatic channel was broken by this
session's infrastructure changes, mid-flight, and **this is the consequence the four earlier
detector-disagreement recurrences had not yet produced.**

The standing exposure: **`source_id` is a stored PATH resolved at finalize time, so any ledger relocation
silently orphans every in-flight plan's outbox.** Recorded as an Open Defect with the mitigation (no
ledger relocation while a plan is in flight) and filed upstream.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-23 --status shipped`
- [x] rows `pr` `#369`, `landing` `landings/PLAN-23.md`, `plan_marshall_plan_id`
      `plan-23-session-route-scope-parity` — `queue --set-row`
- [x] epic.md reconciled: the downstream's highest-priority defect (`kidicap-gateway-downstream-010`)
      CLOSED; ADR-0049's session half superseded by ADR-0057
- [x] PLAN-20 deliverable 9 (the ADR-0053 renumber) RETIRED — resolved independently by PR #367
      (`fix(adr): resolve ordinal 0053 collision and add contract guards`); `doc/adr/` now carries one
      0053 and the header-matcher ADR sits at 0056
- [x] Open Defects opened for every item the report lists as untracked (below)
- [x] 7 lessons the plan filed into THIS repository's store with `--allow-foreign-store` reconciled: the
      6 plan-marshall ones (plus the earlier `2026-09-24-18-001`) copied byte-for-byte to the owning
      repo's store as `2026-10-02-13-001..007` and retired here with verdict `superseded`; the two
      project-owned lessons (`api-sheriff`, `integration-tests`) stay
- [x] resume_anchor updated

## Follow-Ups — the untracked items this landing surfaces

Ranked, because they are not equivalent:

1. **A threat-model entry asserts a control that does not exist.** Logout does not revoke tokens at the
   IdP — the revocation hook is bound to a **no-op** in the shipped runtime — while BFF-09 is still
   labelled **COVERED**. The docs now say the truth; the threat model does not. A security document that
   over-claims is worse than a missing one, and the plan's own lesson `2026-10-02-12-007` (kept in this
   repo's store) records the pattern.
2. **Cookie mode cannot observe a logout**: a late refresh or widening response can set a fresh cookie.
   Documented as residual; CodeRabbit rates it **Medium** and that rating is not disposed of anywhere.
3. **Two shipped decisions carry no ADR**: the problem+json extension-member rule, and the reserved-path
   boot rule (the ADR check suggests an ADR-0018 amendment for the latter).
4. Three info-level audit items: a widening does not revoke the superseded refresh token; the active scope
   set falls back to the requested set when the provider states none; no boot rule refuses duplicate
   reserved paths.
5. Pre-existing: other boot refusal messages echo configured values raw (`default_view`, trusted proxies,
   base-URL alias, websocket origins, anchor prefixes).
6. Roughly forty older comment inaccuracies in the BFF files.

Items 1–3 are disposition-worthy work; 4–6 are hygiene. None is in any staged spec yet.
