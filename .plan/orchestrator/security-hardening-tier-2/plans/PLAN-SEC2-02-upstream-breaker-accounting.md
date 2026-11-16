# PLAN-SEC2-02: Upstream breaker accounting

epic: security-hardening-tier-2
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. Lives at
> `plans/PLAN-SEC2-02-upstream-breaker-accounting.md` and is queued as one row file,
> `queue/PLAN-SEC2-02.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).

## Objective

Make each upstream's circuit breaker count what the upstream did and nothing else. Since #620 the
bounded connection wait ends as a `GatewayException`, which the breaker records as a success, so an
upstream that accepts connections but never answers may never open its breaker; and outcomes the
client or local saturation caused are recorded as breaker successes too. This plan restores
upstream-failure counting for a wait that ran its full bound, makes client-ended and queue-full
outcomes breaker-neutral, lets a departed client release its connection wait, and settles how the
connection wait is attributed against the request-body deadline.

Disclosure is mechanism-level. Background: `.plan/local/security_notes_verified.adoc` entries N1,
R8(d), M3 part b, Info "R2 / L3-U residual", and `.plan/local/security_notes.adoc` 2026-11-14 E1–E3.
**Read them first.** Local only; never quote them into tracked text.

## Deliverables

1. **A connection wait that runs its full bound counts against the upstream.** `poolSaturationOf`
   maps both a full wait queue (`ConnectionPoolTooBusyException`) and the expired 10 s wait
   (`TimeoutException`) to `UPSTREAM_POOL_SATURATED`, a breaker-skipped `GatewayException`. Keep the
   full-queue refusal neutral (local saturation) but count an expired wait as the upstream's failure
   (it held its pooled connections without answering), or record the two outside the guard as D2
   decides — state the rule in ADR-0060's amendment.
   - Done when: a test with a pool of one to an upstream that accepts and never answers, driving
     more than the breaker's request-volume threshold, sees the breaker open — failing at
     `6ee137df` (`order: test-first`); a merely slow upstream that answers inside the wait bound
     does not open it (matched control).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core
   - Persona: security-expert
2. **Breaker neutrality for client-ended and queue-full outcomes.** Every client-ended dispatch
   (departure, request-body idle/deadline, gRPC first-message / message / pre-response client
   ends) and the full-queue refusal is recorded by SmallRye as a success (`skipOn`). Record them
   outside the guarded call so they are neither success nor failure (a pre-guard short-circuit, or
   an equivalent the implementation finds), keeping their HTTP answers and events unchanged.
   - Verify first: confirm SmallRye Fault Tolerance (version from the pom) offers no neutral
     outcome and that a pre-guard short-circuit can know the outcome without re-ordering the guard
     (timeout nested in retry). If it cannot be done without changing the guard's timeout/retry
     semantics, STOP this deliverable, record why in the landing message, and keep D1, D3–D5.
   - Done when: a test where client-ended dispatches interleave with upstream failures sees the
     breaker open at the same failure count as without them — failing at `6ee137df`
     (`order: test-first`); the existing breaker tests stay green.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core
   - Persona: security-expert
3. **A departed client releases its connection wait.** `DispatchStage.clientDeparted` only marks the
   attempt abandoned; a dispatch still queued for a connection keeps its virtual thread and wait-queue
   slot until the wait ends (≤10 s). Let the departure complete the wait (e.g. a `departed` future the
   response wait races) and remove the Vert.x waiter unsent.
   - Done when: extend `InboundBodyDepartureTest` (pool of one, saturated): after a departure the
     dispatch ends at once as `ClientDeparted`, its wait-queue slot is free for the next request, and
     the breaker records nothing — failing at `6ee137df` (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
4. **Connection wait vs. request-body deadline; dead branches.** `bodyDeadlineOf` counts from the
   attempt start, so up to the 10 s connection wait is charged to the client's body deadline; and a
   wait at the queue/dial boundary (E3) is reported as local saturation. Decide and document the
   attribution (ADR-0060 amendment). `UpstreamConnectionTooLate` can no longer be reached (wait ≤ 10 s
   against a 29 s cap): remove it and its branch (pre-1.0), or show by test that it is reachable.
   Correct the stale `UpstreamGuards` Javadoc (≈110-116, "a connection wait lasting the whole 30 s").
   - Done when: the chosen attribution is pinned by a test (a slow connect inside the bound followed
     by a steady body is not answered 408 if the decision is "not the client's", or the ADR states
     why it is); no unreachable branch remains (`order: test-first` where code changes).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:javadoc
   - Persona: implementer
5. **Documentation.** ADR-0060 amendment for D1/D2/D4; GW-07 in `doc/security-threat-model.adoc`
   (edge passages only); `doc/user/edge-security.adoc` / `doc/architecture/` upstream-resilience
   passages; `doc/LogMessages.adoc` for any new record; event/metric docs if names change.
   - Done when: every behaviour change of D1–D4 is described where the old behaviour was
     (`order: none`).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims; remove dead code.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; new records are `LogRecord`s
  in `doc/LogMessages.adoc` with the next free identifier.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every code fix starts with a test that FAILS
  against the unfixed code; a test that cannot be made to fail refutes the premise — record and drop.
- Fixed constants, no new `gateway.yaml` key unless unavoidable (then a test that goes red when the
  key is deleted).
- Production code only under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/`
  (plus event/metric registries if a new outcome needs one). Do not touch `bff/**`,
  `edge/admission/Source*`, `detection/**`, `auth/RetryingJwksLoader*`, ADR-0063.
- `DispatchStage` has a race history: interleaving tests must control the ordering, not sleep.
- Documentation states the mechanism only.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry
appended to the git-ignored `.plan/local/security_notes.adoc` (plan id, location, what, why not
fixed) — never into the PR, commits, docs or the inbox message.

## Claim Labels

- OBSERVED: the 10 s connection wait is set as the `RequestOptions` connect timeout — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:326,791-798` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: DispatchStage.java:798 setConnectTimeout(UPSTREAM_CONNECTION_WAIT_MILLIS) at 31595bca
- OBSERVED: `poolSaturationOf` maps `ConnectionPoolTooBusyException` and any `TimeoutException` before hand-over to `UPSTREAM_POOL_SATURATED`, a `GatewayException` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:779-786,875-884` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: DispatchStage.poolSaturationOf (:779-781) maps ConnectionPoolTooBusyException and TimeoutException; use at :884 at 31595bca
- OBSERVED: the breaker is `requestVolumeThreshold(20)`, `failureRatio(0.5)`, `skipOn(GatewayException.class)` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java:141-146` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: UpstreamGuards.java:141-145 requestVolumeThreshold(20) failureRatio(0.5) skipOn(GatewayException) at 31595bca
- OBSERVED: `EgressClients` sets no HTTP/1 pool size (Vert.x default 5) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java:167-168,255-257` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: EgressClients.poolOptionsFor (:167-168) sets only the max wait queue size, no pool size, at 31595bca
- HYPOTHESIS: under a never-answering upstream with a pool of 5, the breaker's failure ratio stays below 0.5 once traffic exceeds ~0.3 req/s, so it never opens — confirm by the D1 test (verify-at-outline)
- HYPOTHESIS: a pre-guard short-circuit can make outcomes breaker-neutral without changing the timeout/retry nesting — confirm at `UpstreamGuards.guardFor` and the SmallRye Guard API (verify-at-outline, D2)
- OBSERVED: `clientDeparted` only abandons; `awaitResponse` blocks on `get()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:871,1120-1127` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: DispatchStage clientDeparted (:1120-1128) only records the departure and abandons the current attempt; awaitResponse (:824) holds the request future, at 31595bca
- HYPOTHESIS: `UpstreamConnectionTooLate` is unreachable since the wait is ≤10 s — confirm at `DispatchStage.UpstreamConnectionTooLate` and `GrpcDispatchStage` hand-over checks (verify-at-outline, D4)
- Verify-first clause: re-ground every line reference at the HEAD this plan starts from (PLAN-SEC2-01 edits Javadoc in `DispatchStage`).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamPoolSaturationTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/` — further dispatch tests (verify-at-outline)
- OBSERVED: `doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — GW-07 edge passages only
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/LogMessages.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-01 (shipped) and the orchestrator's post-landing re-check of the private notes.
- Overlaps with: PLAN-SEC2-03 on `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` only (different sections); PLAN-SEC2-01 on `DispatchStage.java` (Javadoc, lands first).
- Adjacent to: `edge/admission/` (admission cap feeds the wait-queue bound; not changed).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-02-upstream-breaker-accounting.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the
git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file
under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
