# PLAN-18: Fix the nine hollow integration tests PLAN-16 found, and the named loopback and throw-then-assert sites in the unit lane

epic: deployment-configurability
workstream: WS-06

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 from PLAN-16's own deliverable-5 output**, drained from inbox message
> `cookie-deliverability-and-ceiling-001.md`.
> ⛔ **RE-SCOPED 2026-09-15 by the orchestrator's corpus revisit at `origin/main` `a2969b9`.** It now
> also carries **PLAN-23's former deliverables 3 and 5** (the two named loopback residuals and the
> throw-then-assert pair) as deliverables 3-6 here, because those need no audit and name exact files
> that do not collide with PLAN-25. Operator decision: up to 12 deliverables per plan are authorized.
> PLAN-23 keeps only the open-ended unit-lane audit. ⚠ **Accepted cost**: deliverables 3-6 are a WS-07
> concern riding a WS-06 plan — the same trade PLAN-14 → PLAN-15 made.

## Objective

Two sets of tests pass without exercising what they name. Nine methods in eight integration-test files
were found by PLAN-16's **exhaustive** read of `integration-tests/src/test/**`; three unit-lane sites
(`TlsEdgeProducerTest`, `SniFrontListenerTest`, and the carve-out in `LoopbackEphemeralBindArchTest`
that excuses the first of them) were named by PR #255's loopback investigation and the 2026-09-11
lessons intake. Each analysis names, per site, the observable the fix must reach.

⛔ **This is execution against completed analyses, not a re-investigation.** None needs a new fixture.
The unit lane's *unaudited* remainder is PLAN-23's, not this plan's.

## Deliverables

1. **Fix the nine integration sites.** Every one is a `strengthen` except the first, which is a delete:

   | Site | What is wrong | What the fix asserts |
   |---|---|---|
   | `ApiSheriffIntegrationIT.java:66` `benchmarkGatewayHealthTargetServesOverHttps` | Identical arrange to `quarkusHealthEndpoint:36`, which asserts strictly more; its own Javadoc concedes it cannot pin what its name claims | ⛔ **Delete the method — and move its merge-queue rationale paragraph onto `quarkusHealthEndpoint`** |
   | `BodyLimitActivationWiringTest.java:144` `containerOverrideExceedsTheNegativeCaseBody` | Compares the compose override against `71303168L`, a hand-copied duplicate of `LargeBodyIT:105` | Derive the bound from `LargeBodyIT`'s own constant |
   | `ConfigLoadedIntegrationIT.java:49` `unmountedPathDeniedByDefault` | A 404 for an unmounted path is produced by *any* route-table failure, including loading no routes | Add the positive control the class Javadoc names — a 200 on the mounted `/proxy/get`. ⚠ `managementHealthReportsUp:69` is **not** it: readiness is `UP` with no routes assembled |
   | `ConfigLoadedIntegrationIT.java:59` `declaredAnchorNamespaceWithoutEndpointServesNothing` | Same missing control | ✅ The one added positive leg fixes both rows |
   | `DirectoryAssetServingIT.java:91` `authenticatedAssetRejectsWithoutToken` | "Rejected 401 **before any file is read**" is asserted nowhere | Assert **by contrast**: a non-existent file under `/secure-assets` must also answer 401 |
   | `MetricsIT.java:58` `sheriffMetersAppearAndMoveAfterProxyTraffic` | Named "appear **and move**", but every assertion is a `contains` | Sum before, act, assert strictly greater — as sibling `securityEventsMeterAppearsAndMovesAfterRejection:90` does |
   | `MtlsHandshakeIT.java:87` `wrongCaClientCertRejected` | With `test.mtls.wrong.keystore` unset the context has no key manager — byte-identical to `noClientCertRejected:77` | Assert the property resolves to a readable keystore before using it |
   | `PassthroughFaultIT.java:109` `midStreamResetSurfacesAsAbort` | `assertThrows(IOException.class, …)` is satisfied by an unmapped SNI, an unreachable Toxiproxy or a down listener | Add the matched control (same SNI, toxic removed, reads normally), **or** narrow the expected exception |
   | `TlsEdgeActivationWiringTest.java:291` `failsafeWiresMtlsSystemProperties` | Asserts `pom.xml` contains the element name `<test.mtls.port>`; a wrong value passes | Assert the value against the port the compose file publishes, as `EgressVerifyActivationWiringTest` does |
   | `WsAdmissionActivationWiringTest.java:82` `overlayDeclaresAnExhaustibleAdmissionBudget` | Checked against `SEQUENTIAL_UPGRADES = 10`, a hand-copied mirror of `WebSocketProxyIT.LOW_CAP_SEQUENTIAL_UPGRADES` | Derive the bound from `WebSocketProxyIT`'s constant |

2. **Correct the documentation drift the audit left standing.** `DescriptorInventoryWiringTest`'s Javadoc
   (`:47`) says the declared-limit note enumerates *"sixteen files — seven `gateway.yaml` documents"*;
   the note now enumerates more. The guard derives its expectation from the note at runtime, so the
   count is prose — correct it, or replace it with a pointer to the note rather than a number that goes
   stale again.
3. **Fix target A — `TlsEdgeProducerTest`'s allocation socket.** `freePort()` allocates with a wildcard
   `new ServerSocket(0)` at `:282` (wildcard re-probe at `:309`), and `startsAndStopsFrontListener`'s
   precondition at `:117` is a loopback **connect**, not a bind — ⛔ there is no "control listener",
   whatever the archived lesson's wording says. Allocate on `LoopbackHost.ADDRESS`, keep the re-probe.
   ⛔ **Do NOT touch the three collision holders (`:160`, `:202`, `:229`)** — they hold a port so a
   *wildcard* production bind is refused; binding them to loopback inverts the refusal they assert.
4. **Fix target B — `SniFrontListenerTest:145`.** It constructs `SniFrontListener(..., 0)`, which
   wildcard-listens in **production** (`SniFrontListener.java:98` `netServer.listen(publicPort)`) and is
   then dialled on loopback (`:151`); the arch rule cannot see it because the call is in `src/main`.
   If the fix needs a host parameter on the production listener, that change is in this plan's
   declared surface — keep it minimal and default-preserving.
5. **Narrow `LoopbackEphemeralBindArchTest`'s class-level carve-out** (`CARVED_OUT_TEST`, `:131`) from
   the whole `TlsEdgeProducerTest` to the three collision holders, which is what its own rationale
   (`:102`) describes. Record why it cannot be removed entirely.
6. **Remove the surviving throw-then-assert pair, and land a guard with it.**
   `TlsEdgeProducerTest.awaitNotListening` calls `Awaits.until(() -> !isListening(port), …)` (`:343`)
   and then `assertFalse(isListening(port), message)` (`:344`); `Awaits.until` throws on timeout, so the
   assertion can never be what fails (archived lesson `2026-09-01-14-003`). If a mechanical check for
   "`Awaits.until(c)` followed by an assertion of the same `c`" is cheap, land it in the same change.
7. **Prove every change by reversion, not by green.** For each strengthened test, establish that it
   fails when the behaviour it names is reverted. The three `derive from the sibling constant` fixes
   are reverted by changing the *source* constant and confirming the dependent test moves. For
   deliverables 3-5, ⛔ **a local red or local green is not proof** — PLAN-13's mechanism is a wildcard
   ephemeral bind colliding with a *foreign* `127.0.0.1` listener; establish provenance against CI
   history before attributing a local result to the change (lesson `2026-09-01-14-002`).

## Claim Labels

- OBSERVED: the nine sites, their shapes and their prescribed fixes — read from PLAN-16's audit
  message `cookie-deliverability-and-ceiling-001.md`, produced by an **exhaustive** read of all 56
  files under `integration-tests/src/test/**` at `497c592`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical record of PLAN-16's exhaustive audit output
- OBSERVED: all ten named integration-test files are unchanged between `990aebf` and `a2969b9`, and every
  cited method still sits on its cited line.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: historical drift-check anchored explicitly to the 990aebf..a2969b9 interval
- OBSERVED: at `a2969b9`, `TlsEdgeProducerTest.java` allocates with wildcard `new ServerSocket(0)` at
  `:282`, holds collision sockets at `:160`/`:202`/`:229`, and pairs `Awaits.until` with a re-asserting
  `assertFalse` at `:343-344`; `SniFrontListenerTest.java:145` constructs the listener on port `0`;
  `SniFrontListener.java:98` calls `netServer.listen(publicPort)`; `LoopbackEphemeralBindArchTest.java:131`
  names `tls.TlsEdgeProducerTest` as `CARVED_OUT_TEST`. None of these files changed since `428bbec`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: this plan's own deliverables have since landed: the benchmark-target test deleted, TlsEdgeProducerTest's allocation socket now binds via loopback host explicitly
- HYPOTHESIS: fixing `SniFrontListenerTest` requires a host parameter on the production
  `SniFrontListener` — confirm/refute at `SniFrontListener.java` § the `netServer.listen(publicPort)`
  call (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed: SniFrontListener now has a host-parameter constructor overload, SniFrontListenerTest.java:145 passes an explicit HOST argument
- Verify-first clause: re-read each site at HEAD before changing it. A site whose shape no longer matches
  its description is re-analysed, not force-fitted to the prescribed fix.

## Expected Surface

- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ApiSheriffIntegrationIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BodyLimitActivationWiringTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ConfigLoadedIntegrationIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/DirectoryAssetServingIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MetricsIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MtlsHandshakeIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/PassthroughFaultIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/TlsEdgeActivationWiringTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/WsAdmissionActivationWiringTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/DescriptorInventoryWiringTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/LoopbackEphemeralBindArchTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — only if deliverable 4 needs the host parameter (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/AwaitsReassertionArchTest.java` — deliverable 6's guard, only if cheap (new file; verify-at-outline)

⛔ **Every entry is a NAMED FILE, deliberately** — a directory declaration here would contain PLAN-25's
test files and serialize two plans that do not collide.

## Dependencies and Sequencing

- ✅ **No hard dependency.** PLAN-16 and PLAN-08, the two dependencies this spec carried, have shipped.
- ✅ **Surface-disjoint from PLAN-24 and PLAN-25 as declared** — the three form the parallel round.
- ⛔ **PLAN-23 waits for this plan** — it declares `api-sheriff/src/test/` wholesale, which contains three
  files declared here.
- ✅ **Not urgent.** These tests pass today; the defect is that they would keep passing if the behaviour
  broke.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-18-pro-forma-integration-test-fixes.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
