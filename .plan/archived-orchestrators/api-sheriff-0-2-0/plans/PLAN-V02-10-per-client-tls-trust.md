# PLAN-V02-10: Delete the Process-Global Truststore Override

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> The file name and `plan_id` keep the slug `per-client-tls-trust`; the plan itself is the
> single deliverable below.

## Objective

The integration stack still forces trust through a process-global truststore override:
`-Djavax.net.ssl.trustStore*` JVM arguments on every gateway service in
`integration-tests/docker-compose.yml`. The gateway no longer needs it. JWKS trust resolves
through the logical `jwks.tls_profile` mechanism (`JwksTrustProfileResolver`), and the BFF
back-channel client engine already receives its `SSLContext` from the same resolver.

Delete the override everywhere it is set, so each gateway service resolves trust the way a
deployed gateway does, and reconcile the documentation that shows the override.

This is integration-stack hygiene, not a defect fix: the shipped trust mechanism is neutral and
fail-closed, and the override lives in the integration stack rather than the shipped artifact.

## Deliverables

1. **Delete the process-global truststore override at every site.**
   - `integration-tests/docker-compose.yml` carries 37 trustStore-family arguments across 12
     gateway services: `api-sheriff`, `api-sheriff-mtls`, `api-sheriff-cookie`,
     `api-sheriff-cookie-2`, `api-sheriff-ws-admission`, `api-sheriff-plain-mgmt`,
     `api-sheriff-passthrough-empty`, `api-sheriff-no-certificate`, `api-sheriff-egress-verify-on`,
     `api-sheriff-egress-verify-off`, `api-sheriff-refresh`, `api-sheriff-cookie-refresh`.
     **Delete by service, not by line number, and re-grep at outline** — the count has grown every
     time it was measured, so treat the figures here as a lead and the grep as the authority.
   - `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/NoCertificatePlainHttpOptInIT.java`
     builds the identical `-Djavax.net.ssl.trustStore*` triplet on its own. It is a thirteenth
     site outside the compose file; retire it in the same change, or the override stays live.
   - Removing the override changes how every one of those services resolves trust. Coordinate
     with the benchmark lane on that discontinuity.
   - `doc/user/tls-scenarios.adoc` carries worked `-Djavax.net.ssl.trustStore*` examples that this
     change makes stale. Reconcile them.

`docker-compose*.yml` is a gate-requiring path, so this change runs the full pre-commit process.

## Out of Scope — Already in Place

Do not rebuild any of the following; each exists on `main`.

- **Client-engine `SSLContext` wiring.** `BffRuntimeProducer` passes
  `trustProfileResolver.resolveEgressProfile(...)` to `ClientConfiguration.builder().sslContext(...)`.
  This was once this plan's first deliverable; it is done.
- **The validation-side trust path.** `TokenValidatorProducer` takes `JwksTrustProfileResolver` by
  constructor injection, and the resolver refuses a `trust-all` bucket ahead of the anchor-free
  check. If this plan's change forces an edit there, report it as a finding rather than patching.
- **A positive bearer-validation integration test.** `BearerValidationIT` and
  `BearerSecurityFilterInteractionIT` cover it.
- **A behavioural test for `azpAudienceFallbackEnabled`.** Owned by other work; not this plan's.

## Claim Labels

- OBSERVED: the client-engine `SSLContext` wiring has landed — `BffRuntimeProducer.java` `resolveEgressProfile(OIDC_TLS_PROFILE_KEY, …)`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.java:539 .sslContext(trustProfileResolver.resolveEgressProfile(...))
- OBSERVED: the process-global truststore override spans 12 services in `integration-tests/docker-compose.yml` (37 trustStore-family arguments)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration-tests/docker-compose.yml: 12 services, 37 trustStore-family args
- OBSERVED: a 13th override site exists in `NoCertificatePlainHttpOptInIT.java` — HYPOTHESIS that it must be retired with the compose override (verify-at-outline)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: NoCertificatePlainHttpOptInIT.java constructs the identical trustStore triplet
- OBSERVED: `doc/user/tls-scenarios.adoc` exists and carries worked trustStore examples — this plan's documentation target
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/user/tls-scenarios.adoc present with worked trustStore examples

## Expected Surface

- OBSERVED: `integration-tests/docker-compose.yml` — the twelve gateway services
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/NoCertificatePlainHttpOptInIT.java` — the thirteenth override site
- OBSERVED: `doc/user/tls-scenarios.adoc` — the worked examples

No change to `api-sheriff/src/main/` is expected.

## Dependencies and Sequencing

- Depends on: none. The deletion is correct whatever `PLAN-V02-09` decides about the
  config-mapping direction, so this plan does not wait for it.
- Overlaps with: every plan that edits `integration-tests/docker-compose.yml` or
  `doc/user/tls-scenarios.adoc`; the disjointness gate decides at emit time.
- Adjacent to: GitHub issue #201 (`MtlsHandshakeIT` fails under `-Pjfr` only, fail-open on
  handshake rejection). It is mTLS handshake behaviour and lane-conditional, not trust wiring;
  `PLAN-V02-11` owns it. Do not adopt it here.
- Not release-gating.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-10-per-client-tls-trust.md" plan_id=plan-v02-10-per-client-tls-trust
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
