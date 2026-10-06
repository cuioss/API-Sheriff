# PLAN-24: Close the Key-Rotation Window — Bounded On-Demand JWKS Refresh on an Unknown `kid`

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-24-jwks-unknown-kid-refresh.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-10-02 from inbox message `kidicap-gateway-downstream-011.md`.

## Objective

When the identity provider's signing keys change abruptly, every protected route and every login fails
for **up to 600 seconds** — and nothing in the gateway shortens it, reports it, or lets an operator tune
it. A read that meets an unknown `kid` never fetches; only the background scheduler does, at a fixed
600 s cadence. For an orderly rotation (new key published before it signs) that is fine. For an abrupt
change — IdP restart without persistent keys, restore from backup, emergency key replacement after a
compromise — the gateway turns a key change into a ten-minute total outage, and in the compromise case it
amplifies the incident it should be surviving.

This plan closes the window to a configurable bound while keeping the fetch-storm protection that
motivated the non-fetching read in the first place.

## Source

Inbox `kidicap-gateway-downstream-011.md` (finding, 2026-10-02), filed by the downstream deployment
`kidicap-gateway` against 0.2.3 and classified **HIGH priority**. **Measured twice**, which is what makes
this a defect report rather than a design preference:

- In the downstream's CI cluster after a deployment restarted the test Keycloak: ~10 minutes of `401` on
  every bearer call and `400` at every BFF callback, then recovery with no intervention.
- Reproduced in its local Helm stack (k3s, Keycloak 26.7.4) by `kubectl rollout restart` of Keycloak,
  polling every 30 s with freshly issued tokens: `401` from 11:10:50 to 11:18:54 UTC, `200` at 11:19:24.
  Gateway log: first JWKS load 11:09:22, `TokenSheriff-2: Keys updated due to data change` at 11:19:22 —
  **exactly 600 s later**, the scheduler's interval and nothing else.

Downstream records: `doc/protokolle/messprotokoll.adoc` M-18; `deployment/integration-environment/doc/messprotokoll.adoc` U-05;
open point `doc/open-issues.adoc#schluesselwechsel`.

**The downstream agrees with the fetch-storm goal** the threat model states for the non-fetching read —
it asks for a bound, not for its removal.

## Deliverables

1. **Bounded, single-flight on-demand refresh on an unknown `kid`.** A token whose `kid` is in neither the
   current nor a retired (in-grace) key set triggers **one** JWKS fetch per issuer per window: rate-limited
   (window configurable, default ~30 s) and single-flight, so a flood of random `kid`s costs at most one
   request per window per issuer. The triggering request may still answer `401`; the next one succeeds.
2. **`refresh_interval_seconds` configurable** on `token_validation.issuers[].jwks`, with the grace period
   alongside it, so a deployment can trade IdP load against window length. Schema + config model + boot
   validation, with bounds that refuse a value small enough to constitute self-inflicted load.
3. **A diagnostic WARN, rate-limited**, when a token is rejected for an unknown `kid`: name the issuer and
   the instant the key set was last loaded. Today the only trace is
   `TokenSheriff-200: Failed to validate validation signature`, which names neither.
4. **Integration tests** for the acceptance rows below, using the existing Keycloak stack.
5. **Documentation**: `doc/configuration.adoc` for both new keys, and the threat model — the fetch-storm
   argument stays, now stated WITH its bound. ADR-0011 records `refreshIntervalSeconds` as "effectively
   fixed at 600 seconds today; deployments cannot tune key refresh cadence"; deliverable 2 falsifies that
   sentence, so amend or supersede ADR-0011 in the same act (⚠ next free ordinal is **0062** — 0060 (dispatch framing) and 0061 (offline config validation) were taken after the 2026-10-05 pass; re-checked 2026-10-06 at `6788bdae` by listing `doc/adr/`. Re-list before allocating: this figure has gone stale twice).

Split guard: 5 deliverables — well within the operator-authorized 12.

## Acceptance (from the filing)

| Case | Required outcome |
|---|---|
| Gateway running with a loaded key set; the IdP replaces its signing keys; a token signed with the new key arrives | accepted within the configured window, **not** 600 s, without a restart |
| An unknown-`kid` flood (k requests, random `kid`s) | at most ONE JWKS request per window per issuer |
| A BFF login (callback, ID-token validation) after the key change | recovers within the same window |

## Claim Labels

- OBSERVED: `TokenValidatorProducer.toHttpJwksLoaderConfig` builds the loader config with **no** refresh
  interval and passes `first.getRefreshIntervalSeconds()` (the library default) to `RetryingJwksLoader`
  — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java:270-279`,
  orchestrator-verified at HEAD 2026-10-02.
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): TokenValidatorProducer:360-401 builder sets no refresh interval; :277-278 passes first.getRefreshIntervalSeconds() to RetryingJwksLoader
- OBSERVED: no `refresh_interval` (and no grace-period) key exists anywhere in
  `api-sheriff/src/main/resources/schema/gateway.schema.json` — zero occurrences at HEAD. So there is no
  knob today, exactly as filed.
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): gateway.schema.json jwks block (329-347) is additionalProperties false with source/url/file/allowed_egress_hosts/tls_profile only
- OBSERVED: ADR-0011 states `refreshIntervalSeconds` is effectively fixed at 600 s and untunable by
  deployments — `doc/adr/0011-…adoc:220`.
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): ADR-0011:221 states refreshIntervalSeconds is effectively fixed at 600 s and untunable; 224-226 say the same for grace and maxRetiredKeySets
- **RESOLVED 2026-10-05 (cleanup, re-grounded at `35f2bb37`) — the seam is IN THIS REPOSITORY.** The
  earlier hypothesis attributed both halves to the library and left an outline fork ("if the bounded fetch
  belongs in token-sheriff, that half leaves this repository"). Half of it was right and half was wrong:
  the non-fetching read IS the library's (`HttpJwksLoader.getKeyInfo` in 0.9.6 — current keys, then retired
  sets within grace, then empty), but `RetryingJwksLoader` is **the gateway's own class** under
  `api-sheriff/.../auth/`, carrying its own scheduler and fresh-delegate retry. ⛔ **The fork is therefore
  closed: the bounded unknown-`kid` fetch can be built here, in `RetryingJwksLoader`, with no upstream
  change and no library fork.** Deliverable 2 (`refresh_interval_seconds`) stays a pass-through of the
  library's existing config field, confirmed present by `javap`:
  `HttpJwksLoaderConfig.DEFAULT_REFRESH_INTERVAL_IN_SECONDS = 600`, alongside `keyRotationGracePeriod` and
  `maxRetiredKeySets` — so the grace period of deliverable 2 is also already a library knob.
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RetryingJwksLoader is the gateway's own class under api-sheriff/.../auth/ (auth/ unchanged since 35f2bb37); token-sheriff still 0.9.6 at pom:63 after the parent bump to 1.7.6
- HYPOTHESIS: the 600 s figure is the library's `DEFAULT_REFRESH_INTERVAL_IN_SECONDS`, so deliverable 2 is
  a pass-through rather than a new scheduler — confirm/refute at the same jar § that constant
  (verify-at-outline).
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: pom:63 still pins token-sheriff 0.9.6 after the cui-quarkus-parent 1.7.6 bump; HttpJwksLoaderConfig DEFAULT_REFRESH_INTERVAL_IN_SECONDS=600 as confirmed by javap at 35f2bb37
- HYPOTHESIS: readiness stays `UP` through the whole window (`doc/configuration.adoc` tells operators to
  "alert on it separately"), so no readiness change is in scope and the WARN of deliverable 3 is the only
  signal added — confirm/refute at `doc/configuration.adoc` § the JWKS readiness rows and the health
  contract PLAN-13 shipped (verify-at-outline).
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: configuration.adoc:2177 still says a stalled key rotation is not visible through readiness and to alert on it separately; auth/ and the readiness check are unchanged since 35f2bb37

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java` — the
  loader-config construction and the `RetryingJwksLoader` wrapper
- OBSERVED (added 2026-10-05): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`
  — **the seam deliverable 1 builds in**: the gateway-owned wrapper holding the scheduler and the
  fresh-delegate retry. The earlier surface named only `TokenValidatorProducer`, which is where the config
  is assembled, not where the fetch decision lives
- OBSERVED (added 2026-10-05): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/IssuerKeySetStatus.java`
  — candidate home for the last-loaded instant deliverable 3's WARN must name
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java` — the
  `jwks` block gaining both keys
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `refresh_interval_seconds` and
  the grace period
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/` — bounds validation
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` — the
  rate-limited unknown-`kid` WARN (a new WARN needs a LogRecord in the 100-199 range)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/` — unit coverage
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java`
  — the acceptance rows against a Keycloak whose keys are replaced mid-run. Declared as the one expected
  IT file rather than the whole directory, per the over-declaration lesson (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — an issuer fixture with a short window
  (verify-at-outline)
- OBSERVED: `doc/configuration.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`,
  `doc/adr/` (the ADR-0011 amendment)

## Dependencies and Sequencing

- Depends on: none. PLAN-13 (AS-10 JWKS readiness) and PLAN-17 (egress allowlist) both shipped and both
  touched this surface; this plan extends it rather than waiting on anything.
- Overlaps with: PLAN-20 and PLAN-21 only on `doc/configuration.adoc`, `doc/adr/`, `doc/LogMessages.adoc`
  and the IT directory — **no main-source overlap at all** with either (they are BFF/login; this is
  `auth/` plus config).
- **So this is a genuine second-slot candidate** alongside a WS-04 plan, in the way PLAN-22 was. It is the
  first WS-01 work since PLAN-17.
- ⚠ If the HYPOTHESIS above resolves to "the bounded fetch belongs in token-sheriff", this plan's shape
  changes: it becomes a library change plus a gateway pass-through, and the library work is NOT in this
  repository. Surface that at outline rather than absorbing an upstream change here.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-24-jwks-unknown-kid-refresh.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
