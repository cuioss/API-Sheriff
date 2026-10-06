# PLAN-V02-05: Response Hygiene & Fingerprint Reduction

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

Reduce the gateway's external fingerprintability. Our error bodies are already clean (minimal RFC 7807,
no stack traces) and Vert.x/Quarkus emit no `Server` header by default — but the response relay forwards
**upstream** `Server` / `X-Powered-By` headers straight through, leaking backend stack identity, and the
security response headers are opt-in rather than default-on. This plan strips/normalizes
identity-leaking response headers at the relay, makes the baseline security headers default-on, audits
for version leakage, and documents the TLS/HTTP-fingerprint concern as a fronting-CDN deployment matter.

**The leak is a verified defect in every released version, not a missing feature**: an adopter's
backend stack identity is exposed today. D1 and D2 are small and are the high-value part of this plan.

## Deliverables

1. **Strip/normalize identity-leaking response headers at the relay**: `Server`, `X-Powered-By`, and
   other framework-identifying upstream response headers are removed (or overridden to a neutral value)
   before the client sees them. **Stated as its own line item** — this is the confirmed leak.

   **There are two relay paths, and both must be covered.** `ResponseStage.relay()` and
   `ResponseStage.relayWithTrailers()` (the gRPC/trailers path) each apply
   `isForwardableResponseHeader`, which filters only hop-by-hop headers and the conditional set. A
   strip applied to one call site still leaks on every trailers-carrying response. Fix the shared
   filter — or the shared response-direction policy `ConnectionHeaders.RESPONSE_STRIP` that both
   paths read, which is the more natural home — and prove both paths with a test each.
2. **Security response headers default-on**: HSTS / `X-Content-Type-Options: nosniff` /
   `X-Frame-Options` apply by a secure default posture, remaining per-config overridable (do not force
   them where a route legitimately opts out). Today `SecurityHeadersStage` emits each only when the
   `security_headers` block enables it.
3. **Version / identity leak audit**: confirm no gateway/framework version string appears in any header
   or body; the RFC 7807 error shape stays identical across 4xx/5xx.
4. **Deployment documentation** for the out-of-scope-in-process fingerprints: TLS JA3/JA4 and
   HTTP/2-SETTINGS fingerprints are JDK/Netty-inherent — document that a fronting CDN/LB is the
   mitigation, so operators understand the boundary of what the gateway controls.
5. **Close the forward-all / `not_modified` asymmetry.**

   **Scope note first, because it is why this sits here and not in a forward-policy plan**: this is a
   *response-path coherence* defect — the route emits a response that contradicts its own declared
   posture — which is this plan's subject even though the *cause* is on the request side. If the
   outline concludes it belongs elsewhere, say so and move it; do not silently drop it.

   `ResponseStage.isForwardableResponseHeader` returns
   `notModifiedEnabled || !CONDITIONAL_RESPONSE_HEADERS.contains(...)`, so with `not_modified: false`
   the `ETag` is **stripped from the response** — while a forward-all or negative-list route's mode
   copy carries `If-None-Match` **upstream regardless of that toggle**. The `not_modified` gate
   governs only the protocol set's admission path, so it never reaches the mode copy.
   **The route forwards the precondition and discards the answer.**

   **Severity LOW, coherence not correctness.** A client that sent `If-None-Match` and receives a
   `304` still holds its cached copy; what it loses is the refreshed validator. The real defect is
   that a route declaring *"this route does not do conditional requests"* honours that on the
   response side and silently violates it on the request side.

   **Do not treat the existing test as a bug to fix.**
   `ForwardPolicyStageTest.forwardAllCarriesValidatorsPastTheToggle` **deliberately pins the
   current behaviour** so that changing it is loud. Closing this defect means changing that test on
   purpose, with the reason recorded — not discovering it as a failure.

   **Closing it changes forward-all semantics.** Decide the direction explicitly — either the
   toggle also suppresses the request-side validators, or the toggle is re-scoped to mean
   *response-side only* and documented as such — and record which, with an ADR if the semantics move.
6. **Architecture + three-layer documentation.** Note the response-header normalization at the relay in
   `doc/architecture.adoc` (a small structural addition to the response path); an ADR is warranted only
   if the security-headers-default-on posture is deemed a policy decision — decide at outline, default
   to no new ADR. Plus the three-layer docs: `configuration.adoc` (the header posture + defaults),
   `doc/user/`, `doc/development/`, and the TLS/CDN deployment note from deliverable 4. Write against
   the landed layout: read the current `doc/user/` inventory and prefer the page that already owns
   the subject over adding a section back into `configuration.adoc`.

## Claim Labels

- OBSERVED (confirmed leak): `edge/ResponseStage.java` `isForwardableResponseHeader` filters ONLY
  hop-by-hop and conditional headers — it does **not** strip `Server`/`X-Powered-By`, so an upstream
  emitting them leaks them through; `relay()` and `relayWithTrailers()` both copy every forwardable
  upstream header through that one filter.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ResponseStage.isForwardableResponseHeader drops only ConnectionHeaders.RESPONSE_STRIP and etag/last-modified; no Server or X-Powered-By handling in main; relay and relayWithTrailers share the filter
- OBSERVED: security headers are opt-in today — `pipeline/SecurityHeadersStage.java` emits HSTS /
  nosniff / frame-deny only when the `security_headers` block enables each.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SecurityHeadersStage.OwnedHeader emits HSTS, nosniff and frame DENY only when the security_headers block enables each; portal responses force CSP and nosniff
- OBSERVED: error bodies are already clean — `edge/GatewayEdgeRoute.java` `renderProblem` emits a
  minimal `{"type","title","status"}` with generic `EventCategory` titles, no stack/framework signature.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.renderProblem/problemBody emit type, title, status plus extension members only from a GatewayException; HTML page only for Accept text/html; no stack or framework text
- HYPOTHESIS: the gateway itself emits no `Server`/`X-Powered-By` by default (Vert.x/Quarkus default;
  no `quarkus.http.server-header` property exists — override idiom is `quarkus.http.header."Server".value`).
  Confirm/refute at outline with a live `curl -I` against the running native gateway (verify-at-outline)
  — do not build self-header suppression for a header we do not emit; the real work is the *upstream*
  passthrough strip.
  - verdict: unverifiable | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: needs a live curl -I against the native gateway; statically no quarkus.http.header or server-header property exists (control: application.properties carries 21 quarkus.http keys)
- Verify-first clause: enumerate the actual set of identity-leaking headers an upstream can send (the IT
  stack: Keycloak, go-httpbin) and confirm which pass through today before fixing the allowlist.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural clause still accurate: the IT stack runs Keycloak and go-httpbin 2.23.1 and RESPONSE_STRIP is a deny list, so which identity headers pass is still unenumerated

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` — the response-header forward filter
  (add the identity-strip); both `relay()` and `relayWithTrailers()` share this one filter
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` (`RESPONSE_STRIP`) — the shared
  response-direction policy both relay paths read
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java` + `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityHeadersConfig.java` — default-on posture
- HYPOTHESIS: `api-sheriff/src/main/resources/application.properties` — a `quarkus.http.header` override only if the live check shows a self-emitted header (verify-at-outline)
- OBSERVED: `doc/configuration.adoc`, `doc/user/`, `doc/development/`, `doc/architecture.adoc` — the doc layers incl.
  the TLS/CDN deployment note
- OBSERVED: `api-sheriff/src/test/**` — the header tests

## Dependencies and Sequencing

- Depends on: nothing functionally. Independent of the `PLAN-V02-13` → `PLAN-V02-06` → `PLAN-V02-07`
  chain.
- Overlaps with: `ResponseStage` and the edge. It may pair with `PLAN-V02-06` if the surfaces prove
  disjoint (this plan is the response relay and headers; that one is the route-miss request path) —
  the disjointness gate decides at emit time.
- Adjacent to: the inbound security-filter work — a different control (inbound filtering vs response
  hygiene).

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan (epic-wide).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-05-response-hygiene.md" plan_id=plan-v02-05-response-hygiene
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
