# Landing Analysis: PLAN-24 — Close the Key-Rotation Window

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #430 (merged as c013781dba85878c6a2bab93e88118e5648b3838)

> Landing record for one shipped plan. Lives at `landings/PLAN-24.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `jwks-unknown-kid-refresh-015.md`.** `inbox landing-check` reports
`complete: true`, no missing keys. Corroborated 2026-10-12 at `c013781d`: `ci pr view 430` reports
`state: merged` with that merge commit, which is the tip of `origin/main`. 22 files in the merged diff
(+3036/−172). `ci checks status --pr-number 430` reports `overall_status: success` over 35 checks. Zero
resolvable review threads are open (the six rows `pr comments --unresolved-only` returns all carry an
empty `thread_id`).

## Deliverable Fidelity vs Spec

The landing reports 4 of 4 of the plan's own outline deliverables. Mapped back to the spec's five rows:

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 bounded, single-flight refresh on an unknown `kid` | shipped-as-specified, with one stated approximation | `RetryingJwksLoader.onUnknownKeyId` claims the issuer's window with one compare-and-set and schedules at most one refresh per window; `runRefresh` / `settleRefresh` load the fresh key set to the side. **The refreshed set is published only when it holds the triggering key id** — see Residue |
| D2 `refresh_interval_seconds` and the grace period configurable | shipped, one key more than specified | Three keys on `token_validation.issuers[].jwks`: `refresh_interval_seconds` (60..86400), `key_rotation_grace_period_seconds` (0..3600), `unknown_kid_refresh_window_seconds` (5..3600, default 30). Ranges are enforced by `ConfigValidator` (`ConfigValidatorJwksRefreshTest`); the schema states them in prose only. All three are refused on a `file` source |
| D3 rate-limited diagnostic WARN | shipped-as-specified | `ConfigLogMessages.WARN.JWKS_UNKNOWN_KEY_ID`, naming the issuer and the last-loaded instant, emitted once per claimed window. It lives in `ConfigLogMessages`, not `ApiSheriffLogMessages` as the spec expected |
| D4 integration tests | shipped-modified | `JwksKeyRotationIT` (new, 333 lines), on `StubIdentityProviderRig` rather than "the existing Keycloak stack" the spec named — the 2026-10-12 surface note had already pointed there. The rig gained signing-key rotation and per-issuer JWKS request counting (+464/−74) |
| D5 documentation and the ADR-0011 sentence | shipped-as-specified | `doc/configuration.adoc` (+126), threat model GW-07 (+69), `doc/LogMessages.adoc`, ADR-0011 amended, and a new **ADR-0063** — the ordinal the spec predicted |

No token-sheriff change and no new dependency, as the 2026-10-05 re-grounding said would be possible.

## Metrics and Anomalies

- **7,763,576 tokens and 6 h 56 m wall** (24,966 s). About two thirds of PLAN-25's tokens and a fifth of
  its wall-clock, for a plan with production code.
- Every finalize step is `done`, including `adr-propose` (skipped in PLAN-25) and
  `pre-submission-self-review` (which did not close in PLAN-25). Sonar: 0 new-code issues, confirmed.
- **About 570K tokens were spent on two hand-offs that could not succeed**: `ci-verify.md` prescribes
  `verification-feedback` hand-offs for two producers that `verification-feedback.md` rejects. Filed as
  lesson `2026-10-12-16-005`.
- **One full local integration run was lost to the build daemon's 1800 s default timeout** and left the
  Docker stack running; a later run with `--timeout 4500` completed. Lesson `2026-10-12-16-006`.
- **No single full local run of the integration suite was green end to end** (best: 273 of 274, the one
  failure in a test the branch does not touch, green on a targeted re-run). CI's Integration Tests run
  was green on both pushed heads and in the merge queue.
- **CodeRabbit's inline review covered the first head only** (10252a63). Its later pass over the three
  fix commits filed nothing, and cuioss-review-bot re-reviewed the final head and filed nothing. Sourcery
  declined on diff size (optional).
- **`GatewayEdgeFramingCorpusTest` failed twice, in two different methods, on a branch that touches
  nothing under `gateway/edge`**, and was green on re-run both times. Undiagnosed. Staged into PLAN-28.
- **Surface: declared 16 entries, realized 22 files — 8 undeclared, 4 declared-but-untouched.**
  Undeclared: `ConfigLogMessages.java`, four test classes outside `auth/` (`ConfigLoaderTest`,
  `ConfigModelContractTest`, `ConfigValidatorJwksRefreshTest`, `SheriffMetricsTest`),
  `StubIdentityProviderRig.java` (named in the spec's prose, not as a surface entry),
  `JwksLateIdpReadinessIT.java` and `doc/README.adoc`. Untouched: `IssuerKeySetStatus.java`,
  `ApiSheriffLogMessages.java`, `integration-tests/src/main/docker/sheriff-config/`,
  `OneOffGatewayContainers.java`. The closest declaration-to-footprint match this epic has measured
  (1.4x by file count; earlier landings ran 3x to 5x), and the first measured after the declarations
  were re-grounded on the day of the emit.

## Routing and Merge Behavior

- Review: one CodeRabbit inline thread (ADR-0063 section 3 dropped the in-flight condition) and one
  outside-diff item (threat model said "one JWKS request" where the bound is one refresh); both fixed in
  f77de777. One Sonar CRITICAL code smell on new code (`java:S1192`), fixed, not suppressed.
- CI/merge: squash through the merge queue; `cleanup_owed=false`.
- **Post-merge verification — the PR-attached run is green.** `Run Integration Benchmarks` (Performance
  Benchmark, run 38268812353) reports `SUCCESS`. It was still running when the plan stopped waiting.
- The main-branch Maven Build for c013781d (including `deploy-snapshot`) is unobserved, as for every
  merge commit the CI abstraction cannot reach.

## Residue

Corroborated where the orchestrator could read it; the rest is the sender's statement.

- **The publish rule is an approximation, by operator decision.** A caller presenting invented key ids
  can win an issuer's window and delay pick-up of a genuine rotation until a token signed with the new
  key claims a window, or at the latest until the background refresh. It never accepts an invalid token
  and never drops a valid key. The exact rule needs a key-id accessor token-sheriff 0.9.6 lacks. The
  upstream request has not been filed by anyone; recorded as owed by the operator and as a Watch.
- **`runRetry` lacks the guard `runRefresh` received** — corroborated at `c013781d`: `runRefresh` releases
  its flag in `finally`, `runRetry` has no `finally` and catches three exception types only. The sender
  calls it unreleased in-flight state; the orchestrator's read found no flag in `runRetry`, so what is
  lost may be the retry sequence itself. Staged as PLAN-28 deliverable 1 with that question open.
- Not corroborated, staged into PLAN-28 as leads: no log record on an unanticipated throwable in
  `runRefresh`; window and grace deadline on the wall clock; three simplify findings; documentation rows
  for the three new keys not checked; AsciiDoc list warnings on ADR-0063 and ADR-0011.

## Parallelization Consequences

PLAN-24 ran alone: the second slot was held empty because every other staged spec overlapped it. Nothing
collided, so no declared surface is corrected for a collision.

It did change two files other staged specs build on. `StubIdentityProviderRig` is PLAN-27's instrument
and grew by 390 lines net; PLAN-27's spec now says so. ADR ordinal 0063 is taken; PLAN-26's hint is
corrected to 0064.

## Reconciliation Actions

- Queue row PLAN-24: `running` → `shipped`, `pr` #430, `landing` `landings/PLAN-24.md`.
- 15 inbox messages drained in the same pass: 13 candidate lessons promoted (`2026-10-12-16-001` to
  `-013`), one staged as PLAN-28, the landing reconciled. Dispositions are in `logs/decision.log` and in
  `epic.md` under the 2026-10-12 decision.
- PLAN-27 re-grounded at `c013781d` for its emit: its M-64 deliverable is already pinned by
  `BffRefreshScopeWireIT` and shrinks to one assertion.
