# PLAN-07: TLS Material Audit and the Trust Replaces-vs-Extends Contract

epic: deployment-configurability
workstream: WS-06

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

ADR-0025 settled that certificate, key and trust **material** is deployment-supplied and never
promoted into `gateway.yaml`. That ruling is correct and this plan does not re-open it. What the
ruling deliberately left open is the operator's side of the same boundary: **how material is
supplied, and what the gateway does when it is supplied wrongly.**

Two concrete gaps. First, the gateway never states what TLS it actually resolved on its **main**
listener — `ManagementPlainHttpAudit` does precisely that for the management interface and is the
design to copy, not to invent. Second, configuring trust through Quarkus **replaces** the platform
CA bundle rather than extending it, and nothing in the product says so. That matters more here than
in a typical Quarkus app: the shipped artifact is a **native binary on a distroless base**, so the
usual OS-level `update-ca-trust` route does not exist and the replacement is the *only* route — an
operator mounting a corporate CA silently loses trust for every public endpoint the gateway calls,
including a public IdP's JWKS.

Make the effective TLS and trust state observable at boot, and make the contract for supplying
material explicit enough that the replacement hazard cannot be walked into.

## ⛔ RE-SCOPED 2026-09-08 by the `cleanup` re-grounding pass — READ FIRST

**Half of this plan's second gap has been closed underneath it by PLAN-03 (#268) and PLAN-04 (#272).**
Verified at HEAD `4863c61`:

| Premise as staged | State now |
|---|---|
| *"nothing in the product says"* trust config **replaces** the platform CA bundle | ⛔ **Refuted.** `doc/LogMessages.adoc:75` declares **`ApiSheriff-119`** — a boot CONFIG record stating that `egress_tls.upstream_tls_profile`'s anchors **REPLACE** the JVM default trust store on the proxy, gRPC and WebSocket egress clients. `:76` declares `ApiSheriff-120` for the JWKS leg. |
| the `tls` package enumeration in claim 1 | ⛔ **Stale** — it omits `EgressTrustProfileResolver.java`, landed by PLAN-03. |
| *"no equivalent audit for the **main listener**"* | ✅ **Still true.** `ManagementPlainHttpAudit` remains the only `*Audit` class in the package. |

### What this plan is now

- **Deliverable 1 — the main-listener audit — is UNCHANGED and is now the plan's centre of gravity.**
  It is the part nothing else has touched.
- **Deliverables 2 and 4 SHRINK to the uncovered remainder.** The egress_tls replacement hazard is
  announced and documented; do not re-announce it. ⛔ **The live remainder is the fifth unpinned
  outbound leg** — `BffRuntimeProducer:208` builds `ClientConfiguration` with neither
  `verifyHostname` nor `sslContext`, carrying the `client_secret` through discovery, code exchange
  and refresh, secure only by an upstream default. See the epic's Open Defect. Settle at outline
  whether that leg belongs here or in its own plan.
- **Deliverables 3 and 5 are unaffected.**

⚠ **Re-read `ApiSheriff-119`, `-120` and ADR-0040/0041 before scoping** — they define the vocabulary
this plan must extend rather than duplicate. ⚠ **ADR ordinal is `0042`**; `doc/adr/` ends at `0041`.

## Deliverables

1. **Audit the resolved TLS material on the main listener**, modelled on `ManagementPlainHttpAudit`
   and sharing its central discipline: key the audit on **what was actually resolved**, never on a
   configuration key that "can be renamed, superseded, or silently ignored" and would then "report a
   comfortable fiction". State at boot, once, in the log an operator reads: whether the terminated
   HTTPS listener has key material, and which of the ADR-0017 topologies is live.
2. **Audit the effective outbound trust source.** Name whether the gateway is using the platform
   default bundle or an operator-supplied truststore, and *which one* — this is the single line that
   turns the replacement hazard from a silent failure into a visible one. Cover both routes the repo
   already exercises: the `quarkus.tls.*` bucket and a runtime `-Djavax.net.ssl.trustStore*` override.
   Decide WARN versus INFO deliberately and record why; `ManagementPlainHttpAudit`'s reasoning
   ("a plain-HTTP management port behind a trusted network boundary is a legitimate deployment, and
   the gateway must not block it") is the precedent for not over-escalating.
3. **Close the redirect-to-nothing hole.** `quarkus.http.insecure-requests=redirect` ships as the
   default; if no certificate is supplied, the HTTPS listener never starts and the gateway redirects
   plain-HTTP traffic to a port nothing is listening on. Decide the response — a boot refusal, a loud
   WARN, or an automatic degrade — and record why. ⛔ This is the seam PLAN-08 builds its mode on, so
   settle the *vocabulary* here even though PLAN-08 ships the mode.
4. **Document the material contract, replacement hazard first.** In `doc/configuration.adoc`, beside
   the existing deployment-bound material paragraph: what an operator supplies, through which
   variables, and — stated plainly rather than implied — that configuring trust **replaces** the
   default bundle. Give the concatenate-with-a-public-bundle recipe for the mixed case. State the
   native/distroless consequence explicitly: `update-ca-trust` does not exist in the shipped image and
   the JVM `cacerts` is fixed at image build time, so the runtime property is the supported route.
   Add the threat-model entry.
5. **Record the artifact trade-off without taking it.** Two structural alternatives were raised and
   are deliberately NOT in scope: shipping JVM rather than native as the primary artifact, and moving
   from distroless to UBI minimal so `update-ca-trust` exists. Both trade a smaller attack surface for
   an easier trust story. Write the trade-off down — ideally as an ADR — so the decision is on record
   as *considered and declined for now* rather than never examined. ⛔ Do not change the base image or
   the primary artifact in this plan.

## Claim Labels

- OBSERVED: `ManagementPlainHttpAudit` already implements the boot-time "name the effective mode"
  pattern for the MANAGEMENT interface, and keys it on resolved key material rather than on a config
  key, with the rationale stated inline — *"A configuration key can be renamed, superseded, or
  silently ignored, and an audit keyed on one would then report a comfortable fiction. The resolved key
  material cannot"* — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java:40-63`. It is
  `@ApplicationScoped`, observes startup, and actively invokes its own check to defeat the lazy-proxy
  hazard (lesson 2026-07-20-18-002) — read at `:75-95`. **This is the design deliverables 1 and 2
  extend; they must not invent a second pattern.**
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ManagementPlainHttpAudit.java still carries the config-key-renamed rationale, still @ApplicationScoped
- OBSERVED: there is **no equivalent audit for the main listener or for outbound trust** — the
  `tls` package contains `ClientHelloSniParser`, `ManagementPlainHttpAudit`, `MtlsServerCustomizer`,
  `PassthroughRelay`, `SniFrontListener`, `TlsEdgeProducer` and `TlsServerCustomizer`, and only the
  management one audits. Asserted absence; re-derive at HEAD.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: tls/ package now also contains TerminatedListenerTlsAudit.java and DefaultTrustSourceAudit.java, closing both halves this claim asserted were absent
- OBSERVED: the gateway supplies its server identity through the **legacy**
  `quarkus.http.ssl.certificate.*` form, not the TLS registry — stated inline at
  `api-sheriff/src/main/resources/application.properties:92-94`, which also records that this split
  already caused a real defect: `quarkus.tls.protocols` / `cipher-suites` / `alpn` *"never reached the
  listener"* because Quarkus consults them **only** when the certificate arrives via
  `quarkus.tls.key-store.*`. The fix was to route policy through a customizer; **the underlying
  legacy-versus-registry split remains** and is what deliverable 4 must describe honestly.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties still states the legacy ssl.certificate form and the alpn-never-reached-the-listener defect, now at :198-201
- OBSERVED: ⛔ a **default** `quarkus.tls.key-store.*` bucket must never be declared, because the
  management interface falls back to the default registry bucket and a populated one *"would silently
  switch management to HTTPS through a path no configuration file mentions (upstream quarkus issue
  43380)"* — read at `application.properties:80-84`. Any trust work here must not drift into that.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties still states the never-populate-the-default-bucket rule with the quarkus issue 43380 reference
- OBSERVED: both trust routes are already exercised by the integration stack, so neither is
  hypothetical — `integration-tests/docker-compose.yml:195-197` passes
  `-Djavax.net.ssl.trustStore` / `TrustStorePassword` / `TrustStoreType` and `:260` sets
  `QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH`, with the comment at `:192` recording that the
  *"GraalVM/Mandrel native executable honors runtime `-Djavax.net.ssl.trustStore*` for the default"*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: integration-tests/docker-compose.yml still passes javax.net.ssl.trustStore*/TrustStorePassword and QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH
- OBSERVED: the shipped main image is **distroless** — `Dockerfile.native` is built `FROM
  quay.io/quarkus/quarkus-distroless-image` and its header states *"no shell, no package manager"*, so
  `update-ca-trust` and any OS-level trust extension are unavailable by construction. Read at
  `api-sheriff/src/main/docker/Dockerfile.native:1-9`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: Dockerfile.native header still states the distroless minimal-attack-surface line
- OBSERVED: `deployment/compose-sample/docker-compose.yml:184-190` supplies all four
  `QUARKUS_*_SSL_CERTIFICATE_*` variables unconditionally, so the shipped operator example presents
  certificates as mandatory and demonstrates no other shape.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: deployment/compose-sample/docker-compose.yml still supplies all four SSL_CERTIFICATE variables unconditionally
- OBSERVED: `SingleSourceTlsContractTest` pins the server-TLS surface and classifies
  `certificate` / `key-store` / `trust-store` / `keystore` / `truststore` / `credentials-provider` as
  MATERIAL — deliberately outside the neutrality bar — read at
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/SingleSourceTlsContractTest.java:88-95`.
  This plan works on the material side and must not trip that bar.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SingleSourceTlsContractTest.java untouched, MATERIAL_MARKERS still lists cert/key-store/trust-store/keystore/truststore/credentials-provider
- HYPOTHESIS: with `insecure-requests=redirect` and no certificate supplied, the HTTPS listener never
  starts and plain-HTTP traffic is redirected to a dead port — confirm/refute by booting the container
  with the four `QUARKUS_*_SSL_CERTIFICATE_*` variables unset and observing the actual response
  (verify-at-outline). This is the load-bearing claim for deliverable 3 and the premise PLAN-08
  depends on.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ServerTlsDeclarationGate.java now implements exactly this boot refusal for the undeclared-plain-HTTP case
- HYPOTHESIS: a Quarkus-configured truststore **replaces** the platform bundle rather than extending
  it, so an operator adding only an internal CA loses trust for public CAs — confirm/refute against
  the resolved Quarkus version's TLS-registry behaviour AND a live outbound call to a public-CA host
  with an internal-CA-only truststore configured (verify-at-outline). ⛔ This claim reached this spec
  from a **third-party chat transcript** the operator pasted; it is recorded as a lead and must be
  settled against the implementation before any documentation asserts it.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/LogMessages.adoc ApiSheriff-119/122/125/126 all explicitly document REPLACE (not extend) semantics
- HYPOTHESIS: a native binary's default `cacerts` is captured at image build time, so replacing the
  file in the runtime image has no effect while a runtime `-Djavax.net.ssl.trustStore` override does —
  confirm/refute against the actual native image (verify-at-outline). The repo's own compose comment at
  `integration-tests/docker-compose.yml:192` asserts the second half; the first half is unverified here.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/configuration.adoc:905 states native-image trust material is captured at build time
- Verify-first clause: settle every hypothesis against the built native image and a live boot — never
  against the pasted chat, a standards doc, or this spec's prose. ⛔ The chat is **not evidence**: it is
  the reason to look. A documentation deliverable asserting the replacement semantics before
  deliverable 4's own check has confirmed them would ship a claim this project never verified.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/` — the new audit, beside `ManagementPlainHttpAudit`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` — the audit's `LogRecord` constants (INFO 001-099 / WARN 100-199)
- OBSERVED: `doc/LogMessages.adoc` — the new identifiers
- OBSERVED: `doc/configuration.adoc` — the material contract and the replacement hazard
- OBSERVED: `doc/security-threat-model.adoc` — the residual-risk entry
- HYPOTHESIS: `api-sheriff/src/main/resources/application.properties` — only if deliverable 3's response needs a key (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/` — the audit test, driven through the real startup-event path per the lazy-proxy lesson (verify-at-outline)
- HYPOTHESIS: `doc/adr/` — the deliverable-5 trade-off ADR (verify-at-outline: next free ordinal)

## Dependencies and Sequencing

- Depends on: PLAN-02 and PLAN-06 for sequencing only — all three touch `doc/configuration.adoc`,
  the shipped `gateway.yaml` files and `deployment/compose-sample/`.
- **Blocks: PLAN-08, hard.** PLAN-08's mode is the degenerate "no server certificate" case of this
  plan's contract, and its boot guard is deliverable 3's. Settling the vocabulary twice is the defect
  this ordering prevents.
- Overlaps with: PLAN-03 and PLAN-04 on `doc/configuration.adoc` and `doc/security-threat-model.adoc`.
  ⚠ PLAN-03's deliverable 2 is the naming authority for the outbound hostname-verification vocabulary;
  trust-source naming here must read as part of the same story, not a competing one.
- Adjacent to: `MtlsServerCustomizer` and the inbound client-cert surface. Untouched — that is the
  gateway verifying a client, not the gateway's own identity or its outbound trust.
- Adjacent to: `JwksTrustProfileResolver`. Untouched, and its posture is the model: it **refuses** an
  unresolvable profile rather than falling back to default trust, and refuses a `trust-all` bucket
  outright. Nothing here may become a softer second path to the same place.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-07-tls-material-audit-and-trust-contract.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
