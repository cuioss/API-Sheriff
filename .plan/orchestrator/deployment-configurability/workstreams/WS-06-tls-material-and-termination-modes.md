# WS-06: TLS Material Contract and Termination Modes

epic: deployment-configurability

## Charter

API Sheriff already has a strong *policy* story for server TLS: ADR-0025 classifies every knob as
policy / deployment-bound / build-time, routes policy through exactly two seams, and
`SingleSourceTlsContractTest` pins that surface so a raw policy key cannot creep back. This
workstream does **not** re-open any of that.

What it addresses is the other half — the half ADR-0025 deliberately pushed out of scope by ruling
that certificate, key and trust **material** stays deployment-supplied. Material being
deployment-supplied is the right call; it is *how an operator supplies it, and what happens when they
supply it wrongly*, that is unfinished:

1. **The gateway never says what TLS it actually resolved on its main listener.**
   `ManagementPlainHttpAudit` does exactly this for the management interface, and does it well —
   keyed on the resolved key material rather than on a config key, "because a configuration key can be
   renamed, superseded, or silently ignored, and an audit keyed on one would then report a comfortable
   fiction." The main listener and the outbound trust source have no equivalent.
2. **Trust configured through Quarkus REPLACES the platform bundle rather than extending it**, and
   nothing in the product tells an operator so. On a distroless image with a native binary — this
   project's shipped artifact — the usual OS-level escape hatch is unavailable, so the replacement is
   the only route and the footgun is unavoidable rather than merely available.
3. **There is no plain-HTTP / TLS-terminated-upstream mode.** `insecure-requests=redirect` ships as
   the default and no shipped configuration uses any other value, so a deployment that terminates TLS
   at an ingress or sidecar has no supported shape — and today gets a redirect to a listener that was
   never started.

## Scope

- In scope: a boot-time audit of the *resolved* TLS material and trust source on the main listener,
  matching the management audit's design; the operator-facing contract for supplying certificate, key
  and trust material (including the replaces-vs-extends hazard and the native/distroless
  consequences); and a first-class, documented, tested plain-HTTP termination mode with the shipped
  example to match.
- Out of scope: ADR-0025's policy/material classification and its two seams — settled, and this
  workstream binds to them. The ADR-0017 SNI split. The mTLS *inbound* client-cert surface
  (`MtlsServerCustomizer`). The JWKS per-issuer trust profiles (`JwksTrustProfileResolver`), whose
  refuse-rather-than-fall-back posture is the model to follow, not the thing to change.
- Out of scope: switching the shipped artifact from native to JVM, or the distroless base to UBI
  minimal. Both were raised as options; both are project-shaping decisions well beyond this epic, and
  PLAN-07 records the trade-off rather than taking it.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-07-tls-material-audit-and-trust-contract | staged | Audit the resolved material on the main listener; settle and document the trust replaces-vs-extends contract |
| PLAN-08-plain-http-termination-mode | staged | A first-class TLS-terminated-upstream (ingress / sidecar) mode, with the boot guard, example and tests |
| PLAN-09-tls-scenario-guide | staged | A scenario-organised `doc/user/` guide: goal / preconditions / concrete keys, per supported TLS scenario |

## Sequencing and Surface Notes

- **PLAN-07 before PLAN-08, hard.** PLAN-08's mode is the degenerate case of PLAN-07's contract — "no
  server certificate at all" — and its boot guard is the same guard. Building the mode before the
  contract means deciding the vocabulary twice.
- Overlaps PLAN-02 and PLAN-06 on `doc/configuration.adoc`, the shipped `gateway.yaml` files and
  `deployment/compose-sample/`. Sequence after both.
- Surface-disjoint from PLAN-03 (upstream dial) and PLAN-04 (JWKS back-channel) in *code*, but ⚠ they
  share the **operator-facing TLS documentation** and the threat model, and PLAN-03's deliverable 2 is
  the naming authority for the outbound hostname-verification vocabulary. Do not invent a second
  vocabulary for trust here.
- **PLAN-09 is documentation-only and mostly independent.** Scenarios 1-5 are documentable against
  today's shipped behaviour and depend on nothing; only scenario 6 (private-CA IdP trust) waits on
  PLAN-07's replaces-vs-extends verdict, and scenario 7 (plain HTTP) is added when PLAN-08 lands.
  ⚠ It is therefore the workstream's **earliest operator-facing value** and can be split to ship
  scenarios 1-5 ahead of PLAN-07 if that is wanted.
- ⚠ **This is a security workstream.** Every deliverable that widens or relaxes a default owes an
  explicit statement of what it exposes and a threat-model entry, per the epic's secure-by-default
  constraint.
