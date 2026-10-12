# Landing Analysis: PLAN-25 — Pin the Properties the Downstream Documents but Cannot Prove

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #408 (merged as b7f937d3da1ea999205db62d02add38d25946e7d)

> Landing record for one shipped plan. Lives at `landings/PLAN-25.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `plan-25-integration-test-evidence-017.md`**, the first landing in
this epic to arrive over the automatic channel since the ledger relocation (PLAN-23's was lost to the
retired-path defect). `inbox landing-check` reports `complete: true`, no missing keys. Corroborated
2026-10-12 at `fdcfaeb3`: `ci pr view 408` reports `state: merged` with `merge_commit_sha` b7f937d3, and
that commit is an ancestor of `origin/main`. 40 files in the merged diff (+7195/−144). Zero resolvable
review threads are open (the 15 rows `pr comments --unresolved-only` returns all carry an empty
`thread_id`).

## Deliverable Fidelity vs Spec

The landing reports 11 of 11 of the plan's own outline deliverables. Mapped back to the spec's rows:

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 M-40 reserved `/auth` paths win, floor applies | shipped-as-specified | `BffReservedPathPrecedenceIT` (all eight reserved paths), fixture `reserved-path-overlap/auth-overlap.yaml` |
| D2 M-42 one refresh under concurrency | shipped-as-specified | `BffRefreshSingleFlightIT` |
| D3 M-44 refresh sends `scope=` | shipped-as-specified | `BffRefreshScopeWireIT`, observed on the new recording stub IdP (`StubIdentityProviderRig`, WireMock standalone image pinned by tag and digest) — the route the 2026-10-05 re-grounding said was needed |
| D4 M-60 transient refresh failure keeps the session | shipped-as-specified | `BffRefreshTransientFailureIT`, faults injected at the stub IdP rather than through toxiproxy |
| D5 M-50 / M-52 / M-59 TLS-relaxation matrix | **shipped by amending the guard** | `BffTlsRelaxationIT`. The spec recorded M-50/M-52 as blocked by `ItProfileConfigBindingWiringTest`; the operator decided the fork, and the guard gained one named, scoped exemption for the TLS-relaxation fixture (including its JSSE trust-store argument). Every other descriptor stays under the unchanged guard |
| D6 M-51 `503` / `504` HTML error pages | shipped-modified | `HtmlErrorPageIT`; the `502 \|\| 503 \|\| 504` disjunction is narrowed to exactly `502`. Carried the plan's one production change (below). Closes the PLAN-16 gap "no test covers the 504 error page" |
| D7 M-54 absolute session TTL | shipped-as-specified | `BffSessionAbsoluteTtlIT`, on a test-started gateway rather than a new compose instance |
| D8 M-55 memory-store instance isolation | shipped-as-specified | `BffSessionInstanceIsolationIT` |
| D9 M-41 pending-login bound as a load test | **shipped, then broke `main`'s benchmark, then re-homed** | Shipped as the k6 goal `pending_login_flood.js`. It had never run against a stack, and it failed the post-merge Performance Benchmark on this PR and on the eight merged after it. PR #427 (`2657be7c`) moved it into `-Pintegration-tests` as `BffPendingLoginBoundIT` and deleted the k6 goal. See Routing |
| D10 javadoc correction | dropped before launch | obsolete at the 2026-10-05 re-grounding |
| Documentation | shipped-as-specified | `doc/quality-report/test-quality.adoc` (+151), `doc/development/integration-test-topology.adoc` (+401), `declared-limit-assertion-coverage.adoc` |

**The tests-only rule was broken once, by operator decision, and the PR says so.** `GatewayEdgeRoute` now
sends `Retry-After: 5` on the gateway-generated open-circuit `503` (`RETRY_AFTER_HEADER`, covered by
`GatewayEdgeRouteRetryAfterTest`). The value is the breaker's fixed 5 s delay; the per-route
`circuit_breaker.reset_ms` is parsed but not read by the breaker. The spec's STOP rule ("if a test fails
against shipped behaviour, file a finding") was therefore not followed for this one row — no finding was
filed, the fix was absorbed. It is the only change under `api-sheriff/src/main/**`.

Four further scope deviations are listed in the PR body, each an operator decision: benchmark-harness
source changed to keep the flood summary unpublished (since reverted by #427), the guard exemption above,
the WireMock stub image (no Maven dependency), and test-started gateways publishing fixed ports 10459 and
10462-10468 on all interfaces.

## Metrics and Anomalies

- **11,691,525 tokens and 35 h 1 m wall** (126,091 s). The sender notes the token total is about 130k high:
  one subagent's usage was added to the accumulator twice.
- **Finalize ran well past its ceiling.** `pre-submission-self-review` looped six rounds on documentation
  wording and spent all 5 loop-back iterations before the PR existed; the operator switched it off for
  this plan, raised the ceiling to 8 for this plan only, and authorised two further rounds individually.
  This is the same shared-counter mechanism PLAN-23 hit. Filed again as lessons (messages 003, 004).
- **`adr-propose` did not run before the merge** (an ordering slip in a hand-driven final round; recorded
  `skipped` one second after `branch-cleanup` completed). Two candidate decisions were never evaluated for
  an ADR: the fixed `Retry-After` on the open-circuit 503, and the host-port publication rule for
  test-started containers. Staged into PLAN-27.
- **The native integration suite was not re-run on the final HEAD** before the merge (the PR's own test
  plan says so); CI ran it.
- **Five security-audit findings were left "pending triage"** in the PR body: test ports on all
  interfaces, the TLS-relaxation exemption, the nginx proxy image pinned by tag only, the `Retry-After`
  without jitter, and the stub IdP's admin API on loopback. Staged into PLAN-27.
- **Surface: declared 8 entries, realized 40 files — 14 undeclared, 2 declared-but-untouched**
  (`integration-tests/docker-compose.yml`, `start-integration-container.sh`: the plan used test-started
  gateways instead of new compose instances). The undeclared set is the three workflow files, the
  production change with its test, the benchmark harness and its docs, and four documents. Roughly 5x by
  file count, but narrower than it looks: 26 of the 40 files sit inside the two declared directories.

## Routing and Merge Behavior

- Review: CodeRabbit ran two rounds plus one late comment; all threads resolved. `pre-submission-self-review`
  did not close (see above).
- CI/merge: merge queue. The queue run outlasted `merge_queue_wait_budget_seconds` (1800 s), so
  `branch-cleanup` took the deferred path and closed on re-entry. `cleanup_owed=false`.
- The integration-tests job's `timeout-minutes` was raised from 35 to 60 inside this PR, after the job was
  cancelled by its own limit with every finished suite green.
- **Post-merge verification — the PR-attached run FAILED.** `ci checks status --pr-number 408` reports
  `overall_status: failure`: `Run Integration Benchmarks` (Performance Benchmark, run 37846286183). k6
  aborted in the first login with `HTTP 404`: the gateway answers its reserved login path only when the
  request host equals the host of `oidc.redirect_uri`, and k6 ran inside the compose network as
  `Host: api-sheriff`. Not a gateway defect — the flood was wired against the wrong host — but it left the
  post-merge benchmark red for nine consecutive merges (#408, #415, #416, #418, #414, #417, #421, #420,
  #424) until **#427 fixed it**; `ci checks status --pr-number 427` reports `overall_status: success`
  including the benchmark (run 38073671464).
- The main-branch Maven Build for b7f937d3 is unobserved, as for every merge commit the CI abstraction
  cannot reach.

## Parallelization Consequences

PLAN-25 was emitted by operator override beside `api-sheriff-0-2-0/PLAN-V02-01`, with five predicted
overlap rows, all inside `integration-tests/**`. **The collision materialised, in the form predicted and
in one form that was not:**

- **Predicted:** `main` gained #412 (session-store work, "part 2 of 3") mid-finalize. PLAN-25 rebased onto
  it (iteration 8). No lost work is reported.
- **Not predicted — a shared budget, not a shared file:** each branch's native integration job was green
  alone, and the combination exceeded the job's 35-minute limit. Two plans adding suites in parallel
  collide on the CI job's wall-clock even when their files are disjoint. No declared surface can express
  that, so no spec's `## Expected Surface` is corrected for it; it is recorded as a Watch instead.
- #412 also changed what the pre-commit formatter infers, which is what made every gate run rewrite 282
  untouched files (fixed once on `main` by #415).

## Reconciliation Actions

- Queue row PLAN-25: `running` → `shipped`, `pr` #408, `landing` `landings/PLAN-25.md`.
- 18 inbox messages drained in the same pass; dispositions are in `logs/decision.log` and in `epic.md`
  under the 2026-10-12 decision.
- PLAN-27 staged for the downstream's two new IT requests (M-62, M-64) and this landing's follow-ups.
- PLAN-21 told that the flood test now exists (`BffPendingLoginBoundIT`); PLAN-20 given the downstream's
  two step-up observations.
