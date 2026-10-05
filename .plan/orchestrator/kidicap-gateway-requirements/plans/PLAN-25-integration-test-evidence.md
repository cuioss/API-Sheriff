# PLAN-25: Pin the Properties the Downstream Documents but Cannot Prove

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-25-integration-test-evidence.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-10-02 from inbox message `kidicap-gateway-downstream-012.md`, by operator decision.

## Objective

The downstream documents only what it can prove: every statement it makes to its operators points at one
of its own Helm integration tests or at a measurement record, and anything it cannot reproduce is listed
as an **unproven property**. Ten such properties are specified in API Sheriff at unit level only — the
behaviour is asserted against a class, never against a running gateway — so the downstream is telling its
operators things no end-to-end test holds to.

This plan converts those into integration tests (plus one load test), so the gateway's documented
behaviour is pinned where it is actually consumed. It writes **tests only**: a property that turns out to
be false is a finding for a separate plan, not a fix absorbed here.

## Source

Inbox `kidicap-gateway-downstream-012.md` (finding, 2026-10-02), filed against 0.2.3 after the downstream
searched this repository at tag 0.2.3 for an IT or load test covering each property.

**Six properties are already pinned, and the filing lists them so they are not deleted unnoticed** — no
work, but this spec records them because a future refactor that removes one would silently un-prove a
downstream claim:

| Downstream id | Property | Pinned by |
|---|---|---|
| M-48 | an IdP reachable only after gateway start is picked up without restart | `JwksLateIdpReadinessIT.lateIdentityProviderTurnsReadinessUpAndItsTokensAreAccepted` |
| M-49 | back-channel logout ends the server-mode session | `BffBackchannelLogoutIT.idpInitiatedBackchannelLogoutDestroysTheGatewayHeldSession` |
| M-53 | an anchor `security_headers` block replaces the global block wholesale | `RoutingAndResponseHeadersIT.SecurityHeaders.proxyResponseCarriesAnchorBlockWholesale` |
| M-58 | a login requests `oidc.scopes` ∪ `endpoint.scopes` | `BffEndpointScopesIT.navigationOnScopedRouteRequestsTheUnitedScopeSet`, `.loginInitiationForScopedTargetRequestsTheUnitedScopeSet` |
| M-47 | a terminal refresh failure (`invalid_grant`) ends the session | `BffTokenRefreshIT.failedOutcomeRejectsXhrAndClearsTheSession`, `.failedOutcomeRedirectsNavigationAndClearsTheSession` |
| M-56 | `upstream_verify_hostname: false` relaxes only the name check; an untrusted chain is still refused | `UpstreamHostnameVerificationIT.verifyOffInstanceStillRefusesAnUntrustedChain` |

## Deliverables

> ⚠ **RE-GROUNDED 2026-10-05 at `35f2bb37`. Read this before the list — three rows moved.** The filing
> measured this repository at tag 0.2.3; five commits have landed since, two of which touched exactly this
> surface (`e8db85bf` PAR/DPoP, `35f2bb37` which removed 102 lines from `integration-tests/docker-compose.yml`).
>
> | Row | State at HEAD | Consequence |
> |---|---|---|
> | **D10** | ⛔ **OBSOLETE** | The `BffRefreshReuseIT` javadoc is already accurate — it says only `refresh-client` and `cookie-refresh-client` lack a `backchannel.logout.url`, and `integration-realm.json:60` confirms `integration-client` registers it. Nothing to correct; drop the deliverable |
> | **D5 (M-50, M-52)** | ⛔ **BLOCKED AS FILED** | `35f2bb37` added `ItProfileConfigBindingWiringTest`, which requires every OIDC descriptor to name `oidc_tls_profile: benchmark-idp` and **forbids** declaring `oidc_verify_hostname`, and bans `-Djavax.net.ssl.*` arguments at every launch site. So the TLS-relaxation fixtures have no trust route against the self-signed Keycloak. Either amend that guard (a deliberate decision, not a test detail) or re-design the scenario. **M-59 in a bearer-only JWKS form stays feasible** |
> | **M-58 pins** | renamed | `e8db85bf` renamed both to `navigationOnScopedRouteIsGrantedTheUnitedScopeSet` and `loginInitiationForScopedTargetIsGrantedTheEndpointScope`, because PAR hides the scope from the authorization URL — so those pins are now effect-only. Cite the new names |
> | **D3 (M-44)** | still open, and **harder** | PLAN-23's `BffSessionScopeParityIT` does NOT cover it: its own javadoc (93-99) states the in-grant scope-driven refresh cannot be produced through the public surface (`A == S` after login and widening) and its probe hits Keycloak directly. A recording stub IdP is needed |
> | **D4 (M-60)** | still open, needs **new** fixture | toxiproxy 2.12.0 is in the compose stack (admin 8474) but has no static proxies and only `PassthroughFaultIT` uses it; nothing fronts Keycloak and the issuer is pinned to `https://keycloak:8443`. So "toxiproxy needs no new infrastructure" was too optimistic — a new IdP fault fixture is required |
> | **D7** | still open, needs **new** instance | no fixture sets `ttl_seconds` below 3600 |
> | **D8** | feasible as filed | `api-sheriff-refresh` publishes `10452:8443` in server/memory mode and the primary 10443 is also server/memory |
> | D1, D2, D6, D9 | unchanged | D6 additionally still has no 504 edge test, and since `5ddf8081` a framing rejection retires the HTTP/1.x connection or ends the h2 stream — pin that answer |
>
> Net: **8 deliverables live** (D1, D2, D3, D4, D6, D7, D8, D9), one obsolete (D10), one partly blocked (D5).


Each row is one deliverable: an integration test (or load test) at the stated level, plus whatever fixture
it needs.

1. **M-40 — reserved `/auth` paths win over an overlapping route, and the stage-1 security floor applies to
   them.** Today: unit only (`GatewayEdgeRouteBffWiringTest.StructuralReservedPathBypass`); **no IT fixture
   declares a route overlapping `/auth`** and no test sends a floor-violating request to a reserved path.
   Needs both halves. ⚠ The filing also corrects its own earlier wording: at 0.2.3 only the stage-1 floor
   precedes reserved-path dispatch — `verbGateStage` and `thoroughChecksStage` run after route selection
   and never see a reserved path (`GatewayEdgeRoute.process()`, ADR-0019). Pin what is true, not the
   downstream's retired wording.
2. **M-42 — concurrent requests on an expired access token cause ONE refresh call at the IdP.** Unit only
   (`TokenRefreshCoordinatorTest.SingleFlight`); `BffTokenRefreshIT` and `BffRefreshReuseIT` explicitly
   disclaim it. This one carries real consequence: with strict refresh-token rotation at the IdP — which
   the downstream recommends to every operator — a missed coalescing ends the session.
3. **M-44 — the refresh grant sends `scope=` with the session's active set.** Today effect-only
   (`BffTokenRefreshIT.refreshedOutcomeKeepsTheEndpointScope` cannot distinguish `scope=A` from no
   `scope`); the wire parameter is unit only. Wanted: an IT that narrows the active set and observes the
   refreshed token. ⚠ **Check PLAN-23 first** (#369 shipped the scope-driven refresh leg and
   `BffSessionScopeParityIT`) — if that suite already observes the wire parameter, close this deliverable
   as already-covered with the test named, rather than writing a second one.
4. **M-60 — a transient refresh failure (IdP unreachable, 5xx) keeps the session and is retried after the
   back-off.** Unit only (`TokenRefreshCoordinatorTest.PreRedemption`); toxiproxy is already in the
   compose stack, so the fault injection needs no new infrastructure.
5. **M-50 / M-52 / M-59 — the TLS-relaxation matrix.** M-50: with only one of `jwks_verify_hostname` /
   `oidc_verify_hostname` relaxed, token validation works and the BFF login fails (each half unit only,
   the combination untested, and **no IT fixture sets either key**). M-52: the full login round trip
   (discovery, code exchange, callback, refresh) against an IdP whose certificate does not name the
   dialled host, both keys `false` (no test at all; unit covers the discovery leg only). M-59: both keys
   `false` still refuse an untrusted chain (unit only). One fixture family, three assertions.
6. **M-51 — gateway-generated `503` and `504` render as the HTML error page for `Accept: text/html`.**
   Today `HtmlErrorPageIT.unreachableUpstream` accepts `502 || 503 || 504` and the fixture yields `502`,
   so neither of the two statuses the downstream documents is actually pinned; `503` (open circuit) is
   unit only and `504` (upstream timeout) has no edge-level test. toxiproxy can produce the timeout.
   ⚠ Narrow that disjunction while you are here — an assertion accepting three statuses pins none of them.
   This also closes the known PLAN-16 gap "no unit test covers the 504 upstream-timeout error page".
7. **M-54 — a server-mode session ends absolutely after `session.ttl_seconds`, and a refresh does not
   extend it.** `BffCookieRefreshIT.theResealDoesNotExtendTheSession` covers cookie mode (`Max-Age`) only;
   the absolute end is unit only. Needs an instance with a short TTL.
8. **M-55 — with `session.store: memory`, a second instance does not accept the first instance's session
   cookie.** Documented only; `BffCookieStatelessnessIT` proves the cookie-mode alternative. One call of a
   10443 session against 10452 pins it.
9. **M-41 — pending logins are bounded (10 000, hard-coded) and the oldest is evicted at the limit**, as a
   **load test**. ⚠ This is the same flood as `kidicap-gateway-downstream-007`, which **PLAN-21 owns**.
   Carry it here ONLY if PLAN-21 has not landed when this plan reaches outline; otherwise drop it and name
   PLAN-21's test. Do not write a second flood test.
10. **Correct the two documentation inaccuracies the filing found on the way** (no behaviour change): the
    `BffRefreshReuseIT` class javadoc says no client registers a `backchannel.logout.url`, while the realm
    file and `BffBackchannelLogoutIT` at the same tag say otherwise.

Split guard: 8 live deliverables (10 filed, D10 obsolete, D5 partly blocked) — within the operator-authorized 12. Deliverables 3 and 9 may resolve to
"already covered elsewhere", which SHRINKS the plan; that is an expected outcome, not a shortfall.

## Claim Labels

- OBSERVED: the filing is a per-property audit against this repository at tag 0.2.3, naming for each
  property the exact existing test (or its absence) — it is evidence-bearing rather than a wish list, and
  the orchestrator accepted it on that basis.
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: the named tests exist at HEAD; five of the six pinned ITs are unchanged - only the two M-58 methods were renamed by e8db85bf
- HYPOTHESIS: every per-row state above ("unit only", "no fixture sets either key", "accepts
  `502 || 503 || 504`") still holds at HEAD. Measured by the downstream at **0.2.3**, and six plans have
  landed since — confirm/refute at each named test class before writing anything, e.g.
  `integration-tests/src/test/java/…/HtmlErrorPageIT.java` § `unreachableUpstream` for the status
  disjunction (verify-at-outline). ⛔ A row that turns out already covered is CLOSED with the covering
  test named, never re-tested.
  - verdict: contradicted | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: not every cited state holds at HEAD: M-58 renamed; the BffRefreshReuseIT javadoc is already accurate (D10 obsolete); 35f2bb37 guards now block the M-50/M-52 fixtures
- HYPOTHESIS: toxiproxy in the existing compose stack can produce both the 5xx of deliverable 4 and the
  upstream timeout of deliverable 6 — confirm/refute at `integration-tests/src/main/docker/` § the
  toxiproxy service and its current use (verify-at-outline).
  - verdict: unverifiable | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: toxiproxy 2.12.0 is at compose:87 with admin 8474 but only PassthroughFaultIT uses it and nothing fronts Keycloak; the IdP leg is pinned to keycloak:8443 so feasibility is unproven
- HYPOTHESIS: a second gateway instance on 10452 already exists in the compose stack for deliverable 8
  (the filing names the ports) — confirm/refute at `integration-tests/src/main/docker/` § the instance
  definitions (verify-at-outline).
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: api-sheriff-refresh publishes 10452:8443 (docker-compose.yml:1373) in server/memory mode and the primary 10443 is also server/memory

## Expected Surface

- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — the new and
  amended ITs. Declared as the directory deliberately: this plan's whole subject is that directory, and a
  per-file list would be guesswork before the outline resolves which rows are still open.
- OBSERVED (added 2026-10-05): `integration-tests/docker-compose.yml` — the instance map, published ports
  and any new short-TTL service live HERE, not under `src/main/docker/`; `35f2bb37` removed 102 lines from
  it (every one a `-Djavax.net.ssl.trustStore` block, on 11 services)
- OBSERVED (added 2026-10-05): `doc/development/integration-test-topology.adoc` (+351 lines by `35f2bb37`)
  and `integration-tests/scripts/start-integration-container.sh`, which derives readiness probes from the
  compose services — so any new instance touches both
- OBSERVED (added 2026-10-05): `integration-tests/src/test/java/.../ItProfileConfigBindingWiringTest.java`
  — inside the declared directory but a NEW constraint, and the thing that blocks D5's M-50/M-52 fixtures
- OBSERVED: `integration-tests/src/main/docker/` — fixtures: a route overlapping `/auth`, the TLS
  relaxation keys, a short-TTL instance, toxiproxy wiring
- HYPOTHESIS: `benchmarks/src/main/resources/k6-scripts/` — the load test of deliverable 9, if it is
  carried here rather than left to PLAN-21 (verify-at-outline)
- OBSERVED: `doc/quality-report/` — this epic's test-quality record, where the newly pinned properties
  belong
- ⛔ **No `api-sheriff/src/main/**` entry, deliberately.** This plan writes tests. A property that proves
  false is filed as a finding for its own plan — see Dependencies.

## Dependencies and Sequencing

- Depends on: nothing. Every property is already shipped behaviour.
- **Near-disjoint from every WS-04 spec**: no main-source surface at all, so it is a strong second-slot
  candidate alongside PLAN-20 or PLAN-21. It shares only `integration-tests/` with them.
- ⚠ Overlaps PLAN-21 on deliverable 9 and PLAN-23 (landed) on deliverable 3 — both resolved by the
  check-first instructions above rather than by sequencing.
- **If a test fails against shipped behaviour, STOP and file a finding** to the epic inbox rather than
  fixing production code here. A tests-only plan that quietly becomes a fix plan is how a 10-deliverable
  spec turns into an unbounded one.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-25-integration-test-evidence.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
