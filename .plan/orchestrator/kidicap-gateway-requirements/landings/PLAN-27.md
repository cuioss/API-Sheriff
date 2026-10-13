# Landing Analysis: PLAN-27 — Two More Downstream Properties at IT Level, and What PLAN-25 Left Open

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #433 (merged as 128fd07e892f3f1b0981a027eb4e30c820eefc8d)

> Landing record for one shipped plan. Lives at `landings/PLAN-27.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `integration-test-evidence-follow-ups-009.md`.** `inbox landing-check`
reports `complete: true`, no missing keys. Corroborated 2026-10-13 at `128fd07e`: `ci pr view 433` reports
`state: merged` with that merge commit, which is the tip of `origin/main`. 28 files in the merged diff
(+1037/−146). Zero resolvable review threads are open (the eight rows `pr comments --unresolved-only`
returns all carry an empty `thread_id`; both review threads are resolved).

⛔ **`ci checks status --pr-number 433` reports `overall_status: failure`.** 34 of 35 checks are green or
skipped; the one failure is the PR-attached post-merge run, `Run Integration Benchmarks`. See Routing and
Merge Behavior.

## Deliverable Fidelity vs Spec

The landing reports 9 of 9 of the plan's own outline deliverables. Mapped back to the spec's five rows:

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 M-62, an unbound token response is refused | shipped-as-specified | `BffUnboundTokenResponseIT` (new, 219 lines) on `StubIdentityProviderRig`: `accessTokenWithoutConfirmationIsRefused`, `accessTokenBoundToAnotherKeyIsRefused`, and a positive control `accessTokenBoundToTheProofKeyCompletesTheLogin`. The rig can now script an unbound code-exchange answer (the rig changed by 98 lines) |
| D2 M-64, the scope-driven refresh | shipped as re-scoped on the emit day | One assertion added to `BffRefreshScopeWireIT.refreshGrantsCarryTheSessionScopeOnTheWire`: the count of pushed authorization requests is unchanged across the scope-driven call. No second test. Named as the M-64 evidence in `doc/quality-report/test-quality.adoc` |
| D3 the two unrecorded decisions | shipped, (a) resolved to removal | **ADR-0064**: the breaker is the gateway's fixed policy; the whole per-route `upstream.circuit_breaker` block is removed from `UpstreamConfig`, the schema and `ConfigModelReflection`, and a descriptor still declaring it is refused at load (`ConfigLoaderTest`). No runtime change: `GatewayEdgeRoute` is not in the diff. **ADR-0065**: the test-container port topology, accepted. `doc/architecture.adoc` corrected |
| D4 five security-audit findings | shipped-as-specified | A dispositions table in `doc/development/integration-test-topology.adoc`: one fixed (the nginx digest pin), four accepted with reasons |
| D5 test hygiene | shipped-as-specified | `HtmlErrorPageIT.unreachableUpstream` tolerates an identified open-circuit `503`; `reserved-path-overlap/auth-overlap.yaml` is under the inventory guard; `DescriptorInventoryWiringTest` display names narrowed; `nginx:1.27-alpine` digest-pinned at the three services of `integration-tests/docker-compose.yml` and in `JwksLateIdpReadinessIT` |

The removal under D3(a) is the one production bend the spec allowed. It went one step further than the
spec's wording (the key `reset_ms`): the whole `circuit_breaker` block is gone. The spec's STOP rule (a
runtime behaviour change is its own plan) was honoured: the four other inert keys were filed as a finding,
not fixed.

## Metrics and Anomalies

- **5,811,909 tokens and 4 h 37 m wall** (16,621 s). Three quarters of PLAN-24's tokens.
- Every finalize step is `done`. Sonar: 0 new-code issues.
- **The native integration job took 1,182 s (about 20 minutes)** with the new suite, against a 60-minute
  limit. The workflow file was not touched.
- **CodeRabbit's hourly quota was exhausted once** (review refused), and Sourcery's weekly diff budget ran
  out on a later head (optional).
- **Each documentation-only fix commit re-ran every head-dependent finalize step.** Lesson
  `2026-10-13-06-005`.
- **Surface: declared 12 entries, realized 28 files — 10 undeclared, 5 declared-but-untouched.**
  Undeclared: `ApiSheriffLogMessages.java`, `UpstreamConfig.java`, `ConfigModelReflection.java`,
  `ConfigLoaderTest.java`, `ConfigModelContractTest.java`, `doc/LogMessages.adoc`,
  `doc/development/README.adoc`, `doc/quality-report/README.adoc`,
  `doc/quality-report/code-correctness.adoc`, `doc/user/endpoint-routes.adoc`. Untouched:
  `GatewayEdgeRoute.java`, `integration-tests/src/main/docker/`, `.github/workflows/integration-tests.yml`,
  `ItProfileConfigBindingWiringTest`, `OneOffGatewayContainers`. Eight of the ten undeclared files follow
  from one decision the spec left open (remove the key): a spec that leaves a wire-or-remove decision to
  the plan cannot declare that decision's footprint.

## Routing and Merge Behavior

- Review: two CodeRabbit inline threads, both fixed in fceb918e and resolved; two nitpicks accepted and
  recorded (see Residue). cuioss-review-bot re-reviewed on request.
- CI/merge: squash through the merge queue; `cleanup_owed=false`.
- ⛔ **Post-merge verification — the PR-attached run FAILED.** `Run Integration Benchmarks` (Performance
  Benchmark, run 38307102339) ended `BUILD FAILURE` in `run-k6-upload-small-benchmark`: k6 reports
  `thresholds on metrics 'checks, http_req_failed' have been crossed`, error rate 15.66% over 33,473
  iterations. 149 request failures read `http2: server sent GOAWAY and closed the connection …
  ErrCode=ENHANCE_YOUR_CALM, debug="Maximum number of RST frames reached"`, the first 15 seconds into the
  60-second run. The eight benchmarks before it in the same run report an error rate of 0 (one 0.000004).
  The same job was green after #427, #429, #430 and #432. The plan stopped waiting before the run ended
  and reported it as in progress.
  **Cause not established.** #433 changes no code on the dispatch path, so a regression from this PR is
  unlikely but not excluded; one failure in five runs cannot tell an intermittent fault from a new one.
  Recorded as an Open Defect and folded into PLAN-28 as deliverable 8.
- The main-branch Maven Build for 128fd07e (including `deploy-snapshot`) is unobserved, as for every
  merge commit the CI abstraction cannot reach.

## Residue

- **Four upstream keys still parse and act on nothing** — corroborated at `128fd07e`: `connectTimeoutMs`,
  `readTimeoutMs`, `maxAttempts` and `idempotentOnly` occur under `api-sheriff/src/main` only in
  `UpstreamConfig`; `GatewayEdgeRoute` applies a fixed 30 s timeout (2568) and `maxRetries(2)` with a
  100 ms delay (2570). Staged as PLAN-29.
- `deployment/compose-sample` keeps `nginx:1.27-alpine` by tag, as a documented exclusion — corroborated
  (two compose files).
- Accepted and untracked, the sender's statement: no guard test that the nginx digest pins stay equal;
  the toxiproxy, go-httpbin and prometheus images in the IT compose stack remain pinned by tag.
- `doc/variants/01-base-gateway.adoc` (line 115) still shows `read_timeout_ms: 5000` in an example with no
  note that it is inert — found by the orchestrator, not reported by the plan. Carried into PLAN-29.

## Parallelization Consequences

PLAN-27 ran alone; nothing collided. It took ADR ordinals 0064 and 0065, so PLAN-26's hint is corrected to
0066. It changed `doc/configuration.adoc`, which PLAN-28 deliverable 4 also edits; PLAN-28 is emitted
after this landing, so it starts from the changed file.

## Reconciliation Actions

- Queue row PLAN-27: `running` → `shipped`, `pr` #433, `landing` `landings/PLAN-27.md`.
- 9 inbox messages drained in the same pass: 5 lessons promoted (`2026-10-13-06-001` to `-005`), 2
  candidates folded into those as recurrences, 1 finding staged as PLAN-29, the landing reconciled.
- PLAN-28 widened by deliverable 8 (the failed benchmark) and re-grounded at `128fd07e`.
