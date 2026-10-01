# Settled narrative — kidicap-gateway-requirements

Relocated from `epic.md` by the `cleanup` verb on 2026-09-21, operator-confirmed. Every item's subject is
CLOSED. Content is verbatim; `epic.md` carries a pointer at each origin. Nothing here is re-derived — a
retraction and a resolved defect are the anti-rework record.

## PLAN-15 abandonment retraction

- ~~PLAN-15 has no plan directory and may be abandoned~~ — **RETRACTED 2026-09-17, do not re-derive.**
  The orchestrator looked only at the main checkout's `.plan/local/plans/`; PLAN-15 is running normally
  and its plan state lives in its own worktree at
  `.plan/local/worktrees/routing-and-response-headers/.plan/local/plans/routing-and-response-headers`
  (operator-confirmed). A plan's absence from the main checkout's plan directory is NOT evidence of an
  abandoned plan while its worktree exists.
- RESOLVED 2026-09-18 — PLAN-15's seven PR-body follow-ups are filed as cuioss/API-Sheriff issues:
  #323 `sameOrigin` encoded-spelling blind spot (bug), #324 file-level `declaresLimitShapedKey`
  granularity (bug), #325 anchor-coverage rule restated four times (docs), #326 hand-mirrored IT
  `security_headers`/`cors` fixtures, #327 five slash-stripping helpers, #328 no native IT assertion for
  redirect `no-store`, #329 `forward` block on a redirect route accepted but never read (bug). None is
  owned by a plan in this epic; they are repository backlog. — source: landing `landings/PLAN-15.md`

## PLAN-15 follow-ups filed as issues

- RESOLVED 2026-09-18 — PLAN-15's seven PR-body follow-ups are filed as cuioss/API-Sheriff issues:
  #323 `sameOrigin` encoded-spelling blind spot (bug), #324 file-level `declaresLimitShapedKey`
  granularity (bug), #325 anchor-coverage rule restated four times (docs), #326 hand-mirrored IT
  `security_headers`/`cors` fixtures, #327 five slash-stripping helpers, #328 no native IT assertion for
  redirect `no-store`, #329 `forward` block on a redirect route accepted but never read (bug). None is
  owned by a plan in this epic; they are repository backlog. — source: landing `landings/PLAN-15.md`

## PLAN-15 PR chain, retired

- RETIRED 2026-09-17 — PLAN-15's PR chain (#310–#313, #316–#318 closed unmerged over the CI-trigger
  problem cuioss/cuioss-organization#279) ended with PR #320 merging as 93a4b3e; corroborated on
  `origin/main`. The reviewed work reached `main`. Residue moved to Open Defects.

## cui-http #236 and the 3.1 release, resolved

- RESOLVED 2026-09-17: cuioss/cui-http#236 closed by PR #239; cui-http `3.1` released 2026-09-16 and
  already resolved by API Sheriff through `cui-quarkus-parent` 1.7.5 (commit 799976a). PLAN-13's
  cui-http gate is gone; it now waits only on PLAN-15 landing (shared surface).

## Superseded 12-plan decomposition

Superseded by the 2026-09-15 aggregation decision (4 plans, then 9 after the downstream drains), which
stays in `epic.md`. These three bullets record the first cut and why it was made.

- 2026-09-15 — Workstream cut: 5 workstreams (defect fixes, routing primitives, application portal,
  session and scopes, security headers) instead of the 7 the drafting pass proposed. Alternative (one
  workstream per theme incl. single-plan query-validation and scope workstreams) rejected as
  fragmentation without charter value.
- 2026-09-15 — Plan mapping: 12 plans. The drafted 4-item routing bundle (AS-3/4/11/12) was split into
  PLAN-04 (AS-3 + AS-4, high/medium) and PLAN-12 (AS-11 + AS-12, both low) so low-priority items do not
  ride with high-priority work; they share surface and are sequenced. AS-14 split into PLAN-05
  (additive scopes) and PLAN-09 (scope step-up). AS-5 + AS-6 bundled (PLAN-03, same session-stage
  surface). AS-14 "stage 2" (per-endpoint token narrowing) and AS-1 "stage 2" (visible_when, breaker
  state) deliberately not staged.
- 2026-09-15 — Split guard: PLAN-04 and PLAN-07 carry five deliverables each; both proceed unsplit —
  their parts (schema, resolution, dispatch, validation, tests/docs) cannot ship independently to any
  observable benefit.
- 2026-09-15 — Drafted PLAN-02 wrongly included the consumer's `app`-anchor filter revert and a consumer
  integration check as deliverables; removed. External dependencies (`cui-http` for AS-13, the token
  client engine for AS-14, the token-validation library for AS-7/AS-10) are verify-first clauses,
  never in-repo implementation; any dependency bump needs explicit user approval.
