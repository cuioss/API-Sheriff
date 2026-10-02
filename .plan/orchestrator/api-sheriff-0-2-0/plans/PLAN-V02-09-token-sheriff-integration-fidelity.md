# PLAN-V02-09: Token-Sheriff Integration Fidelity — Health Coverage and the Config-Mapping Question

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

API Sheriff runs **two parallel token-validation mechanisms**. The request path builds a
`@GatewayValidator`-qualified `TokenValidator` from `gateway.yaml`'s `token_validation` block via
`TokenValidatorProducer`; the `token-sheriff-validation-quarkus` extension independently builds an
**unqualified** `TokenValidator` from its own `sheriff.token.issuers.<name>.*` property namespace,
which this gateway never populates. Because that namespace is empty, the extension's health probes
reported `DOWN` and its scheduled metrics collector failed on every tick, so the gateway excludes
those beans by type (`quarkus.arc.exclude-types` in `application.properties`, recorded in ADR-0027:
*the token-validation extension's unqualified beans are excluded, not accommodated*).

The exclusion was correct as a fix and is well-reasoned in place. **The question this plan answers
is whether the shape it froze is still the right one** — and the ground under that question has
moved: both upstream changes that would let the gateway stop excluding are now released and are in
the pinned artifact. So this plan decides whether to adopt them, and closes the two observability
gaps that remain whichever way it decides.

## Where things stand

Read this before scoping; it is the state on `main`, and each item is either a fact to build on or
work already done.

- **token-sheriff is pinned at `0.9.6`.**
- **The exclusion is in force**, unconditional and in no profile. It covers the extension's
  `…health.*` and `…metrics.*` packages. Its value has **four carriers**, and a change touching only
  the properties file leaves three behind: `application.properties`,
  `ShippedApplicationPropertiesTest` (which asserts the exact property line),
  `DefaultProfileReadinessTest` (`EXTENSION_HEALTH_PACKAGE`), and ADR-0027's prose. The two tests are
  a tripwire doing its job: the correct response to their failure is a deliberate assertion update,
  never a loosened matcher.
- **The exclusion's scope cannot be derived programmatically.** Two implementations were attempted
  and neither works: the producer stays resolvable even with every reaching bean excluded, and bean
  enumeration is a membership snapshot ADR-0030 forbids. `ExtensionUnqualifiedBeanExclusionTest`
  covers the scheduler-driven case only. The consequence is a standing, unautomatable obligation to
  re-read the exclusion's scope on every token-sheriff upgrade. Do not re-derive this.
- **Upstream `cuioss/TokenSheriff#641` is closed and released** (in 0.9.5 and 0.9.6). It asked the
  extension's health and metrics beans to observe the produced `TokenValidator` as a CDI dependency
  instead of reconstructing issuers from the property namespace, and to degrade honestly — report
  *not configured* and go idle, warning once — when they find no property-based issuers.
- **Upstream `cuioss/TokenSheriff#617` is closed and released.** The plain `token-sheriff-validation`
  0.9.6 jar ships `META-INF/native-image/…/reflect-config.json`, so native reflection metadata no
  longer arrives only through the Quarkus extension's build step.
- **Live JWKS readiness is done.** `GatewayReadinessCheck` reads a live, non-fetching, per-issuer
  `IssuerKeySetStatus` through `RetryingJwksLoader`.
- **The client engine's `SSLContext` is wired.** `BffRuntimeProducer` passes
  `trustProfileResolver.resolveEgressProfile(...)` to `ClientConfiguration.builder().sslContext(...)`.
  Not a deliverable here.
- **The `application.properties` rationale block is current** and already names the metrics gap.
- **Still open:**
  - The token validator's `SecurityEventCounter` is bound to no meter. `SheriffMetrics` binds only
    cui-http's URL-security counter, so JWT signature failures, expiries, issuer mismatches and JWKS
    failures are unmetered.
  - GitHub issue [#174](https://github.com/cuioss/API-Sheriff/issues/174) is open. The false claim
    it reported is gone — the `issuer_reachability` datum now always reads `unverified` — but no
    probe attempts discovery, so the feature the issue asks for does not exist.
  - ADR-0027 carries no reference to `#641` or `#617`. Its *"Escalate to the extension…"*
    alternative still reads as an un-actioned recommendation, and its risk entry *"the exclusion
    outlives its justification"* names exactly what those two releases make retirable.

## Deliverables

1. **Establish what the extension's beans actually do at the pinned version.** Read the shipped
   artifact, not its description and not the issue threads: `health.JwksEndpointHealthCheck`,
   `health.TokenValidatorHealthCheck` and `metrics.JwtMetricsCollector` in
   `token-sheriff-validation-quarkus` 0.9.6. Report, per bean: what it reads, when it can fail,
   whether it observes a **live** condition or a construction fact, and — the question that decides
   D2 — **whether it now observes this gateway's produced `@GatewayValidator` validator or still
   needs the property namespace.** Look for any further bean of the same shape rather than assuming
   the list is complete. If a bean under-covers, that is an upstream finding to report, not
   something to work around here.

   A closed issue is not a released capability. Verify against the resolved jar.

2. **Decide the config-mapping question, with the answer written down either way.** The deliverable
   is a reasoned verdict, not a presupposed one. Candidate directions:
   - **Adopt the upstream fix and delete the exclusion.** If D1 confirms the beans observe the
     produced validator, they see this gateway's real issuers with no projection at all, the
     exclusion has nothing left to exclude, and the standing re-read obligation disappears with it.
     This is the default candidate; it still has to be shown to work, not assumed.
   - **Map** — project `gateway.yaml`'s `token_validation` onto `sheriff.token.issuers.*` through a
     `ConfigSource`, letting the extension build the validator. The precedent exists in this
     codebase: `NeutralTlsConfigSource` projects the neutral `management` block onto a `quarkus.*`
     key.
   - **Map and drop the extension entirely** — available now that the plain library carries its own
     native-image metadata.
   - **Keep the re-implementation and the exclusion**, and close the remaining gaps in the gateway's
     own code.
   - **A hybrid** — the extension for observability, the qualified producer for the request path.

   **`NeutralTlsConfigSource`'s own javadoc argues against blind projection where a live CDI seam
   exists**, and that is the strongest counter-argument to the mapping directions: it deliberately
   does *not* project the sibling `tls` block because `TlsServerCustomizer` can observe what Quarkus
   actually built. `TokenValidatorProducer` is such a live seam. Weigh that explicitly — and weigh it
   against the measured cost of keeping the exclusion: a maintenance obligation that grows with every
   upstream release and has already been missed once.

   Two properties any direction must preserve, or it is a regression: the fail-closed
   `jwks.tls_profile` resolution (`JwksTrustProfileResolver` refuses a name it cannot resolve rather
   than falling back to default trust), and the eager boot-time validation in
   `TokenValidatorProducer.onStartup`, which aborts startup on a misconfigured issuer.

   Record the verdict in ADR-0027 or in a record superseding it, and add the `#641` and `#617`
   references either way. **Do not leave the ADR stating that the escalation is owed when it has
   been made and answered.**

3. **Close the two remaining observability gaps.** Both have the same shape — the gateway
   re-implements a mechanism and then cannot observe it — and both are answered differently
   depending on D2's verdict, so they are one deliverable. If D2's direction lets the extension's own
   beans satisfy either, say so rather than building it twice.

   - **Validation metrics.** Bind the token validator's `SecurityEventCounter`. The seam, if the
     gateway keeps its own producer, is a second binding beside `SheriffMetrics.bindSecurityEventCounter`
     for the qualified validator's counter. Note that the gateway's metric surface is route-shaped
     today and carries no token-validation dimension at all.
   - **Discovery reachability — issue #174.** Two different clients reach the same issuer:
     the JWKS loader fetches the key set, while `DiscoveryResolver` fetches
     `.well-known/openid-configuration`, and **every BFF flow needs the latter**. Readiness never
     touches it. The symptom is the severe part: `/q/health` returns `UP` on an instance where every
     BFF path answers `500`, for ordinary operational reasons — issuer down, DNS, certificate, egress
     policy.

     **Preserve laziness while closing it.** Discovery is resolved on first use, which is deliberate
     and good: the gateway boots without the IdP. A fix that eagerly resolves at boot to make
     readiness honest would trade one correct property for another. The shape that satisfies both is
     a readiness probe that *attempts* discovery and reports its outcome without making boot depend
     on it — state which property you chose and why.

   **CLOSE [#174](https://github.com/cuioss/API-Sheriff/issues/174) WHEN THIS DELIVERABLE LANDS** —
   comment on the issue naming the PR and the merge commit, then close it. A PR body that merely
   *mentions* an issue does not link or close it: use a closing keyword, or close it explicitly after
   the merge. If the plan stops at "the false claim is gone" without adding the probe, say so and
   leave the issue open.

4. **Reconcile the outcome with every carrier of the current design.** The rationale block in
   `application.properties`, the two tests that pin the exclusion value, ADR-0027, and the
   three-layer documentation all describe today's mechanism. Whatever this plan concludes, each is
   updated to match. A stale rationale next to a changed mechanism is worse than no rationale,
   because it is believed.

5. **Re-test the two upstream assumptions against the pinned artifact, and file nothing new unless a
   gap is found.**
   - *"The extension must stay for native image."* It no longer must: the plain library carries its
     metadata. Confirm the metadata is sufficient for this application's native build before D2
     relies on dropping the extension.
   - *"`JwtMetricsCollector` fails hard on an empty namespace."* The upstream change asked for
     warn-once-and-go-idle. Confirm from the jar what 0.9.6 actually does. If the gateway keeps the
     exclusion, this matters less for API Sheriff but remains correct for every other consumer.

**Split-guard.** Five deliverables, under the threshold. If a split is ever forced, the line is
D1+D2 (what the extension does and the decision that follows) | D3+D4+D5.

## Claim Labels

- OBSERVED: token-sheriff is pinned at `0.9.6` — `api-sheriff/pom.xml`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: api-sheriff/pom.xml:63 version.token-sheriff 0.9.6
- OBSERVED: the `quarkus.arc.exclude-types` exclusion of token-sheriff health/metrics beans is unconditional — `application.properties`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: application.properties:345 exclusion unconditional, no profile scoping
- OBSERVED: readiness already reads live `IssuerKeySetStatus` — `GatewayReadinessCheck`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: GatewayReadinessCheck imports IssuerKeySetStatus :19, fields :141/:153
- OBSERVED: the token validator's `SecurityEventCounter` is bound to no meter; `SheriffMetrics` binds only cui-http's counter — D3's metrics half
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SheriffMetrics binds only de.cuioss.http SecurityEventCounter; validator counter feeds SignatureOnlyTokenVerifier only
- OBSERVED: `ADR-0027` carries no `#641`/`#617` reference — `doc/adr/0027-*.adoc`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: grep #641|#617 in doc/adr/0027-*.adoc: no match
- OBSERVED: `token-sheriff-validation-0.9.6.jar` ships `META-INF/native-image/.../reflect-config.json` — `unzip -l` of the resolved artifact
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: unzip -l token-sheriff-validation-0.9.6.jar lists META-INF/native-image/.../reflect-config.json

## Expected Surface

- `api-sheriff/src/main/resources/application.properties` — the exclusion and its rationale block
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`,
  `JwksTrustProfileResolver.java`, `GatewayValidator.java`
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`,
  `SheriffMetrics.java` — the discovery and metrics write sites
- Possibly new: a `ConfigSource` under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/`, if deliverable 2 selects a mapping direction
- `doc/` — the three-layer documentation, and ADR-0027 or its successor
- Read-only: the `token-sheriff-validation-quarkus` and `token-sheriff-validation` 0.9.6 artifacts

## Dependencies and Sequencing

- Depends on: **`PLAN-V02-08` and `PLAN-V02-12`.** All three write `BffRuntimeProducer` and the
  `oidc` block. The chain is `PLAN-V02-08` → `PLAN-V02-12` → this plan, strictly sequential, never
  concurrent.
- Sequence after `PLAN-V02-01` (ADR-0005 reversal) where possible. That plan's premise is that
  hand-rolled equivalents were built where a platform mechanism already existed, and this plan asks
  the same question of token-sheriff. Its ADR verdict sets the standing rule D2 should apply rather
  than re-derive. If it has not landed when this plan reaches outline, record that the verdict could
  not be read rather than assuming one.
- Not concurrent with `PLAN-V02-04` (ADR corpus audit), which is told not to pre-empt this plan's
  re-examination of ADR-0027.
- Not release-gating. The exclusion is a defensible shipped posture, pinned by a fitness function.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-09-token-sheriff-integration-fidelity.md" plan_id=plan-v02-09-token-sheriff-integration-fidelity
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
