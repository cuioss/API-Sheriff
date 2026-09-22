# PLAN-V03-03: Inventory Interop Formats — CycloneDX SaaSBOM + RFC 9727

epic: api-sheriff-0-3-0
workstream: WS-02
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — ready for `/plan-marshall` hand-off. NEW 2026-07-25 (operator chose the FULL format
> scope). Builds on PLAN-21's inventory read model + endpoint.
>
> **⚠ THIS PLAN MERGES INTO PLAN-21 at this epic's DECOMPOSE (decided 2026-07-27).** Two full plan
> lifecycles to add two serializations and a well-known URI over a read model the first plan already
> builds is not a good trade — and PLAN-21's D1 has just SHRUNK (management TLS is done), so the
> merged plan is smaller than the pair was. Expected PR split: PR1 = auth + read model + native
> schema; PR2 = OpenAPI + CycloneDX + `/.well-known/api-catalog` + docs.
> Retained as a separate spec until the merge is authored against re-grounded claim labels. **Do not
> launch it standalone.**

> **Renumbered 2026-08-04.** This spec was `PLAN-22-inventory-interop-formats.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Objective

Extend the authenticated inventory endpoint (PLAN-21) with the two additional standards-aligned interop
layers the operator selected: a **CycloneDX SaaSBOM `service`** representation for security-posture /
CMDB / BOM consumers, and an **RFC 9727 `/.well-known/api-catalog`** discovery document that links to the
available inventory representations. No new inventory data is gathered — these are additional
serializations of, and a discovery pointer to, the read model PLAN-21 already builds.

## Deliverables

1. **CycloneDX SaaSBOM `service` mapping** — represent the gateway and its routes as SaaSBOM `service`
   components (`authenticated`, `endpoints`, trust-boundary, nested services, `externalReferences`
   linking to the OpenAPI doc), from PLAN-21's read model. **Stated as its own line item.**
2. **RFC 9727 `/.well-known/api-catalog`** — a well-known-URI endpoint returning an
   `application/linkset+json` document linking to the inventory representations (native JSON, OpenAPI,
   CycloneDX). **Stated as its own line item** — it is a discovery pointer, distinct from the inventory
   payload itself.
3. **Tests** (CycloneDX validates against the SaaSBOM schema; the well-known linkset resolves to the
   real representations).
4. **Architecture documentation + ADR** — **stated as its own named line item**. Extend
   `doc/architecture.adoc`'s management-plane introspection section (from PLAN-21) with the interop
   representations and the `/.well-known/api-catalog` discovery surface; record an **ADR** for adopting
   CycloneDX SaaSBOM + RFC 9727 as the interop/discovery layer (or extend PLAN-21's ADR — decide at
   outline, but the format-adoption decision must be captured in an ADR, not only prose).
5. **Three-layer documentation**: `configuration.adoc` (the new representations + well-known URI);
   **EXTEND the dedicated `doc/user/management-endpoints.adoc` document PLAN-21 created** (the interop
   formats + `/.well-known/api-catalog` discovery) — do NOT fork a second management-endpoints page
   (operator 2026-07-25); and `doc/development/`.

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25 + the format research.

- OBSERVED (research): no single standard models a gateway route+policy+upstream inventory; CycloneDX
  SaaSBOM `service` is the closest security-oriented fit (`authenticated`/`endpoints`/trust-boundary/
  nested services) but lacks per-route method + path-pattern + route-id; RFC 9727 (Standards Track, Jun
  2025) is a discovery pointer (`application/linkset+json`), not an inventory schema. (Sourced in the
  requirements-intake decision log.)
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: External-standards research claim, no repo artifact to check -- not falsifiable against main, nothing in-repo contradicts it either.
- HYPOTHESIS: PLAN-21's read model exposes enough structure to map onto SaaSBOM `service` without
  re-deriving from `RouteTable`. Confirm/refute at what PLAN-21 ships § its inventory read-model type
  (verify-at-outline) — reuse it; do not re-read `RouteTable` in a second path.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: ResolvedRoute.java:102-121 (drifted from :79-84) still carries all named fields, substrate plausible -- but explicitly gated on what PLAN-V03-02 ships, which is unstarted (unset Status Trail, zero inventory-endpoint code in main). Deferral stays open.
- Verify-first clause: validate the CycloneDX output against the actual published SaaSBOM/service schema
  and the well-known document against RFC 9727's `linkset+json` media type — against the specs, not this
  spec's prose.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: Procedural directive, not a factual claim -- no CycloneDX serializer or /.well-known/api-catalog code exists anywhere in main to validate against yet.

## Expected Surface

- OBSERVED: PLAN-21's inventory read model + endpoint scaffolding — reused
- OBSERVED absence → NEW: a CycloneDX SaaSBOM serializer + the `/.well-known/api-catalog` resource
- OBSERVED: `doc/configuration.adoc`, `doc/user/`, `doc/development/`; `api-sheriff/src/test/**`
- ADDED 2026-09-22 (understated — Deliverable 4 names both explicitly but Expected Surface omitted
  them): `doc/architecture.adoc` (no management-plane/introspection/inventory section exists yet —
  confirmed empty by grep) and `doc/adr/00NN-*.adoc` (the ADR this deliverable's decision record
  requires; corpus is contiguous 0001-0049 on main, so the next free number is 0050)

## Dependencies and Sequencing

- Depends on: **PLAN-21 (hard)** — reuses its read model, endpoint, and auth. Sequence PLAN-21 → PLAN-22.
- Overlaps with: PLAN-21 only (same endpoint) — strictly sequential within WS-06.
- Adjacent to: nothing on the data plane.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-03-inventory-interop-formats.md" plan_id=plan-v03-03-inventory-interop-formats
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/local/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-22.md is recorded}
