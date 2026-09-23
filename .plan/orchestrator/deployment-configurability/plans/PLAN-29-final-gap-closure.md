# PLAN-29: Final Gap Closure

epic: deployment-configurability
workstream: WS-09

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Intended as the LAST plan this epic stages. Written after a full close-readiness sweep that
> re-verified every live Open Defect and Watch against HEAD `1994f28`: most had already shipped
> (mainly via PLAN-25/#306 and PLAN-28/#341) but were never marked resolved in the ledger, and two
> more candidates for this plan (`GatewayEdgePipelineTest`'s meter-race flake,
> `TlsEdgeProducerTest`/`SniFrontListenerTest`'s loopback-bind residual) turned out already fixed by
> unrelated PRs from sibling epics — confirmed by direct code read and excluded before staging. What
> remains below is the genuinely open, genuinely actionable residue.

## Objective

Close the three items still genuinely open in `deployment-configurability`'s Open Defects after the
2026-09-23 close-readiness sweep: adopt the `RotationResult.scopeDelta()` signal `TokenRefreshCoordinator`
has silently ignored since the 0.9.5 bump, correct a stale line-number citation in
`BuildGateCoverageContractTest`'s failure message, and document the machine-local port-collision
flakiness this epic has now observed four times as a known environment caveat rather than leaving it
as oral tradition in the ledger alone.

## Deliverables

1. `TokenRefreshCoordinator.rotate()` (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java:590`)
   never reads `RotationResult.scopeDelta()` — the 4-member `ScopeDelta` enum (`EQUAL`, `NARROWED`,
   `BROADENED`, `UNDECLARED`, from `de.cuioss.sheriff.token.client.token.RotationResult`) is threaded
   through every call site's test fixtures but never consulted by production logic. Add a bounded-cardinality
   `WARN`-level log record (BffLogMessages' `WARN` range, 100-199, next free ordinal — do not guess a
   number, allocate it at execute time) fired when `scopeDelta()` is `NARROWED` or `BROADENED`, naming
   the session and the old/new scope. `EQUAL` and `UNDECLARED` stay silent (an undeclared response is
   RFC 6749 §5.1's documented "identical to requested" case, not an anomaly). Do not change refresh
   behavior — this is an observability deliverable, not a policy change: a narrowed or broadened scope
   during refresh is currently invisible to any operator, and this makes it visible without altering
   what the gateway does about it.
2. `BuildGateCoverageContractTest`'s failure message
   (`api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/BuildGateCoverageContractTest.java:230`)
   cites `"the quality gate CLAUDE.md lines 65-66 declare it requires"` — `CLAUDE.md`'s actual
   `**CRITICAL**` Pre-Commit Process declaration has moved to line 55 (a single line, not a two-line
   span) since this citation was written. Correct the cited line number(s) to match current `CLAUDE.md`.
3. Document the machine-local port-collision flakiness (4 sightings recorded in this epic's Open
   Defects: PLAN-11, PLAN-13's wildcard-bind fix, PLAN-03's `TlsEdgeProducerTest`/`SniFrontListenerTest`
   sightings, and a fourth full-suite-rerun incident) as a known environment caveat in a new
   `doc/development/local-test-environment-caveats.adoc`, linked from `doc/development/README.adoc`'s
   contents table. State the symptom (something on the workstation intermittently holds loopback ports
   the fixtures assume free), the "presumptively environmental" reading convention this epic already
   established, and that it is a distinct mechanism from the wildcard-bind class PLAN-13 fixed. No code
   change — this deliverable is documentation only, closing the gap between "the epic's ledger knows
   this" and "a contributor hitting it locally can find out why."

## Claim Labels

- OBSERVED: `RotationResult.scopeDelta()` (`de.cuioss.sheriff.token.client.token.RotationResult`,
  resolved at `token-sheriff-client:0.9.6` per `pom.xml:63`) is a `record` accessor returning
  `ScopeDelta` (`EQUAL`/`NARROWED`/`BROADENED`/`UNDECLARED`, confirmed via `javap`) — read at HEAD
  `1994f28`.
- OBSERVED: `TokenRefreshCoordinator.rotate()` (`:590-603`) reads `rotation.idToken()`,
  `rotation.accessToken()`, `rotation.refreshToken()`, and `rotation.grantedScope()` (via
  `grantedScopes()`) but never `rotation.scopeDelta()` — `grep -rn "ScopeDelta|scopeDelta"` over the
  file returns nothing — read at HEAD `1994f28`.
- OBSERVED: `BuildGateCoverageContractTest.java:230` reads
  `"... the quality gate CLAUDE.md lines 65-66 declare it requires."` — read at HEAD `1994f28`.
  `CLAUDE.md:55` currently reads `**CRITICAL** — run before every commit that touches build inputs;
  both must pass with zero errors/warnings:` — a single-line declaration, not the two-line span the
  citation names.
- OBSERVED: no file matching `doc/development/*caveat*` or `doc/development/*flak*` exists in
  `doc/development/` (18 existing files listed) — read at HEAD `1994f28`.
- OBSERVED (absence, verified — dropped from this plan's scope before staging):
  `GatewayEdgePipelineTest.metersDisallowedVerbUnderItsRoute` (`:274-289`) already runs
  `Awaits.until(...)` polling the meter counter to `>= 1.0` before asserting, with a comment
  confirming the async-metering mechanism — landed via PR #343 (`69b322b`), a sibling epic's
  unrelated portal-feature PR, not any `deployment-configurability` plan.
- OBSERVED (absence, verified — dropped from this plan's scope before staging):
  `TlsEdgeProducerTest.freePort()` (`:284`) already allocates via
  `new ServerSocket(0, 0, InetAddress.getByName(LoopbackHost.ADDRESS))`, and `SniFrontListener`
  already carries a `host` constructor parameter enabling the loopback override — landed via PLAN-18
  (#308, `c74f5d2`).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/BuildGateCoverageContractTest.java`
- OBSERVED: `doc/development/local-test-environment-caveats.adoc`
- OBSERVED: `doc/development/README.adoc`
- OBSERVED: `doc/LogMessages.adoc`

## Dependencies and Sequencing

- Depends on: none — every other plan in this epic is terminal (`shipped`/`landed`/`superseded`).
- Overlaps with: none live. This is the only non-terminal row in the queue.
- Adjacent to: WS-04's refresh-token-reliability plans (PLAN-05, PLAN-26) touched
  `TokenRefreshCoordinator.java` for unrelated deliverables; all terminal, no collision.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/deployment-configurability/plans/PLAN-29-final-gap-closure.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
