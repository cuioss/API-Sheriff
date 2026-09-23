# WS-05: Forwarded-Trust Allow-List Configurability and Coverage

epic: deployment-configurability

## Charter

`forwarded.trusted_proxies` is the gateway's proxy allow-list: the mandatory CIDR set that decides
whether a `Forwarded` / `X-Forwarded-*` header is believed at all (ADR-0003). Its value is
*inherently deployment-varying* — the proxy addresses in front of the gateway differ per site — yet
it lives only in `gateway.yaml`, a mounted file. The epic's own vision says a docker-only operator
should be able to set deployment-varying policy **from environment variables**, and this is the one
security-critical knob where that is only half true: an operator can env-fill each *slot* in the
list, but cannot env-set how many slots exist, and cannot switch a declared slot off. On top of
that, no shipped configuration exercises the block at all and no test proves an entry is
substitutable, so the half that does work is not test-proven either.

This workstream settles what the operator contract for the allow-list actually is, closes the
cardinality gap if that is the right call, and gives the mechanism the coverage a security-critical
trust boundary owes.

## Scope

- In scope: the `${VAR}` substitutability of `forwarded.trusted_proxies` end to end — the
  `ConfigLoader` array walk, `coerce`'s scalar-only type set, `EnvSecretResolver`'s missing-variable
  behaviour, and `ConfigValidator.validateForwardedTrust`'s CIDR refusal; the operator-facing
  documentation of that contract; a shipped example that actually declares a `forwarded` block; and
  the missing tests.
- Out of scope: the forwarded-header *parsing* and trust *semantics* themselves — ADR-0003 settled
  those and they work. The `TcpPeerGate` peer check. Widening substitution into a general
  list-valued-env feature for every array in `gateway.yaml` — if a mechanism lands it is scoped to
  this key and generalised only on evidence.
- Out of scope: the ADR-0025 boundary. This is **policy**, so gateway.yaml is its correct home; the
  question is how an env value reaches it, never whether the key should move to
  `application.properties`.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-06-forwarded-trust-env-configurability | staged | Settle the env contract for the allow-list, close the cardinality gap, ship an example and the missing tests |

## Sequencing and Surface Notes

- Depends on: nothing functionally. Surface-disjoint from PLAN-01 (container/compose), PLAN-03 and
  PLAN-04 (TLS knobs) and PLAN-05 (BFF refresh).
- ⚠ Overlaps **PLAN-02** on `doc/configuration.adoc` and on the shipped `gateway.yaml` files, and
  shares its *theme* — "a deployment-varying value an operator must express without rebuilding".
  Sequence after PLAN-02 so the two describe one environment-configuration story rather than two.
- ⚠ Whatever contract this workstream lands is the **precedent for every other list-valued
  `gateway.yaml` key**. Decide it as a contract, not as a one-off patch for this key.
