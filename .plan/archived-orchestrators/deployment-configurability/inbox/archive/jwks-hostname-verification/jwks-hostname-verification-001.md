envelope_version=1
sender_type=plan
sender_id=jwks-hostname-verification
epic=deployment-configurability
kind=landing
created=2026-09-07T21:30:01Z
revision=1
amended=2026-09-07T21:31:18Z

PLAN-04 landed: `egress_tls.jwks_verify_hostname` now has a production reader, merged via the merge queue as 054b3e4.

```landing-facts
schema=landing-facts/1
plan_id=jwks-hostname-verification
epic=deployment-configurability
workstream=WS-03
pr=272
merge_state=merged
merge_commit=054b3e4e5102e885df55a13a4abeadde516e534a
deliverables_total=4
deliverables_done=4
total_tokens=4027207
total_wall_seconds=26940
adr_ordinal=0041
build_decision=build
footprint=api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java,api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java,api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EgressTlsConfig.java,api-sheriff/src/main/resources/schema/gateway.schema.json,api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/SanMismatchedJwksServer.java,api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducerTest.java,api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java,api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java,doc/LogMessages.adoc,doc/adr/0040-Upstream_hostname_verification_is_a_global_egress-TLS_knob_bound_at_client_construction.adoc,doc/adr/0041-The_JWKS_hostname-verification_knob_is_the_librarys_own_passthrough_and_its_sslContext_collision_is_refused_at_boot.adoc,doc/configuration.adoc,doc/security-threat-model.adoc,pom.xml
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,pre-submission-self-review:done,finalize-step-simplify:done,finalize-step-security-audit:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:skipped,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.record-metrics.total_tokens=4027207
step.record-metrics.total_wall_seconds=26940
step.finalize-step-sync-baseline.action=noop
step.finalize-step-sync-baseline.upstream_commit_count=0
loop_back_iterations=1
dependency_bump=de.cuioss.sheriff.token:0.9.4->0.9.5-SNAPSHOT
```

## Mechanism — the staged spec's re-scope was itself re-scoped

The spec's ⛔ block retired option (b′) because cui-http 3.0 ships
`HttpHandlerBuilder#verifyHostname(boolean)`. Refine REFUTED that at the resolved dependency: the
gateway never holds a cui-http `HttpHandlerBuilder` — it hands an `SSLContext` to token-sheriff's
`HttpJwksLoaderConfigBuilder`, which at the resolved **0.9.4** forwards no such method. The true
resolution is a third position neither held: `verifyHostname(boolean)` exists on token-sheriff's
OWN builders (JWKS and well-known legs) from **0.9.5**. The plan bumps `version.token-sheriff` to
`0.9.5-SNAPSHOT` and uses that passthrough. No `X509ExtendedTrustManager` was hand-rolled, so the
TokenSheriff cross-repo duplication the spec dissolved stays dissolved.

## Collision settled as option (i)

token-sheriff re-raises cui-http's guard as its own on BOTH builders: `build()` throws
`IllegalArgumentException` when `verifyHostname(false)` meets `sslContext(...)`.
`TokenValidatorProducer` supplies exactly such a context for any issuer declaring
`jwks.tls_profile`. Refused at boot in `toHttpJwksLoaderConfig` with
`GatewayException(CONFIG_INVALID)`, ahead of the library's own throw. (ii) and (iii) recorded as
rejected in ADR-0041.

## Claim corrections owed back to the epic

- **Claim 11's stamped verdict `contradicted @ a8c9834` is right; its EVIDENCE is wrong.** It
  credits cui-http 3.0's knob, which is real but unreachable from this gateway. What resolves the
  plan is token-sheriff 0.9.5's passthrough. A sibling plan reasoning from that stamp will reach a
  wrong mechanism.
- **The spec's issuer-shape table is REINSTATED as correct** after being briefly refuted.
- **PLAN-03's deliverable 2 fully covered the JWKS naming** — no gap, contingency did not fire.

## Countable remainders — recorded, not closed

- **A fifth outbound https leg is unpinned.** `BffRuntimeProducer:208` builds `ClientConfiguration`
  with neither `verifyHostname` nor `sslContext`, dialled by `DiscoveryResolver` /
  `TokenEndpointClient` / `RefreshFlow` — discovery, code exchange, refresh — carrying the
  `client_secret`. Secure today only via the upstream `@Builder.Default true`, the same reliance
  `TokenValidatorProducer` refuses for the lower-value JWKS leg. Documented as a countable
  remainder; the code half is OUT of footprint and wants its own plan.
- **`RotationResult` widened 5→7 components in 0.9.5**, exposing a `scopeDelta` signal nothing
  reads. A NARROWED/BROADENED scope on refresh is unobserved. Fixture migrated, signal not adopted.
- **No deployment-activation guard** — the knob is proven at unit level only, by operator decision.

## Accepted risk

`0.9.5-SNAPSHOT` is a mutable coordinate on the authentication path of every build cut from `main`.
Operator shown twice, chose to proceed. Mitigations: snapshot repo sets
`<releases><enabled>false</enabled>`; pom carries an explicit REMOVAL CONDITION. Residual gap:
nothing in the build REFUSES a release that still resolves a SNAPSHOT — the condition is prose.
Filed by the security audit (`651d5b`) and CodeRabbit (`6cd491`, Major); `accepted` /
`taken_into_account`.

## Verification

CI green at `efb16fa`. CodeRabbit reviewed twice (`a29b388` → 4 findings, `c45994e` → 1); all fixed
or explicitly accepted. Sonar 0 new-code issues at final HEAD, confirmed. Whole-tree quality-gate +
verify green, 202 tests. Sourcery rate-limited (optional, non-gating); cuioss-review-bot
participated with no actionable finding.
