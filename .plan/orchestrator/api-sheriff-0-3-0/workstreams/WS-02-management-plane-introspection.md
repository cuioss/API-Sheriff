# WS-02: Management-Plane Introspection

epic: api-sheriff-0-3-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Give operators a first-class, authenticated read model of what this gateway exposes, and serialize it into the formats the ecosystem already consumes.

## Surface

- **In scope**: the API inventory read model, its authenticated management-plane endpoint, and its interoperability serializations (OpenAPI, CycloneDX, a well-known URI).
- **Out of scope**: mutating management APIs — the gateway is immutable-at-startup by design (ADR-0002).

## Plans

- **PLAN-V03-02** — API inventory endpoint. *Builds the read model.*
- **PLAN-V03-03** — Inventory interop formats. *Decided to merge INTO PLAN-V03-02 — see the epic ledger; the merged spec is deliberately not yet authored.*
