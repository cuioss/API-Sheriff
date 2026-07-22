# PLAN-13: Project Cleanup — Sonar literal zero + snapshot-deploy diagnosis + dependency hygiene

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Spawned by the PLAN-12 landing analysis; EXPANDED 2026-07-22 (operator: 'Project cleanup');
> REFRESHED post-PLAN-14 landing 2026-07-22: (A) reduced to verify/close (deploy-snapshot
> resolved by PLAN-14 deploy-skip), (C) reduced to rationale-only (#69 auto-merged), (B) must
> rebase over the gateway rename, (D) baseline stale after the 168-package rename — fresh query.
> Originally EXPANDED at operator direction
> ("Project cleanup"): three cleanup aspects folded in ahead of the sonar sweep.
> Scope-bloat note: 6 deliverable groups — proceeding unsplit per the guard's
> recorded-rationale path (operator-directed fold; items are small, thematically one
> hygiene pass, and two of them touch no plan-branch code at all).

## Objective

One project-cleanup pass: (A) VERIFY/CLOSE the deploy-snapshot issue (resolved by
PLAN-14's deploy-skip — confirm the green streak, record the root-cause finding),
(B) fix dependabot PR #70 on its own branch (rebase over the gateway rename first),
(C) record the quarkus version-pin rationale (#69 already auto-merged to 3.37.3),
then (D) drive the SonarCloud open-findings backlog to LITERAL ZERO from a fresh
live query — the old 34 baseline is stale after the 168-package rename.

## Deliverables

1. **deploy-snapshot VERIFY/CLOSE (refreshed)**: the 3-hang issue resolved when
   PLAN-14's deploy-skip removed the test-module artifacts from the upload (job green
   in ~100s on 36541f6; new-GA snapshots live). Verify the streak has held, record the
   root-cause finding (upload payload), close. No rerun/cross-project work unless it
   hung again.
2. **PR #70 fix ON ITS OWN BRANCH** (`dependabot/maven/com.networknt-json-schema-validator-3.0.6`):
   analyze the 1.5.9 → 3.0.6 major-bump breakage (build (25) FAILURE + integration-tests
   FAILURE verified), adapt the consuming code/config on that branch, push to it so PR #70
   goes green. Do NOT merge into the plan's own branch — #70 keeps its own review/merge
   path. If the bump is genuinely incompatible, record why and recommend close-or-defer.
3. **Quarkus pin rationale (refreshed)**: #69 auto-merged (version.quarkus now 3.37.3,
   current). Remaining: determine whether cui-java-parent (or a cuioss BOM) manages
   Quarkus — if yes remove the pin and align, if no the pin is legitimate — and record
   the rationale as a pom comment. No version change expected.
4. Fresh Sonar backlog query via the tooling (credentials landed via PR #87; on the
   known upstream credentials-key-prefix failure, record it and fall back to the public
   API) — enumerate every open finding at execution time.
5. Residual sweep to literal zero. The former 34-finding baseline is STALE (the
   168-package rename re-anchors findings) — trust only the live index at execution
   time; historical rule families for orientation: S5778, S7467, S1612, S5976, S6916,
   S1068, S6878, S1130, S1135. Fix-by-default; in-code suppress-with-rationale where a
   fix is not sensible; never silent server-side won't-fix. (Ride-along dropped —
   tree clean; enriched.json refresh belongs to the operator's architecture-enrichment
   run.)
6. Post-merge closure proof: completion report states the actual post-merge PROJECT
   gate status AND open-findings total (expected OK / 0), plus the deliverable-1
   snapshot finding and the #69/#70 dispositions.

## Expected Surface

- `api-sheriff/src/{main,test}/java/**` — asset/config neighborhoods + test smells (sonar sweep)
- Root `pom.xml` (quarkus property; possibly version bump)
- Dependabot branch `dependabot/maven/com.networknt-json-schema-validator-3.0.6` (separate PR #70)
- `.github/workflows/maven.yml` ONLY IF the snapshot diagnosis finds a repo-side cause
  (org reusable workflow is out of scope)
- 2× enriched.json hint files (ride-along)
- NO gate/Sonar-server config changes; no doc-tree changes expected (say so explicitly if true)

## Dependencies and Sequencing

- Depends on: PLAN-04 shipped (done). No plan in flight; surface disjoint from all staged plans.
- Overlaps with: none active. PR #70's branch is its own merge target — no collision
  with the plan branch (different PRs, disjoint files expected; json-schema-validator
  usage sits in config validation — if the sonar sweep touches the same validator
  classes, land the plan PR first and rebase #70).

## Hand-Off Command

```text
/plan-marshall Project cleanup for API Sheriff, four work packages in this order. (A) deploy-snapshot VERIFY/CLOSE: the former 3-consecutive-hang issue on main's Maven Build deploy-snapshot job RESOLVED when the coordinate-relocation plan added maven.deploy.skip to benchmarks/ and integration-tests/ — the job now completes in ~100s (verified green on 36541f6, new-GA snapshots live). Verify the green streak has held since, record the root-cause finding (test-module artifact upload payload caused the hangs; org escalation retired), and close. No rerun, no cross-project comparison needed unless it has hung again. (B) Fix dependabot PR #70 ON ITS OWN BRANCH dependabot/maven/com.networknt-json-schema-validator-3.0.6: FIRST rebase that branch over main — it predates the 168-package de.cuioss.sheriff.gateway rename and the 0.1.0-SNAPSHOT version restart — then analyze the 1.5.9->3.0.6 major-bump breakage (build (25) + integration tests failed pre-rename), adapt the consuming code on that branch, push so #70 goes green; do NOT fold the bump into this plan's branch; if genuinely incompatible, record why and recommend disposition. (C) Quarkus pin rationale: dependabot #69 already auto-merged (version.quarkus now 3.37.3, current) — the remaining question is only WHY the project pins its own version.quarkus despite inheriting cui-java-parent: determine whether the parent (or a cuioss BOM) manages Quarkus; if yes, remove the pin and align; if no (parent Quarkus-agnostic), the pin is legitimate — record the rationale as a pom comment. No version change expected. (D) THEN the sonar sweep: drive the SonarCloud open-findings backlog for cuioss_API-Sheriff to LITERAL ZERO from a FRESH live query (tooling first — credentials configured; on the known upstream credentials-key-prefix failure, record it and use the public API). IMPORTANT: the previous 34-finding baseline is STALE BY CONSTRUCTION — the 168-package rename re-anchors/churns findings — so enumerate from scratch and trust only the live index at execution time. Historical residual candidates for orientation only: S5778, S7467, S1612, S5976, S6916, S1068, S6878, S1130, S1135. Fix-by-default; where a fix is genuinely not sensible, suppress in-code with a rationale (// NOSONAR java:SXXXX <why> or @SuppressWarnings) per doc/development/sonar-quality-gate.adoc — never silent server-side won't-fix. Org-owned gate and Sonar server config OUT of scope. STANDING SONAR POLICY: gate green before merge (hard stop); the plan is not done until the post-merge PROJECT gate is green AND open findings are zero, and the completion report must state both actual values PLUS the deliverable-A finding and the #70 disposition. Three-layer docs convention: if a fix changes anything an operator or contributor would do differently, document it in the appropriate layer in the same PR; otherwise state explicitly that nothing operator-facing changed.
```

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when the landing analysis is recorded at landings/PLAN-13.md}
