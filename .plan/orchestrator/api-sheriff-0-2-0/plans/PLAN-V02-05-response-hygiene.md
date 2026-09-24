# PLAN-V02-05: Response Hygiene & Fingerprint Reduction

epic: api-sheriff-0-2-0
workstream: WS-03
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> NEW 2026-07-25 from operator requirements intake + prior-art research.
> ~~Gated POST-first-landing.~~ **That gate EXPIRED** — PLAN-05 and PLAN-06 both shipped.
>
> **⚠ THE ORCHESTRATOR RECOMMENDED THIS FOR THE 0.1.0 RELEASE AND WAS OVERRULED (2026-07-27).**
> The concern is not withdrawn and is carried as an open defect in this epic: `ResponseStage`:64
> forwards upstream `Server` / `X-Powered-By` straight through, so **an adopter running 0.1.0 leaks
> their backend's stack identity** — a verified defect in what the gateway already ships, not a
> missing feature. D1 and D2 are small. If the release schedule ever allows a cheap addition, this
> is the highest-value one available.

> **Renumbered 2026-08-04.** This spec was `PLAN-17-response-hygiene.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**THE LEAK IS CONFIRMED AND STILL SHIPS.** `ResponseStage.isForwardableResponseHeader` (now at
**:63**, was `:64`) filters hop-by-hop headers and — at **:71** — the conditional set
(`CONDITIONAL_RESPONSE_HEADERS = Set.of("etag", "last-modified")` at :56). There is **no `Server` or
`X-Powered-By` handling anywhere in the file**. Open Defect (1) holds unchanged; 0.1.0 and 0.1.1
both shipped with it.

**D1 HAS TWO RELAY PATHS, NOT ONE — THIS IS THE FINDING OF THIS RE-GROUNDING.** The spec names
`relay` (now at **:84**, filter applied at :92; the spec's `:89-93` has moved). There is a **second,
independent relay path the spec never mentions**: `relayWithTrailers` at **:118**, applying the same
`isForwardableResponseHeader` filter at **:126**. That is the gRPC/trailers path. **A strip applied
only to `relay` leaks `Server`/`X-Powered-By` on every trailers-carrying response.** Fix the filter
itself, or fix both call sites and prove both — do not fix one and call the deliverable done. This is
the epic's standing *enumerate-every-carrier* rule (lesson `2026-08-06-08-002`) applying to this
plan.

**D5 IS EXACTLY ACCURATE.** `isForwardableResponseHeader`:71 returns
`notModifiedEnabled || !CONDITIONAL_RESPONSE_HEADERS.contains(…)` verbatim as corroborated at
`ce644d7`. The line has not moved. `ForwardPolicyStageTest.forwardAllCarriesValidatorsPastTheToggle`
still pins the current behaviour — re-check its line number, but the pinning premise stands.

**MOVED — D2.** `SecurityHeadersStage` emits at **:83** (HSTS), **:86** (`X-Content-Type-Options:
nosniff`) and **:89** (`X-Frame-Options: DENY`), not `:68-81`. Each is still emitted **only when the
`security_headers` block enables it**, so the opt-in premise — the thing D2 changes — holds.

**RENUMBERING.** "Pairable with PLAN-18" means **PLAN-V02-06**; "Adjacent to PLAN-15" and "PLAN-05
SHIPPED" refer to landed `api-sheriff-roadmap` work — read the diffs, do not wait.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**ALL JAVA ANCHORS HELD.** Re-verified at `95dd566`: `isForwardableResponseHeader`:63, the
conditional return :71, `relay`:84 with its filter at :92, and **`relayWithTrailers`:118 with the
same filter at :126**. The two-relay-path finding stands unchanged and is still the sharpest thing in
this spec — a strip applied to one path still leaks on every trailers-carrying response.
`SecurityHeadersStage` still emits at :83 / :86 / :89, still opt-in.

**THE DOC SURFACE MOVED UNDER THIS PLAN.** `PLAN-V02-03` cut `doc/configuration.adoc` from 2539 to
**2119 lines**, moving sections 6–8 into the operator layer, and `doc/user/` is now **ten** pages
including two new ones — `anchors.adoc` and `endpoint-routes.adoc`. D6's three-layer documentation
must be written against the **landed** layout: read the current `doc/user/` inventory rather than
this spec's paths, and prefer the page that already owns the subject to adding a section back into
`configuration.adoc`.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

All premises hold at HEAD. The leak still ships at both relay paths (`ResponseStage.relay()` now `:100`, `relayWithTrailers()` `:172`, `isForwardableResponseHeader` `:74`). Security headers are still opt-in. The `ForwardPolicyStageTest.forwardAllCarriesValidatorsPastTheToggle` pin drifted to `:688`. `GatewayEdgeRoute` grew +276 lines for portal HTML error pages (#343), not for the problem+json body shape. No deliverable discharged.

## Objective

Reduce the gateway's external fingerprintability. Our error bodies are already clean (minimal RFC 7807,
no stack traces) and Vert.x/Quarkus emit no `Server` header by default — but the response relay forwards
**upstream** `Server` / `X-Powered-By` headers straight through, leaking backend stack identity, and the
security response headers are opt-in rather than default-on. This plan strips/normalizes
identity-leaking response headers at the relay, makes the baseline security headers default-on, audits
for version leakage, and documents the TLS/HTTP-fingerprint concern as a fronting-CDN deployment matter.

## Deliverables

1. **Strip/normalize identity-leaking response headers at the relay**: `Server`, `X-Powered-By`, and
   other framework-identifying upstream response headers are removed (or overridden to a neutral value)
   before the client sees them. **Stated as its own line item** — this is the confirmed leak.
2. **Security response headers default-on**: HSTS / `X-Content-Type-Options: nosniff` /
   `X-Frame-Options` apply by a secure default posture, remaining per-config overridable (do not force
   them where a route legitimately opts out).
3. **Version / identity leak audit**: confirm no gateway/framework version string appears in any header
   or body; the RFC 7807 error shape stays identical across 4xx/5xx.
4. **Deployment documentation** for the out-of-scope-in-process fingerprints: TLS JA3/JA4 and
   HTTP/2-SETTINGS fingerprints are JDK/Netty-inherent — document that a fronting CDN/LB is the
   mitigation, so operators understand the boundary of what the gateway controls.
5. **Close the forward-all / `not_modified` asymmetry — re-homed from `api-sheriff-roadmap` Open
   Defect (53), opened by PLAN-52 and DELIBERATELY left open. 2026-08-08.**

   **Scope note first, because it is why this sits here and not in a forward-policy plan**: this is a
   *response-path coherence* defect — the route emits a response that contradicts its own declared
   posture — which is this plan's subject even though the *cause* is on the request side. If the
   outline concludes it belongs elsewhere, say so and move it; do not silently drop it.

   Corroborated first-party at `ce644d7`: `ResponseStage.isForwardableResponseHeader`:71 returns
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
   `ForwardPolicyStageTest.forwardAllCarriesValidatorsPastTheToggle` (`:661`) **deliberately pins the
   current behaviour** so that changing it is loud. Closing this defect means changing that test on
   purpose, with the reason recorded — not discovering it as a failure.

   **Closing it changes forward-all semantics**, which is why PLAN-52 refused to do it unilaterally
   inside a deliverable that had not declared that edit. Decide the direction explicitly — either the
   toggle also suppresses the request-side validators, or the toggle is re-scoped to mean
   *response-side only* and documented as such — and record which, with an ADR if the semantics move.
6. **Architecture + three-layer documentation.** Note the response-header normalization at the relay in
   `doc/architecture.adoc` (a small structural addition to the response path); an ADR is warranted only
   if the security-headers-default-on posture is deemed a policy decision — decide at outline, default
   to no new ADR. Plus the three-layer docs: `configuration.adoc` (the header posture + defaults),
   `doc/user/`, `doc/development/`, and the TLS/CDN deployment note from deliverable 4.

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25.

- OBSERVED (confirmed leak): `edge/ResponseStage.java`:64 `isForwardableResponseHeader` filters ONLY
  hop-by-hop and conditional headers — it does **not** strip `Server`/`X-Powered-By`, so an upstream
  emitting them leaks them through (`relay` at :89-93 copies every forwardable upstream header).
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ResponseStage isForwardableResponseHeader :74, relay() :100, relayWithTrailers() :172; no Server/X-Powered-By strip anywhere; ConnectionHeaders RESPONSE_STRIP :78 unchanged
- OBSERVED: security headers are opt-in today — `pipeline/SecurityHeadersStage.java`:68-81 emits HSTS /
  nosniff / frame-deny only when the `security_headers` block enables each.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SecurityHeadersStage OwnedHeader HSTS/NOSNIFF/FRAME_OPTIONS each still gated by the security_headers block
- OBSERVED: error bodies are already clean — `edge/GatewayEdgeRoute.java` `renderProblem` emits a
  minimal `{"type","title","status"}` with generic `EventCategory` titles, no stack/framework signature.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: renderProblem problem+json body shape not independently re-read; GatewayEdgeRoute +276 lines were portal HTML machinery (#343)
- HYPOTHESIS: the gateway itself emits no `Server`/`X-Powered-By` by default (Vert.x/Quarkus default;
  no `quarkus.http.server-header` property exists — override idiom is `quarkus.http.header."Server".value`).
  Confirm/refute at outline with a live `curl -I` against the running native gateway (verify-at-outline)
  — do not build self-header suppression for a header we do not emit; the real work is the *upstream*
  passthrough strip.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: needs a live curl -I, as the spec itself states
- Verify-first clause: enumerate the actual set of identity-leaking headers an upstream can send (the IT
  stack: Keycloak, go-httpbin) and confirm which pass through today before fixing the allowlist.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural live-IT-stack instruction, not a static claim

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java`:74 (drifted from :64) — the response-header forward filter
  (add the identity-strip); both `relay()` and `relayWithTrailers()` share this one filter
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java`:78-81 (`RESPONSE_STRIP`) — added 2026-09-22: the shared
  response-direction policy both relay paths read; found understated by re-grounding, and the more
  natural home for the Server/X-Powered-By strip than `ResponseStage.java` alone
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java` + `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityHeadersConfig.java` — default-on posture
- HYPOTHESIS: `api-sheriff/src/main/resources/application.properties` — a `quarkus.http.header` override only if the live check shows a self-emitted header (verify-at-outline)
- OBSERVED: `doc/configuration.adoc`, `doc/user/`, `doc/development/`, `doc/architecture.adoc`
  (added 2026-09-22 — Objective deliverable 6 requires it and it was missing) — the doc layers incl.
  the TLS/CDN deployment note
- OBSERVED: `api-sheriff/src/test/**` — the header tests

## Dependencies and Sequencing

- Depends on: nothing functionally. **Independent of the PLAN-18→19→20 chain.**
- **PLAN-05 SHIPPED (#100); PLAN-15 is on the 0.1.0 release track** — re-ground against both landed
  diffs before scoping, rather than treating either as a wait.
- Overlaps with: touches `ResponseStage` / the edge — MAY be pairable with PLAN-18 at emit if
  surfaces prove disjoint (17 = response relay/headers; 18 = route-miss request path) — decide via
  the disjointness check.
- Adjacent to: PLAN-15's security-filter work — a different control (inbound filtering vs response hygiene).

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan (epic-wide).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-05-response-hygiene.md" plan_id=plan-v02-05-response-hygiene
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/local/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-17.md is recorded}
