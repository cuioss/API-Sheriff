# Landing Analysis: PLAN-SEC-07 — tier-2 follow-ups

epic: security-hardening-tier-1
workstream: WS-04
lane: runbook
pr: #619 — https://github.com/cuioss/API-Sheriff/pull/619

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `rb-sec-07-tier-2-follow-ups-001.md`) after checking the inbox claims against git, the merged code,
> the runbook lane's progress file and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

`deliverables_total=4`, `deliverables_done=3`. The landing message is `complete: false` on `total_tokens`, `model` and `effort`, which are all `unknown`: the runbook lane measured none of them and wrote `unknown` honestly. A separate whitespace PR, #618 (`e6fda739`, the pre-commit import-order rewrite), landed beside it on the operator's free-text direction.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — per-source connection share on the Quarkus-terminated listeners | not shipped (verify-first stop) | The spec's HYPOTHESIS was refuted, so no code was written. Quarkus 3.40.1 has no supported hook that can see and reject an accepted connection before a request. The lane read the public source; the orchestrator re-read the local 3.40.1 sources jar (`VertxHttpRecorder.java:1397-1416`). Persisted `contradicted`, `rescoped: yes` |
| D2 — cap headroom, descriptor sizing, admission WARN | shipped-as-specified | Cap `4096` (`application.properties:175`) = 2 × the default `admission_cap`. `ShippedApplicationPropertiesTest:163-184` pins `>`, `== 2×` and the literal `4096`. `ConnectionCapAudit` warns ApiSheriff-148 through the real `StartupEvent`; `ConnectionCapAuditTest` has 7 invocations, with the below-cap case as the matched control. nofile advice is 16384, or 32768 under `passthrough_sni`; the arithmetic checks out. Test-first against a no-op stub (disclosed) |
| D3 — WebSocket-rule negative controls | shipped-as-specified | `ForwardingRulesTest` controls for every non-WebSocket `Protocol` (null, HTTP, GRPC, GRAPHQL) and for a non-proxy route, plus a matched positive control. The recorded mutation of the guard gave 4 failures of 46 (progress file) |
| D4 — PLAN-SEC-06 doc leftovers | shipped-partial | GW-08, ADR-0076, both SVGs, the two management docs, the `Dockerfile.native` comment, ApiSheriff-115 and GW-09 are corrected. The phrase searches are empty. One site the searches could not match remains: `doc/user/tls-scenarios.adoc:339-341` ("over HTTPS. This is the shipped default"). Softer sites: `README.adoc:155` and `environment-variable-overrides.adoc:591-593` |

**Surface vs declaration** (`git diff d6b90737 e1b8a2cb`): 22 entries declared, 17 files realized.
- **Added beyond the declaration:** `Dockerfile.native` (a comment-only D4 site) and `ConnectionCapAudit.java` / `ConnectionCapAuditTest.java` (inside the declared `edge/admission/` directory).
- **Declared but untouched:** the D1 share, `EventType`, `SheriffMetrics` and `MainListenerConnectionCapIT` (all D1, which was stopped); `config/` (the WARN went to `edge/admission`). Every declared doc file was touched.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point this report relies on before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`). Reason: every full-ship landing gets its own leaf, and the D1 refutation had to be checked against the Quarkus and Vert.x jars.
- **Re-read by the orchestrator at e1b8a2cb:**
  - `VertxHttpRecorder.java:1397-1416` (quarkus-vertx-http 3.40.1 sources jar);
  - `ConnectionCapAudit.java:96-107`;
  - `application.properties:175`;
  - `doc/user/tls-scenarios.adoc:339-341`.
- **Verdict:**
  - layer 1: D1's premise refuted, as its verify-first clause foresaw;
  - layer 2: D4 has one missed site;
  - layer 3: minor points only, held outside tracked text.

### Layer 1 — Specified

Verdicts were persisted with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`). Claims 0–5 were checked at parent `d6b90737`, claim 6 at `e1b8a2cb`.

| Claim | Verdict | Polarity | Evidence |
|-------|---------|----------|----------|
| 0–2: one shared cap counter; cap = default `admission_cap`; no maximum and no WARN | checked | confirms | parent `d6b90737` |
| 3–5: GW-08 assertion; ApiSheriff-115 remedy; no non-WebSocket control | checked | confirms | parent `d6b90737` |
| 6 (HYPOTHESIS): a supported per-connection hook exists | checked | **refutes** | the only `connectionHandler` is Quarkus' own max-connections counter; the server objects are local; the customizer and `HttpServerStart` carry options only; Vert.x `connectionHandler` is single-slot. Persisted `contradicted`, `rescoped: yes` |

The leaf named two routes the lane did not, and neither changes the verdict:
- the Vert.x metrics SPI, which can observe each connection but cannot close one;
- a Vert.x `Transport` SPI provider, which reaches the server bootstrap but sits next to internals; whether Quarkus supports it is not established by reading.

The spec cites the guard at `ForwardingRules.java:274`; at `e1b8a2cb` it is at `:275`.

**Layer 1 verdict:** findings — claim 6 refuted (absorbed by the verify-first stop).

### Layer 2 — Implemented

- D2 and D3 trace to code and to tests that can fail. The lane's claims are borne out by reading: the `4096` literal pin, the 7 audit invocations, the real startup path, and the arithmetic. The D3 mutation run is recorded in the progress file, not visible in the diff.
- D4 is incomplete: one management "shipped default" site remains (above).
- The lane disclosed its own rule departures (shell greps, one `git add -u`, a second PR on the operator's word, two commits made before a fresh gate, skills named but not loaded).

**Layer 2 verdict:** findings — one D4 site missed.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| S7-A | `edge/admission/ConnectionCapAudit.java:102` | — | Threshold edge of the new admission WARN. Detail in the operator's local notes | minor |
| S6-A (carried) | `application.properties:175` | — | The per-source gap from the PLAN-SEC-06 analysis stays open because D1 was stopped. Detail in the operator's local notes | major (open) |

No defect found by reading in:
- the cap read (MicroProfile Config precedence matches Quarkus; absent, empty and `<= 0` all mean no WARN, as Quarkus treats them as no cap);
- the single WARN at startup;
- the non-vacuity of the tests;
- the sizing numbers.

Cosmetic: a double blank line in three import blocks; the `ConnectionCapAudit` constructor Javadoc has no summary line.

**Layer 3 verdict:** findings — one new minor point; the major S6-A stays open, held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, LogRecord documented | conforms | ApiSheriff-148 at `LogMessages.adoc:110` |
| CLAUDE.md § Testing | JUnit 5 AAA, no forbidden libraries | conforms | real `StartupEvent`, LogAsserts |
| CLAUDE.md § Testing | a key that parses must act | conforms | `max-connections` delete-the-key pin |
| CLAUDE.md § Pre-1.0 / dependencies | no `@Deprecated`, no new dependency | conforms | no pom change |
| CLAUDE.md § Javadoc | summary line and `@since` | deviates (cosmetic) | `ConnectionCapAudit` constructor |
| spec standing constraints | one PR | deviates | #618 split off on the operator's free-text direction |
| spec standing constraints | stay off `RetryingJwksLoader*` / ADR-0063 | conforms | not in the diff |

## Metrics and Anomalies

- **Tokens / model / effort:** `unknown`, because the runbook lane measured none of them. Plugin and executor 0.1.2118. The executor-skew stop at the start (0.1.2106) was cleared by regeneration.
- **Steps:** setup, implement, build-gate, pr, reviews and merge all `done`; self-review `clear` (one round).
- **Review and Sonar numbers re-read:**
  - `unresolved_review_threads`: landing 0, `review-gate` re-read 0. Agree.
  - `review_body_findings_total`: landing 6, re-read 6 (CodeRabbit inline items). Agree.
  - `review_body_findings_handled`: landing 6. Re-read from the progress file: 6 `finding:` lines, all fixed in `5fedc056`. Agree. (`review-gate` alone reports 0 handled because it does not read the progress file.)
  - `sonar_open_new_issues`: landing 0. The post-merge re-read is `undecidable` (head revision unavailable). Not re-verified.
- **Anomalies:**
  - The `main` checkout carries an uncommitted `.plan/marshal.json` `provisioned_version` stamp from the executor regeneration. The operator chose to leave it.
  - The guard log does not exist, so it records nothing.

## Routing and Merge Behavior

- **Review:** CodeRabbit reviewed `5deae7ff`, and all 6 findings were fixed in `5fedc056`. Sourcery declined on its diff budget (optional).
- **CI/merge:** merged through the merge queue as `e1b8a2cb`.
  - PR checks: 19 success, 4 skipped, 2 CodeRabbit neutral.
  - At analysis time, these were still running: the post-merge "Run Integration Benchmarks", and the `main` push runs Maven Build (incl. `deploy-snapshot`) and Integration Tests. Recorded as a Watch.
  - Config Validation, Demo Client E2E and Scorecard on `main` are green.
- **Parallelization:** ran with no in-epic sibling. Kidicap PLAN-31 shares `doc/security-threat-model.adoc` (different subsection); there was no conflict.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped — `619`
- [x] row `landing` stamped — `landings/PLAN-SEC-07.md`
- [x] row `plan_marshall_plan_id` stamped — `sec-07-tier-2-follow-ups` (the sender `rb-sec-07-tier-2-follow-ups` carries the lane name `sec-07-tier-2-follow-ups`)
- [x] claim verdicts 0–6 persisted — `corpus set-verdict` × 7
- [x] epic.md narrative reconciled; resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **S6-A (per-source connection bound) stays open.** No supported hook exists. The remaining routes, each the operator's call, are kept in the operator's local notes: a Transport-SPI wrapper, an upstream Quarkus request, routing through the SNI front, or the head deadline.
- S7-A (WARN threshold) and the `tls-scenarios.adoc` site are small and stageable on the operator's word.
- Confirm the pending `main` runs and the benchmark for `e1b8a2cb`.
