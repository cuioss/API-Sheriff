# Landing Analysis: PLAN-V02-14 — Validate a Gateway Configuration Without Starting the Runtime

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #387 (https://github.com/cuioss/API-Sheriff/pull/387)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-06 from the inbox
> message `plan-v02-14-offline-config-validation-012.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against merge commit `1a20edad` on `origin/main` (26 files, +3,906/−278) and the PR state
read through the CI abstraction (`state: merged`, same commit). The spec carried five
deliverables; the plan's outline cut them into seven, all reported done.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — validate-and-exit mode running the same validator as the boot | shipped-as-specified | New `ConfigValidationCommand` and `config/boot/ConfigBootPipeline` (the boot's own pipeline seam, shared by both paths); `ApiSheriffApplication` and `ConfigProducer` rewired; `ValidateConfigImageIT` runs the flag against the built image. |
| D2 — settle the placeholder question | shipped-as-specified | `config/load/ConfigLoader`, `EnvSecretResolver` and the new `DefaultedPlaceholder` in the diff; per the plan, unresolved placeholders are reported rather than treated as valid. |
| D3 — packaging verdict | shipped-as-specified | ADR-0061 *Offline configuration validation is a pre-boot flag on the gateway binary over the boot's own pipeline seam*. `PLAN-V02-01` had not landed, so the Quarkus command-mode alternative could not be read; ADR-0061 records that, as the spec required. |
| D4 — usable as a CI gate, wired into this repository's CI | shipped-as-specified | New workflow `.github/workflows/config-validation.yml`; its first `push` run on `main` for `1a20edad` succeeded. Per the CodeRabbit fix, it fails when a `sheriff-config-*` directory has no validation result. Two config sets that run as one-off containers rather than compose services are assembled the way their ITs mount them. |
| D5 — documentation in `doc/user/` | shipped-as-specified | New `doc/user/offline-config-validation.adoc`; `doc/user/README.adoc`, `anchors.adoc`, `endpoint-routes.adoc`, `doc/configuration.adoc` and `doc/LogMessages.adoc` updated. |

Issue **#175 is closed** with a shipping comment.

## Metrics and Anomalies

- Tokens: 5,913,514 total.
- Duration: 16h34m wall (59,615 s).
- Anomalies: two finalize loop-back rounds (a self-review ordinal fix; a Sonar and review-comment
  round of five tasks). Two concurrent Maven builds in the same worktree produced spurious "Failed
  to start quarkus" errors again. The pre-push gate's module-test arm was degraded, so the plan ran
  the `api-sheriff` module tests by hand. All recorded as lessons.

## Routing and Merge Behavior

- Review: zero unresolved resolvable threads. Three CodeRabbit findings fixed, one declined with
  reasons (deriving the NOT CHECKED catalogue from shared refusal descriptors). Sourcery refused the
  PR for size (optional bot).
- CI/merge: merged through the merge queue as `1a20edad`.
- Post-merge, checked by the orchestrator 2026-10-06:
  - Main-branch push runs for `1a20edad`: `Maven Build` (run 37430196415) success including
    `deploy-snapshot`; `Integration Tests`, `Demo Client E2E`, `Scorecard` and the new
    `Config Validation` success.
  - **PR-attached `Run Integration Benchmarks` (run 37430198468): failure.** The
    `run-k6-upload-small-benchmark` execution crossed its `checks` and `http_req_failed` thresholds:
    134 `POST /upload/small` requests failed with the server sending HTTP/2 `GOAWAY`,
    `ENHANCE_YOUR_CALM`, debug *"Maximum number of RST frames reached"*.
  - **Not attributed to this plan.** The same `GOAWAY` appears in the three preceding benchmark
    runs, all of which passed: 105 times on #386 (run 37358754091), 95 on #385 (run 37315527076),
    104 on #382 (run 37189109618). The upload benchmark has been running close to its failure
    threshold since at least #382; this run crossed it. #387's footprint touches no edge, HTTP/2 or
    upload code. Recorded as Open Defect 16; the reset-rate bound it involves belongs to
    `PLAN-V02-06` D8.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `387`; `landing` `landings/PLAN-V02-14.md`
- [x] inbox `-012` archived (`reconciled`); eleven candidate lessons dispositioned and archived:
      six promoted, five folded as recurrences into existing lessons
- [x] Open Defect 16 added (benchmark `GOAWAY` under upload load); its evidence folded into
      `PLAN-V02-06` D8
- [x] `PLAN-V02-01` corrected in place: ADR-0061's command-mode revisit and three now-unused public
      overloads in `config/load` and `config/topology`
- [x] Watch added: the declined CodeRabbit refactor of the NOT CHECKED catalogue
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- **`PLAN-V02-01`:** if it adopts Quarkus command mode, decide whether `--validate-config` moves onto
  it (ADR-0061). `ConfigLoader.load()`, the three-argument `TopologyResolver.resolve(...)` and
  `EnvSecretResolver.resolve(String)` now have no production caller — remove them under the pre-1.0
  rules as part of its `config/load` analysis.
- **Declined refactor** — deriving the NOT CHECKED catalogue from shared refusal descriptors instead of
  a hand-kept list (ADR-0061 Risks). It would touch the TLS, JWKS, BFF and portal refusal paths, so it
  needs its own plan if wanted. Recorded as a Watch.
- **Benchmark threshold** — Open Defect 16.
