envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-02T12:05:00Z

## Request: integration-test evidence for behaviour the downstream relies on but cannot measure itself

Filed by the downstream deployment `kidicap-gateway` on API Sheriff 0.2.3. The downstream documents only
what is proven: every statement about the gateway's behaviour points at a check of its own Helm integration
test or at a measurement record. What it cannot reproduce in its own test stack is listed as an "unproven
property" (`doc/protokolle/messprotokoll.adoc`, section "Unbelegte Eigenschaften"). For those properties
the downstream looked for an **integration test** (running gateway, `integration-tests/…/*IT.java`) or a
load test in this repository at tag `0.2.3`.

Proven by an upstream IT and now cited by the downstream — no action needed, listed so the tests are not
removed unnoticed:

| Downstream id | Property | Upstream IT at 0.2.3 |
|---|---|---|
| M-48 | an IdP that becomes reachable after gateway start is picked up without restart | `JwksLateIdpReadinessIT.lateIdentityProviderTurnsReadinessUpAndItsTokensAreAccepted` |
| M-49 | back-channel logout ends the server-mode session | `BffBackchannelLogoutIT.idpInitiatedBackchannelLogoutDestroysTheGatewayHeldSession` |
| M-53 | an anchor `security_headers` block replaces the global block wholesale | `RoutingAndResponseHeadersIT.SecurityHeaders.proxyResponseCarriesAnchorBlockWholesale` |
| M-58 | a login via a route requests `oidc.scopes` ∪ `endpoint.scopes` | `BffEndpointScopesIT.navigationOnScopedRouteRequestsTheUnitedScopeSet`, `.loginInitiationForScopedTargetRequestsTheUnitedScopeSet` |
| M-47 | a terminal refresh failure (`invalid_grant`) ends the session | `BffTokenRefreshIT.failedOutcomeRejectsXhrAndClearsTheSession`, `.failedOutcomeRedirectsNavigationAndClearsTheSession` |
| M-56 | `upstream_verify_hostname: false` relaxes only the name check, an untrusted chain is still refused | `UpstreamHostnameVerificationIT.verifyOffInstanceStillRefusesAnUntrustedChain` |

**Requested: an integration test** (or, where noted, a load test) for each of the following. All are
specified at unit level only or not at all; the downstream states them in its operator documentation and
needs them pinned against the running gateway.

| Downstream id | Property | State at 0.2.3 |
|---|---|---|
| M-40 | reserved `/auth` paths win over a route declared on an overlapping prefix; the stage-1 security floor (path/header limits) applies to reserved paths | unit only (`GatewayEdgeRouteBffWiringTest.StructuralReservedPathBypass`); no IT fixture declares a route overlapping `/auth`; no test sends a floor-violating request to a reserved path |
| M-41 | pending logins are bounded (10 000, hard-coded), the oldest is evicted at the limit | unit only (`PendingAuthorizationStoreTest.shouldEvictOldestBeyondCapacity`). Wanted as a **load test** — this is the flood of `kidicap-gateway-downstream-007` (PLAN-21) |
| M-42 | concurrent requests on an expired access token cause one refresh call at the IdP | unit only (`TokenRefreshCoordinatorTest.SingleFlight`); `BffTokenRefreshIT` and `BffRefreshReuseIT` disclaim it. With strict refresh-token rotation at the IdP a missed coalescing ends the session — the downstream recommends that rotation to every operator |
| M-44 | the refresh grant sends `scope=` with the session's active scope set | effect only (`BffTokenRefreshIT.refreshedOutcomeKeepsTheEndpointScope` cannot tell `scope=A` from no `scope`); the wire parameter is unit only. Wanted: an IT that narrows the active set and observes the refreshed token |
| M-60 | a transient refresh failure (IdP unreachable, 5xx) keeps the session and is retried after the back-off | unit only (`TokenRefreshCoordinatorTest.PreRedemption`); toxiproxy is already in the compose stack |
| M-50 | with only one of `jwks_verify_hostname` / `oidc_verify_hostname` relaxed, token validation works and the BFF login fails | each half unit only, the combination untested; no IT fixture sets either key |
| M-52 | the complete BFF login round trip (discovery, code exchange, callback, refresh) over an IdP whose certificate does not name the dialled host, both keys `false` | no test; unit covers the discovery leg only |
| M-59 | `jwks_verify_hostname: false` / `oidc_verify_hostname: false` still refuse an untrusted chain | unit only (`TokenValidatorProducerTest.JwksVerifyHostname`, `BffRuntimeProducerTest.OidcBackChannelTls`) |
| M-51 | gateway-generated `503` and `504` render as the HTML error page for `Accept: text/html` | `HtmlErrorPageIT.unreachableUpstream` accepts `502 || 503 || 504` and the fixture yields `502`; `503` (open circuit) is unit only, `504` (upstream timeout) has no edge-level test. toxiproxy can produce the timeout |
| M-54 | a server-mode session ends absolutely after `session.ttl_seconds`; a refresh does not extend it | `BffCookieRefreshIT.theResealDoesNotExtendTheSession` covers cookie mode (`Max-Age`) only; the absolute end is unit only. Wanted: an instance with a short TTL |
| M-55 | with `session.store: memory` a second instance does not accept the first instance's session cookie | documented only; `BffCookieStatelessnessIT` proves the cookie-mode alternative. One call of a 10443 session against 10452 would pin it |

Two observations made on the way, no request:

* The downstream's wording for M-40 said "the basic checks (allowed method, filter) run before the reserved
  paths". At 0.2.3 only the stage-1 floor does; `verbGateStage` and `thoroughChecksStage` run after route
  selection and never see a reserved path (`GatewayEdgeRoute.process()`, ADR-0019). The downstream has
  corrected its documentation.
* The class javadoc of `BffRefreshReuseIT` says no client registers a `backchannel.logout.url`; the realm
  file and `BffBackchannelLogoutIT` at the same tag say otherwise.

Not requested here: M-43 (a session route relays an under-scoped token) is the defect of
`kidicap-gateway-downstream-010` / PLAN-23; its IT comes with the fix.
