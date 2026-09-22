# PLAN-V03-02: API Inventory Endpoint (management-plane, authenticated)

epic: api-sheriff-0-3-0
workstream: WS-02
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — ready for `/plan-marshall` hand-off. NEW 2026-07-25 (operator requirements intake
> + API-inventory-format research). AskUserQuestion answers: TLS + offline-JWT-scope auth on the mgmt
> port (reuse infra); full format scope (this plan does native schema + OpenAPI; CycloneDX/RFC 9727 →
> PLAN-22); metrics + usage snapshot.

> **Renumbered 2026-08-04.** This spec was `PLAN-21-api-inventory-endpoint.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Objective

Expose the gateway's inventory of fronted API routes — and their live per-route usage — through an
authenticated, read-only endpoint on the management interface, so external systems (WAFs, API-security
tools) can consume the map the gateway already compiles at boot. The inventory is sensitive (it is the
authorized form of the enumeration map WS-05 hides from attackers), so the endpoint is served over TLS
and gated by **offline JWT validation requiring a dedicated scope**, reusing the shipped bearer-token
infrastructure. Per-route call counts already exist as cardinality-safe metrics; this plan READS them
into a point-in-time usage snapshot rather than adding new counters.

## Deliverables

**Six deliverables.** Tests are not a numbered deliverable — **each of D1–D5 owes its own tests in the
same PR**, per the epic convention: auth (no-token and wrong-scope rejected, valid scope allowed),
schema validity, and usage counts. The architecture/ADR and three-layer documentation items are merged
into a single D6 — they were two numbered items describing one documentation obligation.

1. **Management-interface AUTH hardening — REDUCED 2026-07-27, the TLS half is already done.**
   Gate the endpoint with **offline JWT validation requiring a specific scope** (e.g.
   `gateway:inventory:read`), reusing the shipped `auth/` bearer validation. **Stated as its own line
   item** — the auth-wiring onto the management interface remains the plan's central risk.
   ~~Serve the endpoint over TLS on the management interface~~ — **management is already HTTPS-only**
   (PLAN-23) and a first-class neutral config block (PLAN-31). Verify, do not rebuild.
2. **Inventory read model** over the compiled `RouteTable` — per route: path pattern, methods, access
   tier (public/authenticated), policy anchor, upstream/asset target, protocol, route id.
3. **Native custom JSON Schema** for the inventory (the five gateway fields no standard captures
   together), published and validated.
4. **OpenAPI export** of the inventory (with `x-apisheriff-{upstream,policy,anchor,routeId}` extensions)
   — the universal WAF / API-security ingestion on-ramp (Cloudflare API Shield, AWS WAF import OpenAPI).
5. **Per-route usage snapshot** — the endpoint joins each inventory route with its CURRENT call count
   read from the existing `sheriff_requests_total{route}` meter (no new instrumentation), so a consumer
   gets inventory + usage in one call without scraping Prometheus.
6. **Documentation — architecture, ADR and all three layers, as one named line item.**
   have been silently dropped in this epic before; see the named-line-items watch). Document the NEW
   management-plane introspection architecture in `doc/architecture.adoc`: the endpoint, the
   **management-interface auth model** (TLS + offline-JWT-scope, and how the shipped `auth/` stage
   attaches to the management interface), and the inventory read model + serialization strategy. Record
   an **ADR** (next free number) for the management-plane introspection API + its auth model + the
   inventory-format decision (native schema + OpenAPI; CycloneDX/RFC 9727 in PLAN-22).

   Plus the three-layer set:
   document** (operator 2026-07-25): `configuration.adoc` (the endpoint, the `gateway:inventory:read`
   scope, the management-interface TLS/auth config); a **NEW dedicated `doc/user/` document** (e.g.
   `doc/user/management-endpoints.adoc`) that is the single home for ALL management-plane endpoint
   docs — it covers the inventory endpoint here AND consolidates the existing management-port surface
   (health, metrics) so operators have one page for the whole management plane; and `doc/development/`.
   **Stated so PLAN-22 EXTENDS this same document rather than forking a second management-endpoints page.**

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25.

- **⚠ REFUTED 2026-07-27** (was: "the management interface is PLAIN + unauthenticated,
  `application.properties`:11-13, port 9000 plain HTTP"). **PLAN-23 (PR #114) activated management
  TLS**, and Quarkus' `ManagementConfig` declares no `ssl-port` and no `insecure-requests`, so
  enabling TLS converted port 9000 itself: **management is HTTPS-ONLY**. PLAN-31 then made it a
  first-class neutral config block. What remains true is that it is **unauthenticated** —
  health/metrics live there (`quarkus/GatewayReadinessCheck.java`, `quarkus/SheriffMetrics.java`).
  **Consequence: D1 SHRINKS to the offline-JWT-scope auth alone; the TLS half is already done.**
  Re-scope D1 before launching — do not re-implement management TLS.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: application.properties:143-171 -- mgmt is still HTTPS-only, default+only listener; ManagementConfig.java:20-46 has no auth field, only tls. Still true; D1 stays shrunk to JWT-scope auth alone.
- OBSERVED: the inventory is ready to serialize — `config/model/RouteTable.java` (`List<ResolvedRoute>`)
  and `config/model/ResolvedRoute.java`:79-84 (id, protocol, anchor, match, effectiveAuth,
  effectiveAllowedMethods, effectiveSecurityFilter, upstream, asset).
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: RouteTable.java:41 + ResolvedRoute.java:103-120 (drifted from :79-84) -- every named field present verbatim plus several added since. Cite by content not line.
- OBSERVED: per-route counts already exist, cardinality-safe — `quarkus/SheriffMetrics.java`:38-47,58-70
  (`sheriff_requests_total{route,method,status_family}`, route id is a config-fixed bounded label,
  unmatched share `<no-route>`).
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: SheriffMetrics.java:38(javadoc),62-63(REQUESTS_TOTAL),75(NO_ROUTE),102-104(recordRequest) -- content matches, lines drifted from 38-47/58-70.
- OBSERVED: bearer-token offline validation infra exists — `auth/` (`AuthenticationStage`,
  `TokenValidatorProducer`, `JwksTrustProfileResolver`); scope enforcement already models a 403 via
  `events/EventType.java` `SCOPE_MISSING`.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: auth/AuthenticationStage.java, TokenValidatorProducer.java, JwksTrustProfileResolver.java all present; EventType.java:108 SCOPE_MISSING(AUTHORIZATION,403) exact match.
- HYPOTHESIS (central risk): the shipped bearer validation can be attached to a *management-interface*
  route. Confirm/refute at outline — the Quarkus management interface has its own routing; if the
  `auth/` stage cannot be applied there, the plan wires management-interface-local auth instead
  (verify-at-outline). Do not assume the data-plane pipeline runs on the management port.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: AuthenticationStage wired only into the data-plane pipeline, no management-interface call site found, no JAX-RS resource exists anywhere under quarkus/ today. Risk still genuinely open, correctly deferred to outline.
- Verify-first clause: confirm the management port is genuinely reachable only where intended and decide
  the TLS story (terminate TLS on 9000, or require it be network-isolated) against the actual deployment
  posture, not this spec's assumption.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: compose-sample docker-compose.yml:184 already binds management to 127.0.0.1:9000:9000 loopback-only with a comment naming this exact concern -- deployment-posture evidence exists but the clause is future design work, not a checkable current-state claim.

## Expected Surface

- OBSERVED: `application.properties` — management-interface TLS + auth config
- OBSERVED absence → NEW: an inventory endpoint resource + read model + JSON Schema + OpenAPI serializer
- OBSERVED: `config/model/RouteTable.java` / `ResolvedRoute.java` — read-only source of the inventory
- OBSERVED: `quarkus/SheriffMetrics.java` — read-only source of the usage snapshot
- OBSERVED: `auth/` — reused for the offline-JWT-scope gate (verify-at-outline for mgmt-interface wiring)
- ADDED 2026-09-22 (understated): `config/model/ManagementConfig.java` (currently `record
  ManagementConfig(@Nullable ManagementTls tls)` — the only neutral config block the management
  interface has today, and the natural home for a new management-auth/scope policy knob) and the
  matching `schema/gateway.schema.json` entry for that block
- OBSERVED: `doc/architecture.adoc` (NEW management-plane introspection section) + a NEW `doc/adr/00NN-*`
  ADR; `doc/configuration.adoc`, `doc/user/`, `doc/development/`; `api-sheriff/src/test/**`

## Dependencies and Sequencing

- Depends on: nothing hard (RouteTable, metrics, auth infra all shipped). **Foundation of WS-06** —
  PLAN-22 reuses this read model.
- Overlaps with: mild adjacency to PLAN-06 (bearer-token/auth area) — confirm disjointness at emit.
  Largely disjoint from the data-plane plans (reads RouteTable/metrics, adds a mgmt endpoint), so it is
  NOT surface-gated behind PLAN-05/06/15 — emit eligibility decided by the disjointness check.
- **Split-guard note:** 5 substantive deliverables (+ tests/docs). Coherent (one endpoint); the auth
  hardening (D1) is the heaviest and riskiest — if outline finds the mgmt-interface auth wiring is
  large, split D1 into its own predecessor plan rather than bloat.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-02-api-inventory-endpoint.md" plan_id=plan-v03-02-api-inventory-endpoint
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/local/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-21.md is recorded}
