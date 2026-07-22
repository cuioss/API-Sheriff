# Landing Analysis: PLAN-12 — Sonar Hardening (zero findings + compliance policy)

epic: api-sheriff-roadmap
workstream: WS-02
pr: #89 (squash-merged → 5265ed6, single parent d1545a3 verified)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying
> claims against ground truth (actual code, artifacts, PR state, live SonarCloud API,
> live GitHub Actions API) — the operator's landing narrative was the lead; every
> material claim below was independently corroborated 2026-07-21.

## Deliverable Fidelity vs Spec

Plan `sonar-zero-findings`, deep lane (light-lane escalated on explosion), 11 tasks /
7 deliverables, archived to `.plan/local/archived-plans/2026-07-21-sonar-zero-findings`.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 both gate-failing S3655 Optional bugs + regression tests | shipped-as-specified | Merge-commit message names RouteTableBuilder/RouteRuntimeAssembler + both test files; **live gate now OK with new_reliability_rating=1** (was 3) — the decisive ground truth |
| D2 care items (S2589, S112, S3776, S125-NOSONAR) | shipped-as-specified (narrative; consistent with commit trail 115d17d) | Commit on branch; S125 resolved via auditable in-code `// NOSONAR` per the fix-by-default policy — the policy's escape hatch used exactly as designed |
| D3 S1192 literal→constant | shipped-as-specified (narrative) | Commit 8b51949 |
| D4 mechanical sweep (S5778, S7467, S6353, S4276, S6878) | shipped-modified | Sweep landed, but live index still shows S5778×17 + S7467×7 OPEN — propagation lag or unfixed remnants; cannot be distinguished until Sonar closure settles (live Sonar was HTTPS-blocked locally during execution, so the sweep ran partially blind). Trued up by PLAN-13 |
| D5/D6 compliance docs | shipped-as-specified | `doc/development/sonar-quality-gate.adoc` exists on main (verified); CLAUDE.md "Sonar / Quality Gate" section present (reference-first, no threshold restating) |
| D7 operator addendum: README badge links + WRK→k6 prose | shipped-as-specified | Verified on origin/main: both `link=` targets now `benchmarks/integration/index.html` / `trends.html` (both previously curl-verified 200), line 59 prose says k6. **Closes the README-badge-404 open defect.** NOTE: the addendum was recorded in the ledger as passed to PLAN-04 (operator said "passed to plan-4"); it actually executed in PLAN-12 — harmless ownership drift, but PLAN-04's rebase must not conflict-revert these lines |

## Metrics and Anomalies

- Tokens: 2.2M; Duration: 2h49m worked / 5h9m wall; all 6 phases recorded.
- Lane escalation: light → deep on explosion — consistent with the 59-finding scope.
- Anomaly (tooling): workflow-integration-sonar scripts look up credentials by
  unprefixed skill name while credentials_config keys are bundle-prefixed →
  "HTTPS required" failures; worked around via
  `~/.plan-marshall/credentials/workflow-integration-sonar.json` url. Upstream
  plan-marshall bug — candidate lesson/issue for the plan-marshall repo.
- Anomaly (visibility): live Sonar HTTPS-blocked locally during D4 → sweep executed
  against a stale finding list; explains the residual ambiguity.

## Routing and Merge Behavior

- Review: CodeRabbit + Sourcery rate-limited (no review; the red CodeRabbit CI check
  was a bot rate-limit, accepted with rationale — verified not code-related); Gemini
  reviewed clean. Merge queue enforced the required check set.
- CI/merge: squash via platform merge queue → 5265ed6. Post-merge main run attempt 1
  AND attempt 2: build(25/26), sonar-build, integration all green; **deploy-snapshot
  hung ~30 min and was cancelled BOTH attempts** (attempt 2: started 19:48:35Z,
  cancelled 20:18:52Z; conclusion=failure). The narrative's own escalation condition
  is met: Sonatype-side incident or org-workflow timeout tuning → org-admin
  escalation, NOT repo scope. Not caused by the change (no POM/deploy edits).
- Surface collision: PLAN-04 (`protocol-processors-ws-grpc`, at 6-finalize during this
  analysis) merges SECOND → per the collision watch it must rebase onto 5265ed6 and
  re-verify; PLAN-12's sweep touched api-sheriff sources/tests broadly, so overlap
  with PLAN-04's edge/test files is likely, including README (D7 lines).

## Reconciliation Actions

- [x] status.json `plans[]` entry updated (PLAN-12 shipped, pr #89, landing recorded)
- [x] epic.md queue reconciled from status.json
- [x] Defect CLOSED: SonarCloud gate RED on main (gate verified OK via API)
- [x] Defect CLOSED: README benchmark badges 404 (verified fixed on main)
- [x] Defect CLOSED: Sonar provider tooling misconfigured (credentials landed via
      PR #87 + local url workaround; residual upstream bug recorded as watch)
- [x] Defect UPDATED: 59-finding backlog → 34 open post-merge (10 declared residual +
      24 S5778/S7467 lag-or-unfixed); literal-zero ownership → PLAN-13
- [x] Defect OPENED: deploy-snapshot hung twice on main — org-admin escalation
- [x] Watch UPDATED: collision watch → concrete PLAN-04 rebase-over-5265ed6 obligation
- [x] PLAN-13-sonar-residual staged (spec + queue entry)
- [x] resume_anchor updated
- [x] START-HERE block regenerated

## Follow-Ups

- **PLAN-13-sonar-residual staged**: small surgical plan to reach the literal zero
  baseline once (a) Sonar closure propagation settles and (b) PLAN-04 lands (its new
  code joins the same sweep — one re-query covers both). Emit after the PLAN-04 landing.
- Operator: run architecture enrichment to populate empty
  `skills_by_profile.documentation.implementation` in marshal.json (plan flagged it;
  out of its no-config-change scope).
- Operator/org-admin: deploy-snapshot double-hang escalation (Sonatype status or
  org-workflow timeout tuning in cuioss-organization).
- Upstream: file the credentials-key-prefix bug as a plan-marshall lesson/issue.
