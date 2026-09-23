# Landing Analysis: PLAN-24 — Operator documentation true at 0.2.1: TLS scenario guide and release-truth review

epic: deployment-configurability
workstream: WS-06
pr: [#305](https://github.com/cuioss/API-Sheriff/pull/305) — merged through the merge queue as squash `fb65222`, 2026-09-15

> Reconciled 2026-09-15 from the operator's landing paste **and** the plan's inbox landing message
> `release-docs-and-tls-scenario-guide-013.md` (`inbox landing-check`: `complete: true`, no missing
> keys). Both are leads; every claim below names what corroborated it. First landing of the
> 2026-09-15 regroup round (PLAN-18 / PLAN-24 / PLAN-25 launched together).

## Deliverable Fidelity vs Spec

The spec carried 11 deliverables; the solution outline re-cut them into 10 without dropping any —
spec D1-5 became outline D1-2, spec D6 became D3, spec D10 split into D7 (CLAUDE.md/AGENTS.md) and
D8 (architecture metadata), spec D11 split into D9 (JFR) and D10 (`.env`). Mapping read from the
archived `solution_outline.md` § Deliverables.

| Deliverable (spec) | Verdict | Evidence checked at `fb65222` |
|---|---|---|
| 1-4. Enumerate scenarios, three-part shape, true config, failure modes | ✅ shipped | `doc/user/tls-scenarios.adoc` (+745): nine scenario sections from `== Terminate TLS at the gateway` (`:87`) to `== Relax outbound hostname verification` (`:691`), plus *Choosing a scenario*, *The material each scenario touches*, *Reading a failure mode*. All nine catalogue rows present, including the three that did not exist when PLAN-09 was staged (TLS terminated in front, corporate CA, relaxed verification) |
| 5. Wire into the documentation graph | ✅ shipped | `doc/user/README.adoc` +11, `tls-edge.adoc` +8, `environment-variable-overrides.adoc` +7 |
| 6. Key-material diagram | ✅ shipped | `doc/resources/diagrams/tls-key-material.svg` (+134). Two-theme render check claimed by the paste — not re-rendered here |
| 7. Known Limitations audit | ✅ shipped-modified | `README.adoc` +84/−; per paste every entry now names its implementing symbol; added the conditioned cookie-mode refresh entry and `tls.passthrough_sni` (operator chose to add only that limitation) |
| 8. Honest badges | ✅ shipped — **remove** chosen | Static `img.shields.io/badge/cosign…` and `…/trivy…` gone from `README.adoc`; rejected alternatives (a) re-point at `release.yml`, (b) endpoint JSON recorded in a README comment at `:46-52`; `doc/development/release-process.adoc` +23 names the real steps |
| 9. Sweep for unsupported claims | ✅ shipped, **scope widened** | Version stamps: `doc/fapi_status.adoc:1` now *"(0.2.1 Alpha)"*; `features-analysis.adoc`, `fapi_next_steps.adoc`, `doc/variants/*` re-stamped. Six read-declared docs corrected in place by refine decision (`architecture.adoc`, `fapi_next_steps.adoc`, `bff-session.adoc`, `context-path.adoc`, `downstream-parent.adoc`, `endpoint-routes.adoc`). Findings in PLAN-25-owned files reported, not edited — as the spec required |
| 10. Agent/module metadata | ✅ shipped-modified | `CLAUDE.md:14` now *"k6 HTTP load testing benchmarks"* and lists all six reactor modules; `.plan/project-architecture/**` corrected through `architecture enrich`. ⚠ **Departs from the spec's literal wording**: the spec (from lessons intake C11) mandated `-Dsurefire.failIfNoSpecifiedTests=false` on the targeted-test example; CodeRabbit d44822 showed that under `-pl api-sheriff -am` the flag only hides a misspelled selector, and the operator dropped it. `CLAUDE.md:41` and `AGENTS.md:52` now document it as conditional. **The spec was wrong, not the plan** — orchestrator authoring error, see Follow-Ups |
| 11. Shipped-artifact defects | ✅ shipped | `Dockerfile.native.jfr:32` now `chown 1001:root … && chmod 0750`; `integration-tests/scripts/prepare-jfr-output-dir.sh` +13 aligned (undeclared). `.env:1-6` now points at `start-sample.sh`'s step 2 and the management labels (ADR-0031); `wait-for-ready.sh` no longer named |

### Surface fidelity — expansion detected

`inbox landing-check` with the declared (17) and realized (34) path sets: **`state: expansion_detected`,
18 added, 1 missing** (symmetric difference 19, base `origin/main` = `fb65222`, not stale).

- Added: seven `.plan/project-architecture/**/enriched.json` + `_project.json` (the enrich route the spec
  itself prescribed but declared only one file of); the six widened docs above; four `doc/variants/*`
  stamps; `integration-tests/scripts/prepare-jfr-output-dir.sh`.
- Missing: `benchmarks/README.adoc` — declared HYPOTHESIS "only where genuinely stale"; its WRK mention
  is historical and was correctly left.
- ✅ **No collision with the running round.** None of the 18 added paths is declared by PLAN-18 or PLAN-25.
  ⚠ `.plan/project-architecture/**` and `CLAUDE.md` changed under both running plans; their own
  finalize `architecture-refresh` will rebase over it (Watch).

## Metrics and Anomalies

From the archived `metrics.md`:

- Tokens: **5,805,844** total (spans populations). 6-finalize 2,114,082 and 5-execute 1,683,473 dominate.
- Duration: **8h34m wall**, 3h25m worked, 5h9m idle — idle is 60% of wall, concentrated in execute
  (2h23m) and finalize (2h25m) build and CI waits.
- Anomalies:
  - Harness killed backgrounded build waits under low memory (the paste says five, candidate lesson
    `-010` says four); each build finished on marshalld and was re-attached.
  - First `-Pjfr` run went red on a stale local `api-sheriff:distroless` base image (candidate lesson `-007`).
  - Phase 3-outline self-transitioned to 4-plan before its q-gate findings were consumed; the plan
    re-opened 3-outline manually (candidate lesson `-008`).
  - Architecture-refresh's own commit staled the push freshness gate, forcing a quality-gate re-run
    (candidate lesson `-009`).
  - Module-tests arm of the pre-push gate DEGRADED — no whole-tree module-tests canonical in this
    project; full verify, coverage, integration tests (145) and jfr ran green instead.

## Routing and Merge Behavior

- Review: CodeRabbit, PR-Agent and Sourcery all reviewed the final commit (paste). Two CodeRabbit
  findings changed content in-run — 4ca8d6 (management plain-HTTP fallback stated unconditionally,
  fixed from `ResolvedServerTlsMaterial.resolvesToPlainHttp`) and d44822 (the surefire flag). 6da2fb
  (module-list contract test) put on Hold as lesson `2026-09-15-17-001`.
- Sonar: 0 new-code issues (`step.sonar-roundtrip.new_code_issue_count=0`).
- CI/merge: ✅ **PR checks green, read through the CI abstraction** — `ci checks status --pr-number 305`
  → `overall_status: success`, 32 checks, including `build / build (25)` and `(26)`, `sonar-build`,
  `integration-tests / test`, and ✅ **the post-merge `Run Integration Benchmarks` job: SUCCESS**
  (1237 s) — the paste reported it still in progress. Merge path `merge_queue`.
- ⚠ **The `main` Maven Build run for `fb65222` is NOT verified.** The CI abstraction offers no
  commit-addressed read (`checks status` takes only `--pr-number` / `--head`) — the gap already routed
  upstream as truthful-signals `api-sheriff-deployment-configurability-013`. Watch opened.
- Deviations logged by the plan: re-opened outline for q-gate findings; quality-gate re-run after the
  architecture commit; second pre-push gate reused a green build on the same commit; fix-round simplify
  pass reviewed inline instead of dispatched.
- Finalize steps (last-colon split): all `done` through `finalize-step-print-phase-breakdown`;
  `emit-landing:pending` and `archive-plan:pending` at emit time — both evidently ran (message present,
  plan archived at `.plan/local/archived-plans/2026-09-15-release-docs-and-tls-scenario-guide/`).
  `cleanup_owed=false`; worktree and branch confirmed gone (`git worktree list`, `git branch -a`).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-24 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-24 --field pr --value 305`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-24 --field landing --value landings/PLAN-24.md`
- [x] row `plan_marshall_plan_id` stamped — `--value release-docs-and-tls-scenario-guide`
- [x] epic.md Open Defects owned by PLAN-24 marked resolved (JFR `chmod 777`, `.env` script name,
      `benchmarks` WRK metadata, CLAUDE.md/AGENTS.md `-am`)
- [x] Open Defects added for the out-of-scope findings reported in the PR (see Follow-Ups)
- [x] Watches added: `main` Maven Build for `fb65222`; PLAN-25 landing → scenario-guide fold; running
      plans rebase over the architecture metadata
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated (`orchestrator compact`)

## Follow-Ups

- **PLAN-25 is running, so nothing folds into its spec** (running-row exclusion). Two reported findings sit
  on its subject and are recorded as Open Defects to check at its landing: `doc/user/bff-cookie.adoc`
  still lists logout as a back-channel trust leg (`:256`) and carries a transitional note; the compose
  sample `deployment/compose-sample/docker/sheriff-config/gateway.yaml` trusted-proxy comment misdescribes
  `ForwardPolicyStage.applyRegeneratedForwarding`.
- **Now a follow-up, not a coupling**: PLAN-24 landed before PLAN-25, so any BFF-client-leg knob PLAN-25
  ships must be added to `doc/user/tls-scenarios.adoc` afterwards — Watch.
- Small unowned doc residue, recorded as Open Defects: `doc/technical_aspects.adoc` token-sheriff version
  below 0.9.5; `release.yml` "Both signed digests" comment over-broad; `BuildGateCoverageContractTest`
  failure message cites CLAUDE.md line numbers now 3 off; legacy dotted package keys and a stale
  `ConfigLoader.coerce()` note in architecture metadata that `architecture enrich` cannot remove;
  `configuration.adoc`, `LogMessages.adoc` and `security-threat-model.adoc` swept by targeted search only.
- **Orchestrator authoring lesson** (candidate `-006`): the surefire flag was mandated by this spec from
  lessons intake C11 without checking it against the example's reactor. A spec-mandated command flag is a
  claim and gets the verify-first treatment like any other.
- Twelve candidate lessons drained — dispositions recorded in `epic.md` and the decision log.
