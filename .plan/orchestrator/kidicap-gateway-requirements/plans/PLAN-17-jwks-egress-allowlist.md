# PLAN-17: Default the JWKS Egress Allowance to the Host of `jwks.url`

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-17-jwks-egress-allowlist.md` and is queued in the epic `status.json` `plans[]`
> field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-17 from inbox message `kidicap-gateway-downstream-003.md`.

## Objective

`token_validation.issuers[].jwks.allowed_egress_hosts` makes the operator repeat the host of the JWKS URL
they already wrote, as a bare hostname. The duplicate is silent in all three failure directions: the two
values can disagree with nothing comparing them, an empty entry boots and fails later at the JWKS fetch,
and an entry carrying a port never matches because the comparison is host-exact. This plan defaults the
allowance to the host of the configured `jwks.url` when the list is absent or empty, and makes an unusable
entry a boot error instead of a runtime surprise.

## Source

Inbox `kidicap-gateway-downstream-003.md` (finding, 2026-09-16), filed by the downstream deployment
`kidicap-gateway`; measured against the 0.2.1 native image.

- `allowed_egress_hosts: [""]` (a `${VAR}` that resolved to empty) boots normally, reports no violation,
  and the JWKS fetch is refused later — the failure surfaces as a login problem far from its cause.
- `host:8443` in the list is never matched (host-exact comparison), again with no boot-time complaint.
- Proposal: when `allowed_egress_hosts` is absent or empty for an `http`-sourced issuer, default it to the
  host of that issuer's `jwks.url`. The SSRF boundary is preserved — the operator wrote that URL, so
  admitting exactly its host is the narrowest allowance that makes the configured URL usable. An operator
  wanting a wider set keeps naming hosts explicitly.
- Alternatives named by the reporter, if defaulting is unwanted: accept `host:port` and compare the host
  part, or an explicit marker (`[from-jwks-url]`). Either removes the silent-mismatch class; only the
  default also removes the duplicate value.
- A boot-time refusal of an empty entry is worth having in any case: it converts "starts, fails later,
  looks like a token problem" into a loud failure.
- Why it matters downstream: the published image is distroless with a native executable, so the value
  cannot be derived in the container; the derivation lives in Helm render and a Compose start script —
  three places carrying one rule that belongs in the component owning the schema.

## Deliverables

1. Default the allowance: for an `http`-sourced issuer whose `allowed_egress_hosts` is absent or empty,
   derive the allowance from the host of that issuer's `jwks.url` instead of leaving the secure-default
   egress policy to refuse a private address. Keep the explicit list authoritative when present.
2. Boot validation: refuse a blank or whitespace-only entry, and decide the `host:port` case — either
   refuse it with a message naming the host-exact rule, or accept it and compare the host part (record
   the choice; do not leave it silently inert).
3. Tests: unit tests for derive-from-URL, explicit-list-wins, blank entry refused, `host:port` per the
   decision; an integration test proving an issuer with no `allowed_egress_hosts` on a private-address
   IdP loads its JWKS, and that a mismatching explicit list still refuses.
4. Documentation and records: `doc/configuration.adoc` (issuer keys), `doc/security-threat-model.adoc`
   (GW-05 / BFF-07 — state that the derived allowance is not a widening of trust), `doc/LogMessages.adoc`
   for any new boot record; ADR only if the SSRF boundary statement changes.

## Claim Labels

- OBSERVED: with `allowed_egress_hosts` absent or empty no egress builder method is called, so the config keeps token-sheriff's `EgressPolicy.secureDefault()` and a private-address JWKS URL is refused — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java` § `httpLoaderConfig` Javadoc and body
  - verdict: corroborated | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: TokenValidatorProducer.java unchanged 3e3addc..69b322b; httpLoaderConfig loop unchanged
- OBSERVED: each configured host is passed to `allowedEgressHost(host)` unchanged and the match is host-exact (no wildcard, no suffix, no port handling) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java` § `httpLoaderConfig` loop
  - verdict: corroborated | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: TokenValidatorProducer.java unchanged; host-exact allowedEgressHost(host) call unchanged
- OBSERVED: `IssuerConfig.allowedEgressHosts` normalises only null to an empty list — no entry-level validation — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java` § canonical constructor
  - verdict: corroborated | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: IssuerConfig.java unchanged 3e3addc..69b322b; canonical constructor unchanged
- HYPOTHESIS: no boot rule rejects a blank entry or a `host:port` entry today — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/` § issuer rules (verify-at-outline)
  - verdict: corroborated | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: ConfigValidator.java gained only PLAN-16 portal rules (PortalRules); still no issuer/egress rule
- HYPOTHESIS: the downstream's measured `[""]`-boots behaviour reproduces at HEAD — confirm/refute with a boot test over an issuer carrying a blank entry (verify-at-outline)
  - verdict: unverifiable | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: nothing rejects a blank entry at 69b322b; boot outcome still depends on token-sheriff, unexecuted
- Verify-first clause: settle whether deriving the allowance belongs in `TokenValidatorProducer` (config assembly) or in the config model / validation layer, and confirm that the derived host reaches token-sheriff exactly as an explicit entry would; a refutation re-scopes deliverable 1 before implementation.
  - verdict: corroborated | checked_at: 69b322b | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: TokenValidatorProducer.java unchanged; toHttpJwksLoaderConfig loop still the single seam

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java` — `httpLoaderConfig`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/` — issuer boot rules (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `allowed_egress_hosts` description
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/` — producer tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — issuer config without an explicit allowlist (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — issuer keys (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` — GW-05 / BFF-07 (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` — new boot record (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none functionally; shares `doc/configuration.adoc` and the validation package with PLAN-13, PLAN-15 and PLAN-16, so it is sequenced against whichever of those is in flight
- Overlaps with: PLAN-13 (`auth/`, docs), PLAN-15 (validation rules, docs)
- Adjacent to: PLAN-13's AS-10 JWKS readiness work reads the same issuer configuration but not this code path

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-17-jwks-egress-allowlist.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
