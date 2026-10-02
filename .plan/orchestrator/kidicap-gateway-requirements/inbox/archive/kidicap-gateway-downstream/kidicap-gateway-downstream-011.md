envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-02T12:00:00Z

## Requirement (PRIORITY: HIGH): tokens signed with a key the IdP introduced after the last JWKS fetch are rejected for up to 600 s

Filed by the downstream deployment `kidicap-gateway` on API Sheriff 0.2.3. **Measured**, twice:

* In the downstream's integration environment (CI cluster) after a deployment that restarted the test
  Keycloak: for a little over ten minutes every bearer call answered `401` and every BFF login ended with
  `400` at the callback; then everything worked again without intervention.
* Reproduced in the downstream's local Helm test stack (k3s, Keycloak 26.7.4): bearer call `200`, then
  `kubectl rollout restart` of the Keycloak deployment (it comes back with new signing keys — different
  `kid`s at the JWKS endpoint). Polling every 30 s with a freshly issued token: `401` from 11:10:50 to
  11:18:54 UTC, `200` at 11:19:24. Gateway log: `TokenSheriff-3: Background JWKS refresh started with
  interval: 600 seconds` and first load at 11:09:22, `TokenSheriff-2: Keys updated due to data change` at
  11:19:22 — exactly 600 s later.

Downstream record: `doc/protokolle/messprotokoll.adoc`, M-18; `deployment/integration-environment/doc/messprotokoll.adoc`,
U-05; open point `doc/open-issues.adoc#schluesselwechsel`.

### Cause (read at tag 0.2.3 and token-sheriff 0.9.6)

* `RetryingJwksLoader.getKeyInfo(kid)` is a pure delegation; "Every read is non-fetching … Only the
  scheduler fetches". The gateway's fast retry (1 s doubling, capped at 30 s) applies only until the first
  successful load.
* `HttpJwksLoader.getKeyInfo` looks in the current keys, then in retired key sets within the grace period,
  then returns empty — no fetch. Refresh is `scheduleAtFixedRate` with `DEFAULT_REFRESH_INTERVAL_IN_SECONDS
  = 600`.
* `TokenValidatorProducer` builds `HttpJwksLoaderConfig` without a refresh interval; ADR-0011 states
  `refreshIntervalSeconds` is "effectively fixed at 600 seconds today; deployments cannot tune key refresh
  cadence".
* The threat model (`doc/security-threat-model.adoc`) gives the reason for the non-fetching read: a flood
  of unknown `kid`s must not drive a fetch storm. The downstream agrees with that goal.

### Why the downstream needs a change

* An orderly key rotation publishes the new key before signing with it and is covered by the periodic
  refresh. An **abrupt** change is not: IdP restart without persistent keys (every CI environment of the
  downstream after a realm change), restore from backup, emergency key replacement after a compromise.
  In the last case ten minutes of total outage of every protected route and every login is the gateway
  amplifying the incident.
* Nothing in the answer tells the operator what is wrong: bearer callers see `401`, browser users a `400`
  at the callback. Readiness stays `UP` (documented in `doc/configuration.adoc`: "alert on it separately").
* There is no knob. The downstream cannot shorten the window.

### Required behaviour (either is sufficient; both preferred)

1. **Bounded on-demand refresh on an unknown `kid`.** When a token carries a `kid` that is neither in the
   current nor in a retired key set, trigger one JWKS fetch — rate-limited (at most one fetch per issuer
   per N seconds, N configurable, default e.g. 30 s) and single-flight, so an unknown-`kid` flood costs at
   most one request per window. The triggering request may still be answered `401`; the next one succeeds.
   This keeps the fetch-storm protection and closes the window to N seconds.
2. **`refresh_interval_seconds` configurable** on `token_validation.issuers[].jwks` (and the grace period
   alongside it), so a deployment can trade IdP load for a shorter window.

Additionally, for diagnosis: a WARN (rate-limited) when a token is rejected for an unknown `kid`, naming
the issuer and that the key set was last loaded at T — today the log shows only `TokenSheriff-200: Failed
to validate validation signature`.

### Acceptance

* Integration test: gateway running with a loaded key set; the IdP replaces its signing keys; a token
  signed with the new key is accepted within the configured window (not 600 s), without restart.
* An unknown-`kid` flood (k requests with random `kid`s) causes at most one JWKS request per window.
* A BFF login (callback, ID-token validation) recovers within the same window.
* Documented in `doc/configuration.adoc` and the threat model (the fetch-storm argument, now with the
  bound).

### Downstream workaround until then

None in the configuration. Operationally: after a deployment or restore that changes the IdP's signing
keys, restart the gateway (which also drops all `store: memory` sessions) or wait ten minutes. The
downstream's CI environments are affected after every deployment that touches the test realm.
