# PLAN-27: Two More Downstream Properties at IT Level, and What PLAN-25 Left Open

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-27-integration-test-evidence-follow-ups.md` and is queued as one row file,
> `queue/PLAN-27.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-12 from inbox message `kidicap-gateway-downstream-013.md` and from PLAN-25's landing
> (`landings/PLAN-25.md`, message `plan-25-integration-test-evidence-017.md`).

## Objective

The downstream raised its parent to API Sheriff 0.2.4 and asks for integration tests for two more
properties it states to its operators and cannot measure itself: that a token response not bound to the
gateway's DPoP key is refused (M-62), and that a session lacking an in-grant route scope obtains it by a
refresh with no browser round trip (M-64). PLAN-25 built the instrument both need — a recording stub
identity provider — so they are now cheap to pin.

The same plan closes what PLAN-25 left open at its merge: two decisions never evaluated for an ADR, five
security-audit findings left pending triage, and a handful of test-hygiene items its own PR names. It is a
**tests-and-records** plan. PLAN-25 was filed tests-only and absorbed one production change anyway; this
spec therefore states the rule and the one place it may bend, instead of pretending it cannot.

## Source

Inbox `kidicap-gateway-downstream-013.md` (finding, 2026-10-06), filed after the downstream adopted 0.2.4,
in the same form as `-012`:

| Downstream id | Property | State at 0.2.4 as filed |
|---|---|---|
| M-62 | a token response not bound to the gateway's DPoP proof key is refused: `400` at the callback, no session, ApiSheriff-134 | `BffFapiControlsIT` and `BffClientSecretModeIT` assert the positive case only; no IT drives an unbound response |
| M-64 | a session lacking a route scope inside its granted set obtains it by a refresh, without a browser round trip | `BffSessionScopeParityIT.refreshRestoresAScopeOfTheGrantAfterANarrowedRefresh` probes the identity provider, not the gateway's scope-driven refresh |

Also from that message, **no work, recorded so the tests are not removed unnoticed**: the downstream now
cites `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush` and
`.xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives` as upstream evidence for M-61.

PLAN-25's landing residue and PR #408's "Known Open Items" supply deliverables 3 to 5.

## Deliverables

> ⚠ **RE-GROUNDED 2026-10-12 at `c013781d`, after PLAN-24 landed (#430). Two rows are smaller than
> filed; read this before the list.**
>
> | # | State at `c013781d` | What remains |
> |---|---|---|
> | **2** (M-64) | ⛔ **already pinned** | `BffRefreshScopeWireIT.refreshGrantsCarryTheSessionScopeOnTheWire` drives exactly this on the stub: a scripted token answer narrows the active set below the grant, a call on the scoped route with a token far from expiry records exactly one further refresh grant carrying the narrowed set plus the missing scope, and the route is served with that token. What it does **not** assert is that no authorization request was made. Remaining work: add that one assertion (the count of pushed authorization requests is unchanged across the scope-driven call), name the test as the M-64 evidence in `doc/quality-report/test-quality.adoc`, and close the deliverable. Do not write a second test |
> | **4**, nginx row | **done for the one file named** | `BffTlsRelaxationIT` pins `nginx:1.27-alpine` by digest. Tag-only references remain at `integration-tests/docker-compose.yml` (three services) and `JwksLateIdpReadinessIT` (`PROXY_IMAGE`). That is deliverable 5's "other nginx image references", now located |
> | 1, 3, 5 | unchanged | M-62: the identifier exists (`BffLogMessages` WARN 134, `TOKEN_RESPONSE_NOT_BOUND`, emitted from `BoundTokenEndpointClient`) and no integration test scripts an unbound token answer. `circuit_breaker.reset_ms` still has no production reader |
>
> **`StubIdentityProviderRig` changed under this plan.** #430 grew it by 390 lines net (+464/−74): it can now
> rotate its signing key mid-run and counts JWKS requests per issuer. Read it at HEAD before adding the
> unbound-token answer of deliverable 1; do not work from PLAN-25's description of it.
>
> **A flaky test may turn this plan's CI red without cause.** `GatewayEdgeFramingCorpusTest` failed twice
> during PLAN-24 on a branch that touches nothing under `gateway/edge`, in two different methods, and was
> green on re-run both times (lesson `2026-10-12-16-012`). Compare against the sibling run of the same
> commit before changing code. Its diagnosis is PLAN-28's, not this plan's.

1. **M-62 — an unbound token response is refused.** An integration test in which the identity provider
   answers the code exchange with a token that is not bound to the gateway's DPoP proof key, asserting
   `400` at the callback, no session cookie, and the log record the gateway emits. Drive it on the stub
   identity provider PLAN-25 added; the real Keycloak cannot be made to return an unbound token.
2. **M-64 — the scope-driven refresh, through the gateway.** An integration test in which a live session's
   active scope set is narrower than its grant, a request arrives on a route needing a scope inside the
   grant, and the gateway obtains it by a refresh: the stub records one token request carrying the scope,
   the client sees one response, and no authorization request is made. ⚠ `BffSessionScopeParityIT`'s own
   javadoc states the in-grant state cannot be produced through the public surface against Keycloak
   (`A == S` after login and widening) — which is why this needs the stub, and why PLAN-25's
   `BffRefreshScopeWireIT` is the place to look first: if it already observes exactly this, close the
   deliverable with that test named.
3. **Evaluate the two decisions PLAN-25 never put to `adr-propose`, and record the outcome of each.**
   (a) The gateway-generated open-circuit `503` carries a fixed `Retry-After: 5`, while the per-route
   `circuit_breaker.reset_ms` is parsed and never read by the breaker. (b) Test-started containers publish
   the application port on all interfaces at fixed ports, the management port on a docker-assigned
   loopback port, on one network. For (a), also correct `doc/architecture.adoc`, which still says in one
   passage that the breaker "half-opens after `reset_ms`" while its own `503` row states the fixed 5 s.
   ⛔ **(a) is the one deliverable that touches a production question**: a configuration key that parses
   and does not act. Record the decision (wire it, or remove the key — pre-1.0, no deprecation). If the
   decision is to change runtime behaviour, STOP and file a finding to the epic inbox; that is its own
   plan. Derive the ADR ordinal by listing `doc/adr/` at write time.
4. **Triage the five security-audit findings PR #408 left pending**, each to a recorded disposition: test
   ports published on all interfaces; the scoped TLS-relaxation exemption in
   `ItProfileConfigBindingWiringTest`; the nginx proxy image pin; the `Retry-After` without jitter; the
   stub identity provider's admin API on loopback. Fix what is a test-harness fix, record the rest as
   accepted with the reason.
5. **Test hygiene named by PLAN-25's own PR.** `HtmlErrorPageIT.unreachableUpstream` pins exactly `502`
   and can see `503` on a stack reused across suite runs; `reserved-path-overlap/auth-overlap.yaml` sits
   outside the declared-limit inventory guard; the `DescriptorInventoryWiringTest` display name is wider
   than its check; other nginx image references in the repository are tag-only.

Split guard: 5 deliverables — within the operator-authorized 12.

## Claim Labels

- OBSERVED: `BffSessionScopeParityIT` carries `refreshRestoresAScopeOfTheGrantAfterANarrowedRefresh`, `navigationNeedingARefusedScopeIsRefusedAtThePush` and `xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives` — read at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionScopeParityIT.java` § those three methods, at `fdcfaeb3`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: BffSessionScopeParityIT carries the three methods at 305, 340 and 379
- OBSERVED: `StubIdentityProviderRig`, `BffRefreshScopeWireIT` and `BffPendingLoginBoundIT` exist at `fdcfaeb3` — read by listing `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: StubIdentityProviderRig, BffRefreshScopeWireIT and BffPendingLoginBoundIT listed in the IT directory; the rig grew by about 400 lines in #430 (signing-key rotation, per-issuer JWKS request tracking)
- OBSERVED: `doc/architecture.adoc` says the breaker "half-opens after `reset_ms`" in its upstream-guard passage while its `503` row states a fixed `5` — read at `doc/architecture.adoc` § the circuit-breaker bullet and § the `503` error-contract row, at `fdcfaeb3`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: architecture.adoc:1038 still says half-opens after reset_ms; the 503 row at 1242 states the fixed 5
- OBSERVED: `GatewayEdgeRoute` declares `RETRY_AFTER_HEADER` and seeds it on an open-circuit `503` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `RETRY_AFTER_HEADER`, `seedRetryAfter`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: GatewayEdgeRoute:238 declares RETRY_AFTER_HEADER; seedRetryAfter called at 1914
- HYPOTHESIS: no integration test drives an unbound token response, and the refusal is logged as ApiSheriff-134 — taken from the downstream's filing, not re-read; the orchestrator's search for the literal `134` in the log-message classes found nothing, so the identifier may be composed elsewhere. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/BoundTokenEndpointClient.java` § the binding check and its log call (verify-at-outline)
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: the identifier exists: BffLogMessages WARN 134 TOKEN_RESPONSE_NOT_BOUND, emitted at BoundTokenEndpointClient:269; in integration-tests only BffFapiControlsIT:129 names ApiSheriff-134 and no IT scripts an unbound token answer
- HYPOTHESIS: `BffRefreshScopeWireIT` observes the `scope` parameter of a time-driven refresh only, not a refresh triggered by a route's missing scope, so M-64 is not already covered — confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffRefreshScopeWireIT.java` § its test methods (verify-at-outline). ⛔ If it is already covered, close deliverable 2 with the test named.
  - verdict: contradicted | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: yes | evidence: BffRefreshScopeWireIT.refreshGrantsCarryTheSessionScopeOnTheWire already drives the scope-driven refresh through the gateway on the stub: third call on the scoped route with a far-from-expiry token records exactly one further grant carrying the narrowed set plus the missing scope and is served with it; it does not assert that no authorization request was made
- HYPOTHESIS: `circuit_breaker.reset_ms` is parsed into the route configuration and has no reader in the breaker — taken from PR #408's body. Confirm/refute by tracing the key from `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `circuit_breaker` to its production reader (verify-at-outline)
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: resetMs occurs under api-sheriff/src/main only in UpstreamConfig (record component and javadoc) and reset_ms only in endpoint.schema.json:234; no reader
- HYPOTHESIS: the nginx proxy image in `BffTlsRelaxationIT` is still pinned by tag only. The orchestrator read `nginx:1.27-alpine@sha256:…` there at `fdcfaeb3`, which contradicts the PR body's finding — it was probably fixed in a later review round. Confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffTlsRelaxationIT.java` § the proxy image constant (verify-at-outline); if pinned, close that row of deliverable 4 as already done.
  - verdict: contradicted | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: yes | evidence: BffTlsRelaxationIT:164 pins nginx:1.27-alpine by digest; tag-only references remain at integration-tests/docker-compose.yml:110,168,237 and JwksLateIdpReadinessIT:125
- Verify-first clause: settle deliverable 2 first — whether the stub can put a session into the state "active set narrower than the grant" through the gateway's public surface. If it cannot, M-64 is not pinnable at IT level; say so in the quality report and file the finding rather than pinning a weaker property under the same name.
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: settled: the stub can produce the state. BffRefreshScopeWireIT narrows the active set below the grant with a scripted token answer (rig.tokenAnswer with the narrowed scopes) and then calls the scoped route, so M-64 is pinnable at IT level and already pinned

## Expected Surface

- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — the two new ITs, `StubIdentityProviderRig`, `HtmlErrorPageIT`, `DescriptorInventoryWiringTest`, `ItProfileConfigBindingWiringTest`, `OneOffGatewayContainers`
- OBSERVED: `integration-tests/src/main/docker/` — stub mappings and fixtures, `reserved-path-overlap/auth-overlap.yaml`, any nginx image reference
- OBSERVED (added 2026-10-12): `integration-tests/docker-compose.yml` — the three tag-only `nginx:1.27-alpine` image references deliverable 5 pins
- OBSERVED: `doc/architecture.adoc` — the `reset_ms` passage
- OBSERVED: `doc/adr/` — up to two new records
- OBSERVED: `doc/quality-report/test-quality.adoc`, `doc/development/integration-test-topology.adoc`, `doc/development/declared-limit-assertion-coverage.adoc`
- HYPOTHESIS: `doc/configuration.adoc` — if deliverable 3(a) changes what is said about `circuit_breaker.reset_ms` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` and `api-sheriff/src/main/resources/schema/endpoint.schema.json` — ONLY if deliverable 3(a) resolves to removing the unread key; a decision to wire it leaves this plan. Declared because PLAN-25 touched `GatewayEdgeRoute` from a spec that declared no main source (verify-at-outline)
- HYPOTHESIS: `.github/workflows/integration-tests.yml` — two more suites lengthen the native integration job; PLAN-25 had to raise its limit (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-25 — landed (#408), and #427, which moved the pending-login flood into the suite.
- Overlaps with: PLAN-24 and PLAN-21 on the IT directory and `integration-tests/src/main/docker/`;
  PLAN-20, PLAN-24 and PLAN-26 on `doc/adr/`. Sequence it against whichever of them is in flight.
- ⚠ **Check the native integration job's duration against its limit before adding suites.** PLAN-25 and a
  sibling plan were each green alone and exceeded the limit once combined.
- **If a test fails against shipped behaviour, STOP and file a finding** to the epic inbox rather than
  fixing production code here.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-27-integration-test-evidence-follow-ups.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
