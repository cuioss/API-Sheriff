# Landing Analysis: PLAN-14 — Coordinate Relocation + Release Guard

epic: api-sheriff-roadmap
workstream: WS-04
pr: #91 (c2636bd, guard) + #92 (99021b5, restructure) + #94 (36541f6, javadoc/one-shot cleanup)

> Landing record. Written 2026-07-22 by the `analyze` verb. The executing plan's own
> narrative carried an explicit trust warning ("three times I reported something as
> verified when my check didn't test what I claimed"), so EVERY claim below was
> independently verified against git, repo1.maven.org, Central snapshots, and the
> Actions API — evidence named per row.

## Deliverable Fidelity vs Spec

Plan `groupid-relocation-release-guard`, 14/14 tasks, archived to
`.plan/local/archived-plans/2026-07-22-groupid-relocation-release-guard`.
2h11m worked / 7h41m wall / 1.8M tokens.

| Deliverable (spec) | Verdict | Evidence (independently checked) |
|---|---|---|
| PR1 release.yml dispatch-only + incident comment | shipped-as-specified | origin/main release.yml: workflow_dispatch only, comment names the 2026-07-12 incident and forbids reintroducing event triggers |
| PR1/PR2 doc/development release-process | shipped-as-specified | doc/development/release-process.adoc on main (references old GA in the intended historical context) |
| GA rename → de.cuioss.sheriff.gateway, artifactIds unchanged | shipped-as-specified | root pom groupId + 0.1.0-SNAPSHOT verified on origin/main; 168 packages renamed per narrative |
| project.yml 0.1.0 / 0.2.0-SNAPSHOT | shipped-as-specified | verified on origin/main (safe: merged after the guard) |
| deploy-skip benchmarks + integration-tests | shipped-as-specified, WITH BONUS | both poms carry deploy.skip; **bonus: the 3-hang deploy-snapshot issue RESOLVED** — job green in 1m40s on 36541f6, new-GA snapshots present, benchmarks 404 under new GA. Evidence pattern (3 consecutive ~30-min hangs before, instant success after the skip landed) points to the test-module artifact upload as root cause — repo-side, not Sonatype. Org escalation retired |
| README badge → new GA + abandonment note | shipped-as-specified | badge/link verified; README carries the honest abandonment/relocation note |
| Grep closing-proof ("only the historical note") | **shipped-modified — proof as literally specified does NOT pass, and correctly so** | 15 files still match `de.cuioss.sheriff.api` on main. Classified: (a) DELIBERATE incident/abandonment docs (release.yml comment, README, release-process.adoc, doc/plan/10 historical); (b) DELIBERATE gRPC wire contract — echo.proto keeps `package de.cuioss.sheriff.api.integration.grpc` with an in-file rationale (wire FQN ≠ Maven coordinate), and the k6 script, test constants, grpc.yaml fixtures, apisix.yaml, ADR-0007 example all follow that FQN consistently; (c) STALE: 3× .plan/project-architecture/*/enriched.json — generated inventory, refreshed by the already-pending architecture-enrichment operator action. No unintentional source/doc reference remains |
| Relocation POMs 1.0.1 at old GA (parent + api-sheriff only) | shipped-as-specified | repo1 metadata both GAs: latest=1.0.1; both 1.0.1 POMs carry `<relocation>` → de.cuioss.sheriff.gateway, version-less BY DOCUMENTED DESIGN (no gateway release exists yet to point at — in-POM comment explains); benchmarks/integration-tests dead-ended as intended |
| skip-bot-review on both PRs | claimed, not re-verified (merged PRs; low stakes) | narrative |

## Metrics and Anomalies

- Three known-good-by-luck escapes flagged by the plan itself; this analysis re-verified
  every material claim independently — one claim (grep proof) was indeed not as specified
  but resolves to deliberate-and-documented exceptions.
- **Stray permanent artifact**: `de.cuioss.sheriff.api:api-sheriff-relocations:1.0.1`
  on Central (verified 200) — aggregator POM published due to a wrong skip property
  (`central-publishing.skipPublishing` vs `skipPublishing`). Immutable, cosmetic,
  under abandoned coordinates. Accepted; recorded here so nobody re-discovers it.
- `release/relocation-stubs` branch retained on origin BY DESIGN (holds the stub
  project) — MUST NEVER be merged. Watch opened.
- Cleanup traps recorded (session knowledge): `git merge-base --is-ancestor` reports
  squash-merged branches as unmerged (use `gh pr list --state merged` instead); the
  merge queue auto-deletes remote branches (use `fetch --prune` before delete calls).

## Routing and Merge Behavior

- Three PRs in the specified order (guard → restructure → cleanup #94, an in-flight
  addition: javadoc release-25 pin + removal of the spent one-shot workflow). #93
  presumably the skipped/closed number. Merge queue used; branches cleaned.
- Events overtaken en route: dependabot #69 (quarkus 3.37.3) auto-merged (d506728);
  steward #90 landed plan-marshall 0.1.1182.
- **Two NEW upstream plan-marshall defects found, with reproductions**
  (.plan/temp/plan-marshall-daemon-false-green.md): (1) marshalld daemon inverts build
  verdicts — `_daemon_result_to_direct()` derives job_status from the child executor
  exit code, which is always 0 (failures are reported in-band) → status:success for
  failed builds; (2) `ci pr merge` reports merged:true for a PR it CLOSED UNMERGED and
  deletes the branch (merge-queue repos reject `gh pr merge --delete-branch`) —
  destructive; commit 0e74da6 survived only via a local checkout. Shared shape: a
  wrapper deriving success from something other than the actual outcome, then emitting
  a well-formed success envelope. **Operator hint: both already fixed in the new
  plan-marshall version** (0.1.1182 landed via #90) — disposition: VERIFY-ON-VERSION
  rather than file; keep the bug report until verified.

## Reconciliation Actions

- [x] status.json plans[] updated (PLAN-14 shipped, pr #91+#92+#94, landing recorded)
- [x] epic.md queue row reconciled
- [x] Release-incident defect RESOLVED (guard live, relocation live, versions restarted;
      residuals recorded: stray relocations artifact, enriched.json staleness)
- [x] Deploy-snapshot defect RESOLVED (root cause: test-module upload payload;
      org escalation retired; PLAN-13(A) reduced to verify/close)
- [x] Watch opened: release/relocation-stubs must never be merged
- [x] Upstream filings list updated: daemon false-green + ci-pr-merge false-merged →
      verify fixed in 0.1.1182 (operator hint); build-wrapper green-while-errored
      likely same family — verify together
- [x] PLAN-13 spec refreshed + RE-EMITTED (A→verify/close, C→#69 already merged,
      D→baseline stale after 168-package rename, ride-along dropped — tree clean)
- [x] resume_anchor updated; START-HERE regenerated

## Follow-Ups

- PLAN-13 (refreshed) is the next launch — no plan in flight, no surface conflicts.
- Operator: architecture enrichment now fixes BOTH open items (empty
  documentation.implementation AND the stale old-GA enriched.json refs).
- Verify the two daemon defects are gone under 0.1.1182 (at next build/plan contact);
  keep .plan/temp/plan-marshall-daemon-false-green.md until confirmed.
