# PLAN-V03-02: API Inventory Endpoint + Interop Formats (management-plane, authenticated)

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

> **MERGED 2026-09-24 (cleanup, A5): PLAN-V03-03 (inventory interop formats) is folded INTO this
> spec**, executing the operator decision of 2026-07-27 ("THIS PLAN MERGES INTO PLAN-21 at this epic's
> DECOMPOSE"), which was held only until both specs carried re-grounded claim labels — both were
> re-grounded at 05f6ee3e the same day. `PLAN-V03-03-inventory-interop-formats.md` is retained
> unchanged as the audit record; its queue row is `superseded`. **Merge strength: STRONG** — same
> read model, same endpoint, same auth, same docs page; V03-03 had no surface V03-02 does not already
> declare. Deliverables were re-derived, not summed (see § Deliverables). Expected PR split, per the
> original decision: **PR1** = D1 auth + D2 read model + D3 native schema + D5 usage snapshot;
> **PR2** = D4 OpenAPI + D6 CycloneDX + D7 api-catalog + D8 docs completion.

## Objective

Expose the gateway's inventory of fronted API routes — and their live per-route usage — through an
authenticated, read-only endpoint on the management interface, so external systems (WAFs, API-security
tools) can consume the map the gateway already compiles at boot. The inventory is sensitive (it is the
authorized form of the enumeration map WS-05 hides from attackers), so the endpoint is served over TLS
and gated by **offline JWT validation requiring a dedicated scope**, reusing the shipped bearer-token
infrastructure. Per-route call counts already exist as cardinality-safe metrics; this plan READS them
into a point-in-time usage snapshot rather than adding new counters.

Beyond the native schema and OpenAPI, the same read model is also served in the two standards-aligned
interop layers the operator selected (folded in from PLAN-V03-03): a **CycloneDX SaaSBOM `service`**
representation for security-posture / CMDB / BOM consumers, and an **RFC 9727
`/.well-known/api-catalog`** discovery document linking to the available representations. No new
inventory data is gathered for these — they are additional serializations of, and a discovery pointer
to, the one read model D2 builds.

## Deliverables

**Eight deliverables (re-derived at the 2026-09-24 merge, not summed: 6 + 5 → 8).** V03-03's D3
(tests) collapses into the per-deliverable test rule below, and its D4 (architecture + ADR) and D5
(three-layer docs) collapse into D8, which already owned the same `doc/architecture.adoc` section, the
same ADR and the same `doc/user/management-endpoints.adoc` page. Scope-bloat guard: 8 is above the ~6
presumptive-split line; proceeding unsplit is recorded — the operator decided the merge on 2026-07-27
precisely because two plan lifecycles over one read model is the worse trade, the work ships as two
PRs (see the merge note above), and the project's authorized per-plan ceiling is 12.

Tests are not a numbered deliverable — **each of D1–D7 owes its own tests in the same PR**, per the
epic convention: auth (no-token and wrong-scope rejected, valid scope allowed), schema validity, usage
counts, CycloneDX output validated against the published SaaSBOM schema, and the well-known linkset
resolving to the real representations.

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
6. **CycloneDX SaaSBOM `service` mapping** (from V03-03 D1) — represent the gateway and its routes as
   SaaSBOM `service` components (`authenticated`, `endpoints`, trust-boundary, nested services,
   `externalReferences` linking to the D4 OpenAPI doc), built from D2's read model — never by re-reading
   `RouteTable` in a second path. **Stated as its own line item.**
7. **RFC 9727 `/.well-known/api-catalog`** (from V03-03 D2) — a well-known-URI endpoint returning an
   `application/linkset+json` document linking to the inventory representations (native JSON, OpenAPI,
   CycloneDX). **Stated as its own line item** — it is a discovery pointer, distinct from the inventory
   payload itself. Which interface serves it (management vs. data plane) and whether it sits behind the
   D1 scope gate is decided at outline and recorded in the D8 ADR.
8. **Documentation — architecture, ADR and all three layers, as one named line item.**
   have been silently dropped in this epic before; see the named-line-items watch). Document the NEW
   management-plane introspection architecture in `doc/architecture.adoc`: the endpoint, the
   **management-interface auth model** (TLS + offline-JWT-scope, and how the shipped `auth/` stage
   attaches to the management interface), the inventory read model + serialization strategy, and the
   interop representations + `/.well-known/api-catalog` discovery surface. Record **one ADR** for the
   management-plane introspection API + its auth model + the inventory-format decision (native schema +
   OpenAPI + CycloneDX SaaSBOM + RFC 9727). ADR number: the next free number on `main` AND every open
   branch at write time — never an assumed one. ⚠ As of 05f6ee3e `doc/adr/` carries a **duplicate
   0053** (two distinct records); resolve or route that before allocating.

   Plus the three-layer set:
   document** (operator 2026-07-25): `configuration.adoc` (the endpoint, the `gateway:inventory:read`
   scope, the management-interface TLS/auth config); a **NEW dedicated `doc/user/` document** (e.g.
   `doc/user/management-endpoints.adoc`) that is the single home for ALL management-plane endpoint
   docs — it covers the inventory endpoint here AND consolidates the existing management-port surface
   (health, metrics) so operators have one page for the whole management plane — including the interop
   formats and the `/.well-known/api-catalog` discovery document; and `doc/development/`. One page, not
   two: the V03-03 fold removes the second-page risk this line used to guard against.

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
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: ManagementConfig.java:47 still only (tls), no auth field; application.properties:125-193 mgmt single HTTPS port
- OBSERVED: the inventory is ready to serialize — `config/model/RouteTable.java` (`List<ResolvedRoute>`)
  and `config/model/ResolvedRoute.java`:79-84 (id, protocol, anchor, match, effectiveAuth,
  effectiveAllowedMethods, effectiveSecurityFilter, upstream, asset).
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: RouteTable.java:41 record RouteTable(List<ResolvedRoute>); ResolvedRoute.java:102 all named fields present
- OBSERVED: per-route counts already exist, cardinality-safe — `quarkus/SheriffMetrics.java`:38-47,58-70
  (`sheriff_requests_total{route,method,status_family}`, route id is a config-fixed bounded label,
  unmatched share `<no-route>`).
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: SheriffMetrics.java:62 REQUESTS_TOTAL=sheriff_requests_total, :102 recordRequest(route,method,statusFamily) unchanged
- OBSERVED: bearer-token offline validation infra exists — `auth/` (`AuthenticationStage`,
  `TokenValidatorProducer`, `JwksTrustProfileResolver`); scope enforcement already models a 403 via
  `events/EventType.java` `SCOPE_MISSING`.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: auth/AuthenticationStage, TokenValidatorProducer, JwksTrustProfileResolver present; EventType.java:108 SCOPE_MISSING(AUTHORIZATION,403)
- HYPOTHESIS (central risk): the shipped bearer validation can be attached to a *management-interface*
  route. Confirm/refute at outline — the Quarkus management interface has its own routing; if the
  `auth/` stage cannot be applied there, the plan wires management-interface-local auth instead
  (verify-at-outline). Do not assume the data-plane pipeline runs on the management port.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: no @Path resource under quarkus/; AuthenticationStage still data-plane-only; portal (69b322b5) is data-plane HTML, not mgmt -- risk open
- Verify-first clause: confirm the management port is genuinely reachable only where intended and decide
  the TLS story (terminate TLS on 9000, or require it be network-isolated) against the actual deployment
  posture, not this spec's assumption.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: compose-sample docker-compose.yml:184 still 127.0.0.1:9000:9000 loopback-only; future design work
- OBSERVED (research, folded from V03-03): no single standard models a gateway route+policy+upstream
  inventory; CycloneDX SaaSBOM `service` is the closest security-oriented fit
  (`authenticated`/`endpoints`/trust-boundary/nested services) but lacks per-route method + path-pattern
  + route-id; RFC 9727 (Standards Track, Jun 2025) is a discovery pointer (`application/linkset+json`),
  not an inventory schema. This is why the native schema (D3) stays the authoritative representation.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: external standards-research claim (folded from V03-03), no repo artifact to check
- HYPOTHESIS (folded from V03-03): D2's read model exposes enough structure to map onto SaaSBOM
  `service` without re-deriving from `RouteTable`. Confirm at outline, when D2's type is designed —
  design D2 so that D6 needs no second path.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: gated on D2's read-model type, unbuilt at 05f6ee3; ResolvedRoute.java:102 fields make it plausible
- Verify-first clause (folded from V03-03): validate the CycloneDX output against the actual published
  SaaSBOM/service schema and the well-known document against RFC 9727's `linkset+json` media type —
  against the specs, not this spec's prose.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: procedural directive; no cyclonedx/api-catalog code in src at 05f6ee3
- OBSERVED (2026-09-24): the portal application catalog (69b322b5, `portal/PortalCatalog.java`,
  `config/model/CatalogConfig.java`) is NOT an inventory substrate — it is an opt-in, per-endpoint,
  data-plane HTML listing built from `CatalogConfig` (title/description/entry/order) and carries none of
  D2's route fields. Do not reuse it for D2, and do not confuse the RFC 9727 "api-catalog" with it.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: PortalCatalog.from() builds only from EndpointConfig.catalog() (CatalogConfig title/description/entry/order), data-plane HTML, no RouteTable fields

## Expected Surface

- CORRECTED 2026-09-24 (cleanup): main-source entries were package-relative and did not resolve
  (application.properties, schema/gateway.schema.json and ResolvedRoute.java were dropped
  silently). Rewritten as full repo paths, one per bullet, path on the bullet's first line.
- OBSERVED: `api-sheriff/src/main/resources/application.properties` — management TLS + auth config
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ManagementConfig.java` — mgmt-auth knob home
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — the ManagementConfig block
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/RouteTable.java` — read-only inventory source
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ResolvedRoute.java` — read-only inventory source
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` — read-only usage snapshot
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/` — reused for the offline-JWT-scope gate
- OBSERVED: `doc/architecture.adoc` — NEW management-plane introspection section
- OBSERVED absence → NEW: `doc/adr/00NN-*.adoc` — number allocated at write time (next free on main)
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/user/`
- OBSERVED: `doc/development/`
- OBSERVED: `api-sheriff/src/test/**`
- Narrative (unchanged): NEW inventory endpoint resource + read model + JSON Schema + OpenAPI
  serializer, package chosen at outline. `ManagementConfig` is currently `record
  ManagementConfig(@Nullable ManagementTls tls)`; mgmt-interface auth wiring is verify-at-outline.

## Dependencies and Sequencing

- Depends on: nothing hard (RouteTable, metrics, auth infra all shipped). Now carries all of WS-02 —
  PLAN-V03-03 is folded in (merge note above), so there is no downstream sibling to sequence against.
- Overlaps with: mild adjacency to the api-sheriff-0-2-0 auth-area plans (bearer-token/auth) — confirm
  disjointness at emit. Largely disjoint from the data-plane plans (reads RouteTable/metrics, adds a
  mgmt endpoint) — emit eligibility decided by the disjointness check.
- **Split-guard note:** 8 deliverables after the merge (see § Deliverables for the recorded rationale).
  Coherent (one read model, one endpoint family); D1 auth is the heaviest and riskiest — if outline
  finds the mgmt-interface auth wiring is large, split D1 into its own predecessor plan. The merge is
  licensed to split back along the PR1/PR2 line at outline if the outline finds that cleaner.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-02-api-inventory-endpoint.md" plan_id=plan-v03-02-api-inventory-endpoint
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-V03-02.md is recorded}
