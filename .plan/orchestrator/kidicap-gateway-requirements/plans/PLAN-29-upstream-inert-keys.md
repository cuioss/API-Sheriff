# PLAN-29: Four Upstream Keys That Parse and Act on Nothing

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-29-upstream-inert-keys.md` and is queued as one row file,
> `queue/PLAN-29.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-13 from inbox message `integration-test-evidence-follow-ups-001.md` and from PLAN-27's
> landing (`landings/PLAN-27.md`, message `integration-test-evidence-follow-ups-009.md`).

## Objective

A route's `upstream` block accepts four keys the gateway binds, validates and never reads:
`connect_timeout_ms`, `read_timeout_ms`, `retry.max_attempts` and `retry.idempotent_only`. An operator who
sets a 2-second connect timeout gets the gateway's fixed 30-second dispatch timeout; one who sets three
attempts gets two. PLAN-27 (#433) removed the fifth such key, `circuit_breaker`, and recorded in ADR-0064
that these four remain. This plan settles each of them: the key either acts, or it is gone.

Unlike PLAN-27, this plan **may change runtime behaviour**. That is its purpose.

## Source

Inbox `integration-test-evidence-follow-ups-001.md` (finding, 2026-10-12): the remainder of
code-correctness finding DP-2. ADR-0064 § "What this decision does not settle" names the same four keys.
Promoted lesson `2026-10-13-06-003` carries the documentation side.

## Deliverables

> ✅ **DECIDED by the operator 2026-10-26: REMOVE ALL FOUR KEYS** (both pairs: `connect_timeout_ms` and
> `read_timeout_ms`; `retry.max_attempts` and `retry.idempotent_only`). Nothing is wired. Consequences for the
> deliverables below, which still carry both branches as written: **D1** records the decision as an amendment
> to ADR-0064 (the "if both pairs are removed" branch) and no new ADR; **D2 and D3** take their "If removed"
> branch only; `retry.enabled` stays, so `UpstreamConfig.Retry` stays with that one component and its
> `ConfigModelReflection` registration; the guard's fixed 30 s timeout, `maxRetries(2)` and the #506 constants
> (10 s idle, 25 s deadline) are untouched and become the documented fixed behaviour; **D5** becomes the
> load-refusal test for each removed key (a descriptor declaring one fails at load; it must go red if the
> refusal is deleted), plus the removal of the tests and fixtures that bind them (`ConfigLoaderTest` :501,
> `ConfigModelContractTest` :300–302 and :591–592, `ConfigValidatorTest` :119–137) — no
> `declared-limit-assertion-coverage` rows are added for removed keys; **D6** states the fixed behaviour
> (30 s, two retries on the idempotent set) instead of the keys. The structural-constraint and D2-relation
> notes in the re-grounding block below no longer apply. The remaining operator question is whether
> `PLAN-V02-48` (which rewrites ADR-0064) may run first; the answer is no unless this plan is re-grounded.

1. **Decide and record, per key, whether it is wired or removed.** Four keys, two decisions (the timeout
   pair, the retry pair). Pre-1.0: a removed key is deleted with no deprecation, and a descriptor still
   declaring it is refused at load, as ADR-0064 did for `circuit_breaker`. ⚠ **Put both decisions to the
   operator at outline time, with a recommendation each**; do not settle them silently. Record the
   outcome in a new ADR, or as an amendment to ADR-0064 if both pairs are removed. Derive the ADR ordinal
   by listing `doc/adr/` at write time.
2. **Carry out the timeout decision.** If wired: `connect_timeout_ms` and `read_timeout_ms` reach the
   upstream client and the dispatch guard, and the documented `504` follows from them. State how the
   per-route value relates to the guard's fixed 30 s timeout (replaces it, or is capped by it).
   If removed: delete the keys from `UpstreamConfig`, the schema and the reflection registration, and add
   the load-refusal test.
3. **Carry out the retry decision**, the same way. If wired: `max_attempts` replaces the fixed
   `maxRetries(2)` and `idempotent_only` governs what `DispatchStage.allowsRetryAfter` already vets;
   state the bounds `ConfigValidator` enforces.
4. **Close DP-6 in the record — the defect itself is already fixed.** ⚠ Re-scoped 2026-10-14 (cleanup at
   `5490144f`): the quality report's DP-6 (the 30 s guard timeout strands a pooled upstream connection)
   no longer holds. Since #386 (`84afdba0`) `DispatchStage.awaitDispatch` calls `attempt.abandon()` on any
   exit other than handing the response back, the guard timeout's interrupt included, which resets the
   request in flight and releases a paused response; since #448 (PLAN-30, ADR-0066 Amendment A1) the
   default HTTP/1.1 client closes that connection abortively. Do not build a second mechanism. What
   remains: mark DP-6 resolved in `doc/quality-report/code-correctness.adoc` (its entry and its location
   `DispatchStage.java:246-266` are stale) and `doc/quality-report/README.adoc`, naming #386 and #448. If
   wiring a timeout key changes the guard's timeout, keep the abandon path covered by a test. ⛔ If the
   work reaches beyond the dispatch guard and the upstream client, STOP and file a finding to the epic
   inbox.
5. **Every wired key gets a test that goes red when the key is deleted** (CLAUDE.md: a configuration key
   that parses is not a configuration key that acts). A wired timeout is asserted against an upstream that
   stalls; a wired attempt count against an upstream that fails a counted number of times. Add the rows
   to `doc/development/declared-limit-assertion-coverage.adoc`.
6. **Make the documentation say what the gateway does.** `doc/architecture.adoc` (the `504` row and the
   `DEADLINE_EXCEEDED` row name the two timeout keys as the cause), `doc/adr/0008-data-plane-transport.adoc`
   (its sentence that timeouts are "transport options … not fault-tolerance timeouts" is untrue beside the
   guard's 30 s timeout; this plan is that sentence's only owner — `api-sheriff-0-2-0` `PLAN-V02-04`
   defers to it — and #433's Amendment A2 already settled the breaker half),
   `doc/configuration.adoc` (the upstream section, its inert-key note, and the validation-rules line on
   the two timeouts), `doc/user/endpoint-routes.adoc` (the example marked "INERT today"),
   `doc/variants/01-base-gateway.adoc` (an example still carrying `read_timeout_ms: 5000` with no note),
   and the DP-2 and DP-6 entries of `doc/quality-report/code-correctness.adoc` and
   `doc/quality-report/README.adoc`. Sweep every example for all four keys, not only the ones listed.

> ⚠ **Re-grounded 2026-10-26 (cleanup at `54f82f03`).** The premise holds: the four keys are still bound and
> unread, #506 (V02-45) added only fixed gateway constants (10 s request-body idle, 25 s request-body
> deadline, 5 min gRPC stream idle) that read none of them, and D4 stays a record correction (DP-6 is fixed
> by #386 and #448). The spec's code locations are stale, because #487 moved the guard and the client and #476
> merged `ResolvedRoute` into `Route`:
>
> | Spec says | At `54f82f03` |
> |---|---|
> | `GatewayEdgeRoute.guardFor` (:2568–2571, :2657–2660) | `edge/upstream/UpstreamGuards.java` `guardFor` :88–116 (timeout :110, retry :111–114, breaker :102–109) |
> | `GatewayEdgeRoute.clientFor` (:2378–2382) | `edge/upstream/EgressClients.java` `clientFor` :167–181 (`SO_LINGER 0` :171); egress TLS :190–197 |
> | `RouteTableBuilder.resolveRetryEnabled` (:239/:371) | no such method: `resolveRoute` :239 reads `route.upstream().retry()` :252, `routeToggle` (:381) at :254; the value ends in `Route.retryEnabled` and `ResilienceShape(target, retryEnabled)` |
> | `endpoint.schema.json` 211–219 | 259–267; no `minimum`, so no positivity bound exists anywhere |
> | `architecture.adoc` :1318, :1420 | :1675 (504 row), :1777 (`DEADLINE_EXCEEDED` row) |
> | `endpoint-routes.adoc` :136–137 | :149–155 |
> | `variants/01-base-gateway.adoc` :115 | :133 (the same example's :132 comment "replaces the matched prefix" contradicts the append semantics, ADR-0004 A2) |
> | `ConfigLoaderTest:341`, `ConfigValidatorTest:1030` | `ConfigLoaderTest` :501 (`max_attempts: 3`, in a binding test that must be edited if the key is removed); `ConfigValidatorTest` :119–137 (`shouldAcceptMillisecondPrecisionTimeout`, `readTimeoutMs(2500)`) |
> | `DispatchStage.java:246-266` (DP-6 location) | `edge/upstream/DispatchStage.java` `awaitDispatch` :467–493; the DP-6 entry is `code-correctness.adoc` :725–739; DP-2 :644–671 (it cites `resolveRetryEnabled` :667) |
> | ADR-0066 "Amendment A1" | ADR-0066 was rewritten in place and has no amendment headings: the abortive-close lifecycle is the section "A gateway-closed HTTP/1.1 upstream connection is closed abortively" (:141); the V02-46 landing calls it "A2" |
> | `ConfigValidator` hosts bounds for a wired key | `ConfigValidator` is a 289-line rule list (ADR-0071); a bounds rule is a new or extended class in `config/validation/rule/` (`ProtocolRules` is the closest), see `doc/development/boot-validation-rules.adoc` |
> | `doc/configuration.adoc` | upstream paragraph :3994–3999, inert NOTE :4001–4005, validation line :5003–5006 ("must be positive integers", which no rule enforces) |
> | ADR-0008 timeout sentence | `doc/adr/0008-data-plane-transport.adoc` :82–83; adjacent :84–87 and :73–75 |
>
> - **Structural constraint for D2/D3.** The guard is built once per `ResilienceShape(target, retryEnabled)` and
>   shared across routes, and the client is shared per `UpstreamTarget`. A per-route timeout or attempt count
>   cannot be a guard or client option without widening those keys; the outline chooses between widening
>   `ResilienceShape` and applying per-request values in `DispatchStage.awaitDispatch` (:470–475). This is the
>   strongest argument for the remove option, and pre-1.0 removal is cheap.
> - **D2 relation to #506.** `UpstreamGuards` and `EdgeHardeningOptions` state the ordering idle 10 s < deadline
>   25 s < guard 30 s; `DispatchStage`'s constructor checks only deadline > idle (:283–285). A wired
>   `read_timeout_ms` under 25 s inverts it, so the ADR and `doc/architecture.adoc` (anchors
>   `_request_body_idle_timeout`, `_request_body_deadline`) must state the relation. V02-45 shipped (#506), so
>   the "whichever plan runs second" coordination below is now one-way and this plan owns the statement.
> - **Also sweep.** `UpstreamConfig` Javadoc (:40–41, :62–64), the `DispatchStage` Javadoc (:160, "the edge's
>   idle timeout can reap an inbound connection before the route's read timeout fires": there is no route read
>   timeout), `README.adoc` :105–110, and `doc/adr/0064` (:9, :101–111, retry bullet :106–108; rewritten in
>   place by #521, content survives). The `ConfigModelReflection` registration of `UpstreamConfig.Retry` stays
>   while `retry.enabled` stays.
> - **ADR ordinal.** 0072 is free (0071 is the highest). Sibling `PLAN-V02-48` (staged) rewrites ADRs 0008 and
>   0064 and defers the 0008 timeout sentence to D6: it must NOT run beside this plan; if it lands first,
>   re-ground the 0008/0064 line references.

Split guard: 6 deliverables — within the operator-authorized 12.

## Claim Labels

- OBSERVED: `connectTimeoutMs`, `readTimeoutMs`, `maxAttempts` and `idempotentOnly` occur under `api-sheriff/src/main` only in `UpstreamConfig` (record components and Javadoc); nothing reads them — read by searching `api-sheriff/src/main` for the four names at `128fd07e`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: the four names occur under api-sheriff/src/main only in UpstreamConfig.java:52-53 and :70 plus the schema; no upstream timeout is set anywhere in main (EgressClients, EdgeHardeningOptions:228 is the inbound listener); #506/#515/#487/#476 wired none
- OBSERVED: the dispatch guard applies `withTimeout().duration(30, SECONDS)` and, for a route with retry enabled, `withRetry().maxRetries(2).delay(100, MILLIS).whenException(DispatchStage::allowsRetryAfter)` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § the guard builder (lines 2568 to 2571), at `128fd07e`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: code moved by #487: edge/upstream/UpstreamGuards.java guardFor :88-116, withTimeout 30 s :110, maxRetries(2).delay(100 ms).whenException(DispatchStage::allowsRetryAfter) :111-114; allowsRetryAfter DispatchStage.java:413; idempotent set hard-coded in StreamAwareRetryGate:45
- OBSERVED: only `retry.enabled` acts, through `RouteTableBuilder.resolveRetryEnabled` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRetryEnabled`, at `128fd07e`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: behaviour holds, symbol gone: RouteTableBuilder.resolveRoute :239 reads route.upstream().retry() :252 and calls routeToggle :254 (declared :381); max_attempts and idempotent_only never read; resolveRetryEnabled no longer exists
- OBSERVED: the schema declares all four keys — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `upstream` (lines 211 to 219), at `128fd07e`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: endpoint.schema.json:259-260 connect_timeout_ms, read_timeout_ms (bare integer), :265-267 retry keys; no minimum; no rule under config/validation reads them, so doc/configuration.adoc:5003 'must be positive integers' overstates
- OBSERVED: `doc/architecture.adoc` names `connect_timeout_ms` / `read_timeout_ms` as the cause of `504` in two rows; `doc/user/endpoint-routes.adoc` marks the keys inert in its example; `doc/variants/01-base-gateway.adoc` carries `read_timeout_ms: 5000` unmarked — read at those files at `128fd07e`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: architecture.adoc:1675 (504) and :1777 (DEADLINE_EXCEEDED) name the keys; endpoint-routes.adoc:149-155 INERT block; variants/01-base-gateway.adoc:133 read_timeout_ms 5000 unmarked; only the lines moved
- HYPOTHESIS: the upstream client is built from a bare `HttpClientOptions` with no connect and no idle timeout, only the egress-TLS settings — the sender's statement; the orchestrator saw the bare options object and did not read the whole method. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `clientFor` (verify-at-outline)
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: no connect or idle timeout still holds, but EgressClients.clientFor :167-181 sets SO_LINGER 0 on the HTTP/1.1 branch (:171, #448) then egress TLS :190-197; moved from GatewayEdgeRoute by #487; spec re-scoped
- HYPOTHESIS: the 30 s guard timeout strands a pooled upstream connection when it fires (DP-6) — taken from the quality report, not re-read. Confirm/refute at `doc/quality-report/code-correctness.adoc` § DP-6 and the dispatch path it cites (verify-at-outline)
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: DP-6 fixed: DispatchStage.awaitDispatch edge/upstream :467-493 abandons the attempt in finally for any non-handed-back exit (:489-491) and dispatch() calls abandonCurrentAttempt at :364-366; default client closes abortively; D4 stays a record correction (code-correctness.adoc:725-739 still the old text)
- HYPOTHESIS: no descriptor in the repository's own stacks relies on any of the four keys, so removal breaks no suite — not checked. Confirm/refute by searching `integration-tests/src/main/docker/`, `benchmarks/` and `deployment/` for the four keys (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: key names appear in no descriptor under integration-tests, benchmarks, deployment, demo-client, build-parent or main resources; test hits only ConfigLoaderTest:501, ConfigModelContractTest:300-302 and :591-592, ConfigValidatorTest:119-137
- Verify-first clause: settle deliverable 1 before writing code. The two decisions determine which of deliverables 2 to 5 exist in which form, and the surface below is declared for both outcomes.
  - verdict: unverifiable | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: process rule; the operator decision is still outstanding: ADR-0064:99-111 and doc/configuration.adoc:4001-4005 still say undecided

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/UpstreamConfig.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED (re-grounded 2026-10-26: the guard and client code left `GatewayEdgeRoute`, now a thin wiring root, in #487): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` (guard timeout and attempt count) and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java` (the client; a connect timeout at client level) — if a key is wired
- OBSERVED (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/assembly/ResilienceShape.java` and `RouteAssembler` (the guard cache key is `(UpstreamTarget, retryEnabled)`: a per-route value must enter the key or bypass the shared guard), `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/Route.java` (carries `retryEnabled`, would carry any resolved value) — if a key is wired
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — only to remove stale wiring references; it no longer holds the guard or client code
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — if a key is wired (`resolveRoute` :239, `routeToggle` :381); `retry.maxAttempts` and `idempotentOnly` are read nowhere
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/ConfigModelContractTest.java` — PLAN-27 touched both for the one key it removed
- OBSERVED: `doc/architecture.adoc`, `doc/configuration.adoc`, `doc/user/endpoint-routes.adoc`, `doc/variants/01-base-gateway.adoc`
- OBSERVED: `doc/adr/` — one new record or an amendment to ADR-0064, and `doc/adr/0008-data-plane-transport.adoc`
- OBSERVED: `doc/quality-report/code-correctness.adoc`, `doc/quality-report/README.adoc`, `doc/development/declared-limit-assertion-coverage.adoc`
- OBSERVED (added 2026-10-14, cleanup at `5490144f`; line moved 2026-10-26): `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidatorTest.java` — :119–137 (`shouldAcceptMillisecondPrecisionTimeout`) builds `UpstreamConfig` with `readTimeoutMs(2500)`, so removing that key breaks its compilation; `ConfigLoaderTest` :501 and `ConfigModelContractTest` :300–302, :591–592 also use the keys
- OBSERVED (added 2026-10-14): `doc/adr/0064-The_upstream_guard_is_fixed_in_the_gateway_and_a_route_declares_no_circuit_breaker_settings.adoc` — names the four keys (lines 9, 103 to 106)
- OBSERVED (added 2026-10-14): `doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc` — Amendment A1 is the connection lifecycle deliverable 4 reads against
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java` — if a nested record goes away with a removed key (verify-at-outline)
- HYPOTHESIS (re-grounded 2026-10-26: the rules left `ConfigValidator` under ADR-0071): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ProtocolRules.java` or a new class in that package registered on the `ConfigValidator` rule list, plus `doc/development/boot-validation-rules.adoc` — bounds for a wired key (verify-at-outline)
- HYPOTHESIS (re-grounded 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` — `awaitDispatch`, `allowsRetryAfter` :413 — if a key is wired (verify-at-outline)
- HYPOTHESIS (re-grounded 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/StreamAwareRetryGate.java` — the idempotent method set is hard-coded at :45 — if `idempotent_only` is wired (verify-at-outline)
- HYPOTHESIS (re-grounded 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java` (guard failure to 504) and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java` (:71–76, the ordering constants) — if a timeout key is wired (verify-at-outline)
- HYPOTHESIS (re-grounded 2026-10-26): `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStageTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java` and `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRequestBodyIdleTimeoutTest.java` — the existing homes for stalled-upstream and retry-count tests; no UpstreamGuards or EgressClients test class exists, so a new one is needed for a wired timeout or attempt count (verify-at-outline)
- HYPOTHESIS (added 2026-10-26): `doc/security-threat-model.adoc` (matches the guard/timeout pattern once: check the mention) and `README.adoc` :105–110 (lists the four keys as inert)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` and `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — only if a wired key is also proven at integration level, or a descriptor there declares a removed key (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-27 — landed (#433). ADR-0064 is the precedent for removal.
- Overlaps with: PLAN-28 on `GatewayEdgeRoute`-adjacent test fixtures only if PLAN-28's deliverables 6
  or 8 find their cause in the gateway's edge; with PLAN-20, PLAN-21, PLAN-26 and PLAN-28 on
  `doc/configuration.adoc`, `doc/architecture.adoc` and `doc/adr/`. `GatewayEdgeRoute` and
  `UpstreamConfig` are touched by no other staged spec.
- Re-grounded 2026-10-26: the cross-epic hold is lifted (the sibling refactor chain, including V02-25's edge
  decomposition #487, has shipped) and PLAN-28 (#437), PLAN-30 (#448) and PLAN-32 (#466) are landed, so the
  sequencing gate below is met. No live PR matched the duplicate-run guard. The running siblings V02-05,
  V02-06 and V02-20 share `doc/configuration.adoc`, `doc/architecture.adoc` and `LogMessages.adoc` with D6;
  staged `V02-49` (test simplification part 2) collides on `config/` and `edge/` tests.
- ⚠ **Sequence it after PLAN-28.** PLAN-28 diagnoses two intermittent failures on the dispatch path (an
  edge framing test and the `upload_small` benchmark). Changing the dispatch timeout and retry behaviour
  before those are understood would move the ground under the diagnosis.
- **Coordinates with `api-sheriff-0-2-0` `PLAN-V02-45` deliverable 3** (added 2026-10-15 from #472): V02-45
  adds a total request-body bound (deadline or minimum rate) beside #466's 10 s request-body idle bound.
  This plan decides whether the per-route `read_timeout_ms` is wired. The two must not overlap: whichever
  plan runs second reads the other's result and states how its bound relates to the other's (and to the
  guard's 30 s timeout) in the ADR and `doc/architecture.adoc`. (Resolved 2026-10-26: V02-45 shipped as #506;
  its D3 is the fixed 25 s request-body deadline, so this plan runs second and owns the statement.)
- **If a test fails against shipped behaviour this plan did not intend to change, STOP and file a
  finding** to the epic inbox.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-29-upstream-inert-keys.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
