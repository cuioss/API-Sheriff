# PLAN-13: Land the measured loopback-stall fix and its socket-state instrumentation

epic: deployment-configurability
workstream: WS-07

> ⛔ **TRACKING SPEC for a plan this epic did not launch.** `loopback-stall-fix-and-instrumentation`
> was created ad-hoc on 2026-09-03 08:54:52Z and carries `source_id: none`, so no
> `inbox detect` will ever classify it as orchestrated and no landing will arrive by inbox drain.
> It is a REAL plan-marshall plan — unlike PLAN-12 it has a plan id, phases and a finalize — so its
> `plan_marshall_plan_id` row field IS stamped; what it lacks is provenance from this ledger.
>
> **It carries a row for exactly one reason: gate coverage** — the same reason PLAN-12's spec gives.
> A row is the only way this epic's surface-disjointness gate can see the work, and the corpus
> requires row↔spec in both directions, so a row without a spec would itself be a reported defect.
> The cost of not doing this is already measured twice in this epic: PLAN-11 spent its whole first
> day invisible to the gate, and the ADR-0038 ordinal was taken by a plan nobody predicted.
>
> **The authoritative brief is the plan's own `request.md`**; nothing here restates it.

## Objective

Land the fix the PLAN-12 investigation measured: replace the dual-stack wildcard `.listen(0)` with
`.listen(0, "127.0.0.1")` at every ephemeral-bind test call site, and land the socket-state
instrumentation that made the mechanism observable. PLAN-12 established the cause and refuted the
kqueue hypothesis; this plan applies the remedy and guards it against regression.

## Expected Surface

Declared from the plan's own `references.affected_files` (19 entries, read 2026-09-03), not
re-derived — the plan is the authority on its own footprint.

- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgePipelineTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteBffWiringTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GrpcDispatchStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GrpcStatusMapperTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/ReservedBodyCeilingTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/AwaitsTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/LoopbackHost.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/SocketSnapshot.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/LoopbackEphemeralBindArchTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/specimen/LoopbackEphemeralBindSpecimen.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/specimen/WildcardEphemeralBindSpecimen.java`
- OBSERVED: `doc/development/build-gate-discipline.adoc`
- OBSERVED: `.plan/local/lessons-learned/2026-08-29-16-002.md`

## Claim Labels

- OBSERVED: the fix is `.listen(0)` → `.listen(0, "127.0.0.1")` at **20 call sites across 9 files**
  in `api-sheriff/src/test/java` — 7 files under `edge/`, 2 under `tls/`. Counted at `5e8413b`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the fix now uses .listen(0, LoopbackHost.ADDRESS) at 30 call sites across 17 files, not the measured 20/9
- OBSERVED: the mechanism PLAN-12 measured is a wildcard/loopback listener collision, **not** a
  kqueue readiness failure — 179 foreign `127.0.0.1` listeners in 49152–65535 give 1.09 % per
  `listen(0)`, and the matched loopback-bound control arm took 0 of them over 20 000 binds.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical benchmark measurement (179 foreign listeners, 1.09 percent collision rate)
- OBSERVED: the plan declares an ArchUnit guard (`LoopbackEphemeralBindArchTest` plus two specimens),
  so the fix is regression-guarded rather than applied once and left to erode.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: LoopbackEphemeralBindArchTest.java and both specimen classes still exist at HEAD
- HYPOTHESIS: the socket-state instrumentation (`SocketSnapshot`, `LoopbackHost`) is retained as
  permanent test infrastructure rather than removed after the diagnosis (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SocketSnapshot.java/LoopbackHost.java still exist, LoopbackHost now used across 17 files
- Verify-first clause: the call-site count and the file set are re-counted against HEAD before the
  fix is declared complete — 20/9 is a measurement at `5e8413b`, not a constant.

## Dependencies and Sequencing

- **Depends on: none.** PLAN-12 has concluded (`landed`, `landings/PLAN-12.md`) and supplied the
  measured cause this plan acts on. Nothing else gates it.
- ⛔ **Blocks PLAN-07 and PLAN-08 in practice, and the path matcher DOES see this one.** Three of its
  files sit under `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/`, which is the declared
  test surface of both plans. Do not emit either while this plan runs.
- ⛔ **Overlaps PLAN-03 through shared test infrastructure the matcher CANNOT see.** PLAN-03 edits
  `edge/WebSocketRelayStage.java`; this plan edits `edge/WebSocketRelayStageTest.java` and mutates
  `testsupport/Awaits.java`, consumed by 7 `edge/` and 3 `tls/` test files. Re-check by hand.
- Adjacent to: the epic's 30 s-hang defect, which this plan **narrows but does not close** — the 5 s
  TEARDOWN-tier occurrence (`f243e7`) is unmeasured, and CI cleanliness is unproven.
