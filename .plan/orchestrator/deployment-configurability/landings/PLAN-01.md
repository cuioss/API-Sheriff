# Landing Analysis: PLAN-01 — Minimal Container Health-Check for the Distroless Main Image

epic: deployment-configurability
workstream: WS-01
pr: [#230](https://github.com/cuioss/API-Sheriff/pull/230) → `909d43f`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims against
> ground truth — a pasted claim is a lead, never a fact.

## Ground-Truth Corroboration

The plan's report was treated as a lead. Every material claim below was checked against the
repository at `843a038` before this record was written.

| Claim | Verdict | Evidence |
|---|---|---|
| Merged as PR #230 → `909d43f` on `main` | **corroborated** | `ci pr view --pr-number 230` → `state: merged`, `merge_commit_sha: 909d43fb…`; `git log` shows `909d43f` on `main`, 2026-08-29T16:00:25Z |
| Pre-boot probe in the application binary | **corroborated** | `ApiSheriffApplication.java:63-64` — `if (HealthProbe.isProbe(args)) System.exit(HealthProbe.probe());`, before `Quarkus.run`; `HealthProbe.java:56` |
| Identical exec-form `HEALTHCHECK` on both images | **corroborated** | `Dockerfile.native:56-57` and `Dockerfile.native.jfr:39-40`, both `CMD ["/app/application", "--health-probe"]`, both `--interval=10s --timeout=3s --start-period=20s --retries=3` |
| `ContainerHealthIT` asserts the signal, 3 tests | **corroborated** | file present; 3 `@Test` methods |
| Two files deleted (net surface reduction) | **corroborated** | `integration-tests/src/main/docker/health-check.sh` and `deployment/compose-sample/scripts/wait-for-ready.sh` both absent at HEAD |
| `-T1C` → `-T1` | **corroborated** | `.mvn/maven.config` contains exactly `-T1` |
| Worktree removed | **corroborated** | `git worktree list` shows only the main checkout |
| *"Repository: main up-to-date, working tree clean"* | **contradicted** | the checkout is on `chore/update-cui-java-parent-1.5.10` at `843a038` with `AGENTS.md`, `CLAUDE.md`, `pom.xml` modified. Unrelated to this plan (a parent-POM bump in flight) — the claim was simply not true of the tree when written. Recorded, not actioned. |
| Distroless base carries no executable at all (`/usr/bin`, `/usr/sbin` empty) | **unverifiable here** | requires pulling and inspecting `quay.io/quarkus/quarkus-distroless-image:2.0`; outside the orchestrator's read-only carve-out. The *conclusion* is corroborated by the shipped exec-form `HEALTHCHECK` naming `/app/application`, which is the only thing that could work if the claim holds. |

## Deliverable Fidelity vs Spec

The spec carried 5 deliverables; the plan reports 7. The extra two are a **split** of spec
deliverable 5 (documentation) into ADR + doc surfaces, not unplanned scope.

| Deliverable (spec) | Verdict | Evidence |
|---|---|---|
| 1. Decide the mechanism, shell-less, no added attack surface | **shipped-modified** | Landed as a pre-boot probe *inside the existing binary* — narrower than any spec candidate. The spec's own added-binary and base-image-change options were rejected on the attack-surface constraint the spec itself set. |
| 2. Add to `Dockerfile.native`, align `Dockerfile.native.jfr` | **shipped-as-specified** | Both carry the identical check; the JFR image's `/dev/tcp` shell probe on 8443 is gone, so the two images no longer diverge. |
| 3. Wire the gateway services' `healthcheck:` blocks | **shipped-modified** | All seven services rewired — the **corrected** surface this orchestrator applied pre-launch, not the spec's original two. The two-scheme requirement was **dissolved rather than met**: see Deviations. |
| 4. Verify it in the integration run | **shipped-modified** | `ContainerHealthIT` (3 tests) added. The host-side gate was **replaced** rather than retained-or-demoted — the spec asked for a stay/replace/fallback decision and "replace" is one of the three, so this is within the brief. |
| 5. Document it | **shipped-as-specified, plus overflow** | Eight documentation surfaces updated, ADR-0031 amended. ADR-0038 drafted but **not landed** — see Follow-Ups. |

**The two pre-launch corrections both proved load-bearing.** This orchestrator corrected the spec's
understated surface (two services → seven) and named the compose-sample counter-claim as something to
refute before launch. The landing shows the seven-service figure was used, and that the counter-claim
was not merely refuted but *upheld for a stronger reason than it gave* — the base carries no
executable at all, so the mechanism space really was empty except for `/app/application`.

## Deviations from the Staged Spec

Two spec requirements could not be met as written, and both were answered rather than silently
dropped:

- **The label requirement was unsatisfiable.** The spec (and this orchestrator's own pre-launch
  correction) required the probe to read the `de.cuioss.sheriff.management-scheme` label to handle the
  `http` / `https` split. Compose labels are **daemon-side and invisible in-container**, so no
  in-container probe can read one. A TCP-accept probe is protocol-blind, which **dissolves** the
  two-scheme problem instead of solving it — both schemes are covered by one check.
  ⚠ **This corrects a claim this orchestrator made.** The pre-launch note asserted the probe "must read
  the scheme off the label"; that was wrong for an in-container probe. The label indirection remains
  correct and untouched for the *host-side* gate, which is where it always lived.
- **`depends_on: service_healthy` could not gate the suite**, because the runner is host-side rather
  than a compose service. `compose up --wait` is the concrete equivalent, with a single-shot readiness
  assertion retained for the semantic the container check cannot provide.

## Routing and Merge Behavior

- Review: 13 automatic-review findings, quorum satisfied; unified triage over 14 findings produced
  **0 fix tasks**; Sonar round-trip 0 new-code issues. Zero unresolved threads at merge.
- CI/merge: green; merged via merge queue as `909d43f`. Sonar `new_coverage` 63.6% → 100.0%.
- Quality gate: 1886 tests, 0 failures. Gate wall-clock **1617s → 537s** from the flakiness fix plus
  container cleanup.
- ⚠ `main` was **red** on `BuildGateCoverageContractTest` before this landed and is green now — so
  this plan also repaired a pre-existing break.

## Reconciliation Actions

- [x] row `status` → `shipped` — `queue --transition PLAN-01 --status shipped`
- [x] row `pr` stamped — `#230`
- [x] row `landing` stamped — `landings/PLAN-01.md`
- [x] row `plan_marshall_plan_id` stamped — `distroless-health-check` (stamped earlier, at the `running` transition)
- [x] inbox message `distroless-health-check-001.md` archived after this record was persisted
- [x] Open Defects opened for the incomplete landing payload, the ADR-0038 preservation risk, the
      stale `benchmarks` metadata, the JFR `chmod 777`, and the CI blind spot
- [x] Watch opened for the plan-marshall self-response-filter defect and the four misrouted lessons
- [x] PLAN-01 queue annotations retired (the running-liveness note and the do-not-re-scope note are
      spent)
- [x] `resume_anchor` updated; START-HERE and Ordered Queue regenerated via `compact`

## Follow-Ups

1. **ADR-0038 — drafted, not landed, and at risk.** 218 lines plus a `doc/README.adoc` index-row
   patch. ⚠ It is preserved **only** in another session's scratchpad at
   `/private/tmp/claude-501/-Users-oliver-git-API-Sheriff/5a730476-…/scratchpad/adr-0038.adoc`.
   That is a session-scoped temp directory, not durable storage. **Decision owed by the operator**;
   recorded as an Open Defect so it cannot be lost silently.
2. **`orchestrated: false` was a reporting error, NOT a tooling defect — verified.** The plan's
   self-assessment blamed a mis-detection for routing four lessons to the store instead of the epic
   inbox. Running the seam directly on the plan's real `source_id`
   (`.plan/local/orchestrator/deployment-configurability/plans/PLAN-01-distroless-health-check.md`)
   returns `orchestrated: true`, `epic: deployment-configurability`, `detection: orchestrated`. The
   detection seam is sound; the value was hand-supplied. **No bundle fix is owed for this item.**
3. **Four lessons are in the right store but bypassed epic disposition** — `2026-08-29-16-001`
   (contract test outside the build-trigger set never runs on CI), `-002` (`-T1C` flakiness),
   `-003` (executor `tests_run` counts only the last module), `-004` (derive a commit delta with
   `git log A..B`). All are live in this repo's lessons store and none is lost. Recorded as a Watch.
4. ✅ **CLOSED 2026-08-29 — routed upstream by the operator; do not re-open here.**
   plan-marshall bundle defect (foreign store, correctly refused). The automatic-review
   self-response filter is start-anchored on `## Review responses` while `post_responses` emits other
   headings, so the tool re-ingests its own output as a fresh finding each round. Observed twice. The
   lessons agent correctly declined to launder it into API-Sheriff's store. **Belongs upstream in the
   plan-marshall bundle, not in this epic.**
5. **`benchmarks` module metadata is stale** — described as WRK-based but genuinely k6, with WRK
   wording in five fields of `benchmarks/enriched.json` including a package key that no longer exists.
   Pre-existing drift. Fix at the `enriched.json` root, since `architecture discover` regenerates
   `_project.json` from it.
6. **JFR overlay `chmod 777 /tmp/jfr-output`** (`ea651d`), same pattern in
   `integration-tests/scripts/prepare-jfr-output-dir.sh`. Pre-existing, accepted out of scope.
7. **CI blind spot** — a change to `.plan/marshal.json` is not a build-triggering path, so
   `BuildGateCoverageContractTest` never runs on CI, which is how the glob erasure reached `main`
   unseen. Needs an org-managed `cuioss-organization` workflow change. CLAUDE.md now documents the gap
   and the manual mitigation.
