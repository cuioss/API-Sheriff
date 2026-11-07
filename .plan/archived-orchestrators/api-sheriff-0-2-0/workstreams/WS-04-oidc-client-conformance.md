# WS-04: OIDC Client Conformance

epic: api-sheriff-0-2-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Bring the BFF from the RFC 9700 baseline it ships at 0.1.0 to genuine FAPI 2.0 Security Profile conformance as an OIDC relying party, and make the gateway able to **address** an identity provider correctly in the first place.

## Surface

- **In scope**: the confidential-client flow as the profile governs it — PAR, client authentication, sender-constrained tokens and their key lifecycle, the `oidc` configuration surface, back-channel trust/key material, and what the gateway forwards upstream once tokens carry `cnf`. Also the **IdP addressing model**: the issuer identity, the frontchannel/backchannel transport split, and the reserved-path namespace.
- **Out of scope**: authorization-server-side requirements; FAPI 2.0 Message Signing (a separate profile); the `response_mode=query` tradeoff, which is an independent decision on its own merits.

## Plans

- **PLAN-V02-08** — FAPI 2.0 conformance for the confidential-client flow.
- **PLAN-V02-12** — the IdP addressing model: one issuer identity, the frontchannel/backchannel split, and the `/auth`-fronted Keycloak variant.

**Both plans write the `oidc` block and `OidcConfig` — they are SEQUENTIAL, never concurrent, and PLAN-V02-09 (`BffRuntimeProducer`) joins the same chain.** Prefer V02-08 first: it is the larger reshaping of that block, and RFC 9207's `iss` requirement — which V02-08 inherits from FAPI — is the same constraint that forecloses V02-12's rejected two-issuer design.

**The standing constraint this workstream carries: a capability present in a dependency is not a capability of the product.** This workstream exists because that distinction was lost once already — `doc/features-analysis.adoc` claimed a PAR-driven, sender-constrained flow while the gateway wired neither. Settle every completion claim at the **call site**, never at the import; settle a conformance claim by a conformance-suite run, never by inspection.

## Status — 2026-08-09

- **Shipped**: none yet
- **Remaining**: **PLAN-V02-08** first, then **PLAN-V02-12**

*Epic-wide since these charters were written*: the build now fails on any compiler warning
(`showDeprecation` + `failOnWarning`, reactor-wide), so every remaining plan in this workstream must
migrate off a warned construct rather than suppress it. See each spec's `## Re-Grounded (2)` section.
