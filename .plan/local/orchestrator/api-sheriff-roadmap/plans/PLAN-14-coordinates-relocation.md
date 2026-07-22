# PLAN-14: Coordinate Relocation — de.cuioss.sheriff.gateway + release guard

epic: api-sheriff-roadmap
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Spawned by the 2026-07-22 release-incident analysis (see epic Open Defects): an
> accidental, half-completed 1.0.0 release of all four modules sits immutably on Maven
> Central (published 2026-07-12 by a project.yml-touching PR firing release.yml).
> Operator decisions: do NOT follow 1.0.0; new groupId `de.cuioss.sheriff.gateway`
> (family-symmetric with de.cuioss.sheriff.token@0.9.2, artifactIds unchanged); restart
> at 0.1.0-SNAPSHOT/0.1.0; relocate old coordinates; and ABOVE ALL ensure no accidental
> release can ever fire again.
> Scope note: ~8 deliverables across two strictly-ordered PRs + one off-cycle publish —
> over the split guard, proceeding as one plan with recorded rationale: the restructure
> is an atomic identity change that cannot ship partially, and the internal PR split IS
> the safety structure.

## Objective

Retire the accidental `de.cuioss.sheriff.api:*:1.0.0` identity: first disarm the release
trigger so no merged PR can ever fire a release again (PR 1, minimal and urgent), then
move the project to `de.cuioss.sheriff.gateway` at `0.1.0-SNAPSHOT` with test modules
excluded from deployment (PR 2), and finally publish relocation POMs at the old
coordinates pointing consumers to the new home (off-cycle one-off).

## Deliverables

**Cross-cutting (operator 2026-07-22): BOTH PRs get the `skip-bot-review` label at
creation** (ensure the label exists via the CI label verb first) — mechanical
rename/guard changes must not burn bot-review quota. Gemini/CodeRabbit/Sourcery are
skipped by design here; the quality gate + CI + Sonar still apply in full.

**PR 1 — release guard (MUST merge before anything below; lands immediately):**

1. `release.yml`: remove the `pull_request`/paths trigger entirely — `workflow_dispatch`
   ONLY. No merged PR may ever fire a release again; releasing becomes a deliberate
   manual act. (The reusable org workflow call stays; only the repo-side trigger
   changes. If the cuioss org convention expects the project.yml-edit flow, this repo
   deviates deliberately — record that in the workflow file as a comment referencing the
   2026-07-12 incident.)
2. `doc/development/`: document the release process as dispatch-only, including WHY
   (the incident: any project.yml edit — even a CI-matrix change — used to trigger a
   release of `release.current-version`). Three-layer convention: this is the
   contributor-facing layer; nothing operator-facing changes in PR 1.

**PR 2 — coordinate restructure (only after PR 1 is merged; ordering is a hard gate):**

3. GroupId `de.cuioss.sheriff.api` → `de.cuioss.sheriff.gateway` in the root pom and
   every module pom's parent reference. ArtifactIds unchanged (api-sheriff-parent,
   api-sheriff, benchmarks, integration-tests).
4. Version `1.0.0-SNAPSHOT` → `0.1.0-SNAPSHOT` (root + any hardcoded references).
   `.github/project.yml`: `release.current-version: 0.1.0`,
   `next-version: 0.2.0-SNAPSHOT`. Safe to touch ONLY because PR 1 disarmed the trigger.
5. Deployment exclusion for test modules: `maven.deploy.skip=true` (or equivalent) in
   `benchmarks/` and `integration-tests/` so they never publish again — neither
   snapshots nor releases (both shipped to Central in the incident and ship today on
   every snapshot deploy).
6. **Documentation completeness (operator-emphasized — double-checked, not sampled)**:
   README badge + link → `de.cuioss.sheriff.gateway/api-sheriff`; exhaustive sweep of
   EVERY tree — README.adoc, CLAUDE.md, doc/** (configuration.adoc, architecture.adoc,
   doc/user/, doc/development/, doc/plan/, doc/adr/, threat model), Docker/compose
   files, scripts — for old-GA (`de.cuioss.sheriff.api`) and old-version
   (`1.0.0-SNAPSHOT`/`1.0.0`) references. Add a short honest note in doc/development
   explaining why `de.cuioss.sheriff.api:1.0.0` exists on Central (accidental
   2026-07-12 release, superseded by relocation). CLOSING PROOF: a repo-wide grep for
   `de.cuioss.sheriff.api` returns ONLY the deliberate historical note (and relocation
   tooling, if any is in-repo) — assert this explicitly in the completion report.
7. Whole-tree green incl. native ITs (artifactIds unchanged, so jar/image names should
   not move — assert that; groupId appears in no runtime-critical path, verify quarkus
   application metadata is unaffected).

**Off-cycle — relocation publish (after PR 2 is on main):**

8. Publish relocation-only POMs at the OLD coordinates as version **1.0.1** for
   `api-sheriff-parent` and `api-sheriff` ONLY (benchmarks/integration-tests dead-end
   deliberately): packaging `pom`, `<distributionManagement><relocation>` →
   `de.cuioss.sheriff.gateway` with a message naming the new coordinates and the reason;
   must meet Central publishing requirements (name/description/url/license/scm,
   signed). Publish mechanism is the plan's to choose (throwaway module + dispatch, or
   one-shot manual signed deploy with org credentials) — NOT the normal release
   workflow. Verify afterwards: old-GA maven-metadata shows latest=1.0.1 and the 1.0.1
   POM carries the relocation element.

## Expected Surface

- `.github/workflows/release.yml` (PR 1)
- `doc/development/**` (PR 1 + PR 2)
- Root `pom.xml` + all module poms (GAV, versions, deploy-skip) (PR 2)
- `.github/project.yml` (PR 2, post-guard only)
- `README.adoc` badge + doc/** sweep (PR 2)
- Old-GA relocation POMs on Central (off-cycle; no repo files beyond a possible
  throwaway publish module)

## Dependencies and Sequencing

- Depends on: operator groupId decision (DONE: de.cuioss.sheriff.gateway).
- Overlaps with: **PLAN-13 on the root pom** (its quarkus-pin deliverable C edits
  pom.xml) — do NOT run the two concurrently; whichever launches second rebases. PR 1
  (release.yml only) is disjoint from everything and may land at any time.
- Internal hard ordering: PR 1 → PR 2 → off-cycle publish. PR 2 touching project.yml
  before PR 1 merges would fire a release — this is the exact failure mode the plan
  exists to close.
- Before PLAN-08: the release cut then targets de.cuioss.sheriff.gateway at 0.1.0 via
  manual dispatch.

## Hand-Off Command

```text
/plan-marshall Surgical coordinate relocation + release guard for API Sheriff, three strictly-ordered stages. CONTEXT: on 2026-07-12 a merged PR that touched .github/project.yml only for the CI java-versions matrix accidentally fired release.yml (which triggers on ANY merged PR touching project.yml), releasing the template-inherited current-version 1.0.0 of ALL FOUR modules to Maven Central (immutable), then failing at the repo push — so no tag/bump exists and the pom is still 1.0.0-SNAPSHOT. Operator decisions: abandon 1.0.0; new groupId de.cuioss.sheriff.gateway (artifactIds unchanged); restart at 0.1.0-SNAPSHOT; relocate; and guarantee no accidental release can ever happen again. CROSS-CUTTING: apply the skip-bot-review label to BOTH PRs at creation (ensure the label exists first via the CI label verb) — mechanical rename/guard changes must not burn bot-review quota; quality gate + CI + Sonar still apply in full. STAGE 1 (own PR, merge FIRST — this is the safety gate for everything else): change release.yml to workflow_dispatch ONLY — delete the pull_request/paths trigger; add a comment in the workflow referencing the 2026-07-12 incident; document the dispatch-only release process and its rationale in doc/development. STAGE 2 (second PR, ONLY after stage 1 is merged — touching project.yml before that fires a release): rename groupId de.cuioss.sheriff.api -> de.cuioss.sheriff.gateway in root + all module poms (artifactIds stay); version 1.0.0-SNAPSHOT -> 0.1.0-SNAPSHOT; project.yml current-version: 0.1.0, next-version: 0.2.0-SNAPSHOT; add maven.deploy.skip=true to benchmarks/ and integration-tests/ so test modules never publish again (they shipped in the incident and ship on every snapshot deploy today); DOCUMENTATION COMPLETENESS (operator-emphasized, double-check not sample): update the README Maven-Central badge + link to the new coordinates and exhaustively sweep EVERY tree — README.adoc, CLAUDE.md, doc/** (configuration.adoc, architecture.adoc, doc/user/, doc/development/, doc/plan/, doc/adr/, threat model), Docker/compose files, scripts — for old-GA and old-version references; add a short doc/development note explaining why de.cuioss.sheriff.api:1.0.0 exists on Central (accidental, superseded via relocation); CLOSING PROOF: a repo-wide grep for de.cuioss.sheriff.api must return ONLY that deliberate historical note (plus in-repo relocation tooling if any) — assert this explicitly in the completion report; whole tree green incl. native ITs — artifactIds are unchanged so jar/image names must not move, assert that. STAGE 3 (off-cycle, after stage 2 is on main): publish relocation-only POMs at the OLD coordinates as version 1.0.1 for api-sheriff-parent and api-sheriff ONLY (benchmarks/integration-tests dead-end deliberately): packaging pom with <distributionManagement><relocation> pointing to de.cuioss.sheriff.gateway plus an explanatory message, meeting all Central requirements (license/scm/description, GPG-signed), published via a one-shot deliberate mechanism of your choice (throwaway module + manual dispatch, or direct signed deploy with the org credentials) — NEVER via an automatic trigger; then VERIFY on repo1.maven.org that the old-GA metadata shows latest=1.0.1 and the 1.0.1 pom carries the relocation element. STANDING SONAR POLICY applies to both PRs (gate green before merge; zero new findings). NOTE: the deploy-snapshot job on main is failing environmentally (known org-level issue, owned elsewhere) — a red deploy-snapshot is not this plan's failure; however, once stage 2 merges, the next SUCCESSFUL snapshot deploy must publish ONLY api-sheriff-parent and api-sheriff under de.cuioss.sheriff.gateway at 0.1.0-SNAPSHOT — verify when possible. SEQUENCING vs other work: do not run concurrently with the project-cleanup plan (both touch the root pom); if it merged first, rebase over it.
```

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PRs open; two expected}
- landing: {set when the landing analysis is recorded at landings/PLAN-14.md}
