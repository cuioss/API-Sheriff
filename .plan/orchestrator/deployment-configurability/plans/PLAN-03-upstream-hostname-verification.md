# PLAN-03: Configurable Hostname Verification — Upstream Dial (TLS Termination Mode)

epic: deployment-configurability
workstream: WS-03

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

When API Sheriff runs in **TLS termination mode** — the ADR-0017 branch where the gateway terminates
the client connection and re-dials the upstream itself — it verifies the upstream certificate's
hostname unconditionally, and no configuration can change that. Internal DNS names, Docker service
names and SAN-mismatched internal CAs all fail with no operator recourse. Make hostname verification
configurable on the upstream dial, **defaulting to ON**. The operator's qualifier "at least for the
TLS termination mode" is read literally: passthrough connections are opaquely L4-relayed and their
certificates are never inspected by the gateway, so they are out of scope by construction, not by
omission. Research the standard mechanism first — Vert.x exposes `setVerifyHost(boolean)` as a
first-class option, so this is expected to be a config-model and binding change rather than bespoke
TLS code.

## Deliverables

1. **Research and record the mechanism**, confirming that `setVerifyHost(false)` on the upstream
   client/request options is the supported Vert.x lever, what it does and does not relax (hostname
   matching only, versus chain trust), and where in the dial path it must be applied so every
   terminated egress honours it — HTTP proxying, gRPC dispatch and WebSocket relay alike.
2. **Define the hostname-verification vocabulary for BOTH outbound surfaces, and implement the
   upstream half.** Per ADR-0025 this is TLS **policy**, so it belongs in the gateway.yaml config
   model, not in `application.properties`. Default ON. Decide and record whether it is global (`tls`
   block) or per-upstream/per-route, and justify the choice — per-route is the tighter blast radius,
   global is the simpler contract.

   ⛔ **This deliverable is the single naming authority for the whole feature.** PLAN-04 covers the
   JWKS back-channel and is *implement-only* against what is decided here — it re-decides nothing.
   So settle here, in one pass: the key name(s), the value shape, the default, where the JWKS-side
   key sits relative to the upstream-side one, the shared documentation section, and the shared
   `T-TLS` threat-model entry. Hostname verification is ONE feature over two surfaces; two
   independently-named knobs for it would be the defect this deliverable exists to prevent.
   Write the JWKS-side key into the vocabulary **specification** (schema, docs, config model as
   appropriate) even though PLAN-04 implements its binding — a name agreed after the fact is a name
   agreed twice.
3. **Bind it on every terminated egress path** — the `DispatchStage` upstream request options, the
   `WebSocketRelayStage` relay options, and the `GatewayEdgeRoute` HTTP/2 client options. A knob that
   covers two of three paths is worse than none, because the uncovered path fails differently.
4. **Test it** — a matched positive and negative control against a backend whose certificate does not
   match the dialled name: **rejected** with the default, **accepted** with the knob off. The
   integration stack already ships a SAN-mismatched topology (`passthrough-backend` carries
   `CN=passthrough-backend` and `SAN DNS:passthrough.test.example`), which is a ready-made fixture.
5. **Document it with the security framing explicit** — the default, what turning it off exposes
   (hostname matching only, not chain trust), the topologies where it is legitimate, and an explicit
   statement that it does not and cannot affect passthrough connections. Author the section to cover
   **both** surfaces from the start, marking the JWKS half as landing in PLAN-04, so the two never
   appear as two separately-described features.
6. **Move cui-http to 3.0 by an explicit property override, and absorb the API change atomically.**
   ⛔ **FOLDED IN 2026-09-06 by operator decision ("API updates are part of it"), then RE-SCOPED the
   same day to decouple it from the parent release.** Set the version in this project rather than
   waiting for `cui-java-parent` 1.6.3:

   - `pom.xml` `<properties>` — add `<version.cui.http>3.0</version.cui.http>`
   - `SecurityConfigurations.java` § `builderSeededFrom` — add the new
     `.component(preset.component())` calls the grown record requires
   - `GatewayEdgeRouteTest.java:521` — `int copiedByBuilderSeededFrom = 24;` → the new count
   - `SecurityConfigurations.java:47` — the Javadoc's *"leaves the other twenty-three"* follows

   ✅ **Why the override rather than the parent bump.** `cui-java-bom` manages `cui-http` at
   `${version.cui.http}` (`cui-java-bom-1.6.2.pom:24` declares `2.2`, `:73-74` manages the artifact
   through it), so a one-line property override moves cui-http **without** taking the parent bump.
   That decouples this deliverable from an **unreleased** 1.6.3, shrinks the blast radius to the one
   artifact whose API actually changed, and is idiomatic here — `pom.xml` already carries three
   project-owned pins (`version.quarkus`, `version.token-sheriff`, `version.json-schema-validator`).
   **cui-http `3.0` is present in the local repository**, so it resolves today.

   ⛔ **It is an OVERRIDE, not a pin, and the root pom's own comment makes that distinction — honour
   it.** `version.quarkus` is a *pin* because the parent chain declares no Quarkus version at all;
   `version.cui.http` is an *override* because the parent **does** declare `2.2`. Comment it as such,
   and state its **removal condition**: ⚠ **delete the override once the resolved parent manages
   cui-http at ≥ 3.0.** Left in place it silently holds the artifact at 3.0 forever, including past a
   future 3.1 — an override that outlives its reason is how a dependency quietly stops tracking.

   ⛔ **The property change and the three source edits are ONE commit, not a sequence.** The tripwire
   asserts **equality** against the live record, so raising the constant while cui-http is still 2.2
   turns the test red immediately, and moving the version without extending the copy turns it red the
   other way. No ordering is green in between.

   ⛔ **The tripwire is doing its job — do NOT "fix" it by deriving the count.** A dropped component
   silently reverts to the `defaults()` policy the builder starts from: a real posture regression with
   no other failing test. Deriving the count would delete exactly the guard that caught this.

   ⚠ **The new component count is UNVERIFIED here.** Another repository's build reported the record
   growing 24 → 26; that number was relayed, never read against a 3.0 artifact in this checkout.
   **Read `SecurityConfiguration.class.getRecordComponents()` at 3.0 and use what it says.** If the
   growth is not two components, deliverable 6 changes size and the split guard below applies.

## Claim Labels

- OBSERVED: `setVerifyHost` is called **nowhere** in the repository — a repo-wide grep over `*.java`
  returned zero hits. Asserted **absence**; carries the same verification obligation as a presence.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: setVerifyHost is now called extensively (GatewayEdgeRoute.java:430,1555); verifyHostname is a first-class config surface
- OBSERVED: the upstream dial sets TLS but not verification —
  `DispatchStage:243` calls `.setSsl("https".equalsIgnoreCase(upstream.scheme()))`,
  `WebSocketRelayStage:147` calls `.setSsl(HTTPS.equalsIgnoreCase(upstream.scheme()))`, and
  `GatewayEdgeRoute:1294-1296` builds `new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)`
  then `.setSsl(true).setUseAlpn(true)` — read at those three files and lines. These are the three
  binding sites deliverable 3 must cover.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: GatewayEdgeRoute.clientFor now wraps every client via egressTlsBound(options, verifyHostname, trustOptions) which calls setVerifyHost
- OBSERVED: ADR-0017 defines the termination/passthrough split — *"A dedicated raw-TCP front listener
  owns the public TLS port, reassembles the ClientHello, and by SNI either opaquely L4-relays a
  passthrough match to its resolved backend or hands the still-encrypted connection to an internal
  terminating HTTPS listener that solely owns TLS termination and mTLS"* — read at
  `doc/adr/0017-Accept-time_SNI_split_at_a_dedicated_front_listener_passthrough_relays_opaquely_at_L4_everything_else_terminates_internally.adoc`
  § its summary line. This is why the operator's scoping qualifier is precise rather than vague.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ADR-0017 summary line quoted verbatim, unchanged
- OBSERVED: the front listener is started ONLY when gateway.yaml declares `tls.passthrough_sni`;
  when empty the terminated Quarkus HTTPS listener keeps the public port and the default topology is
  single-listener — read at `api-sheriff/src/main/resources/application.properties` § the accept-time
  TLS edge block. So in the DEFAULT topology every connection is terminated and this knob governs all
  egress.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: front-listener-only-on-tls.passthrough_sni text still stated, now at application.properties:208
- OBSERVED: `TlsConfig` is the gateway.yaml TLS policy record, carrying `minVersion`,
  `cipherSuites`, `alpn` and a nested `Mtls(boolean enabled, String clientCa)` — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/TlsConfig.java:39-65`. This is the
  natural home for a policy knob.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: TlsConfig.java still carries minVersion/cipherSuites/alpn/nested Mtls(enabled,clientCa)
- OBSERVED: `UpstreamConfig` carries `path`, `connectTimeoutMs`, `readTimeoutMs`, `retry`,
  `notModified`, `circuitBreaker` and no TLS field at all — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/UpstreamConfig.java:41-47`. A
  per-upstream knob would be a new field there.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: UpstreamConfig.java still carries no TLS field
- OBSERVED: ADR-0025's boundary rule — gateway.yaml names TLS policy neutrally; ports and trust
  material stay deployment-supplied — is stated inline at
  `api-sheriff/src/main/resources/application.properties` § the HTTP/HTTPS block.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SAN-mismatched passthrough-backend fixture still present at integration-tests/docker-compose.yml
- OBSERVED: a SAN-mismatched fixture already exists — `passthrough-backend` is an nginx service
  serving HTTPS on 8443 with *"its OWN self-signed certificate (CN=passthrough-backend)"*, and the
  gateway carries the network alias `passthrough.test.example` — read at
  `integration-tests/docker-compose.yml` § the `passthrough-backend` service and § the gateway's
  `networks.aliases`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: hostname-matching-only distinction now documented extensively across EgressTlsConfig.java, configuration.adoc, security-threat-model.adoc
- HYPOTHESIS: Vert.x `setVerifyHost(false)` relaxes hostname matching WITHOUT relaxing
  certificate-chain trust, so the knob is narrower than a trust-all — confirm/refute at the Vert.x
  `ClientOptionsBase` / `HttpClientOptions` implementation for the pinned version, exercised by
  deliverable 4's control pair (verify-at-outline). If refuted, the security framing in deliverable 5
  changes materially.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: DispatchStage/WebSocketRelayStage per-request setSsl sites remain unchanged, verification bound only at client level
- HYPOTHESIS: `RequestOptions.setSsl(...)` at the two stage sites inherits the client-level
  `HttpClientOptions` verification setting, so binding the knob at the client is sufficient and the
  per-request sites need no change — confirm/refute at those three call sites and the Vert.x request
  option resolution (verify-at-outline). This decides whether deliverable 3 is one edit or three.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: RequestOptions.setSsl sites untouched, client-level HttpClientOptions.setVerifyHost governs verification
- Verify-first clause: settle both hypotheses against the Vert.x implementation and a live dial, never
  against documentation alone. A refutation of the second forces all three binding sites to change
  and re-scopes deliverable 3.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: GatewayEdgeRouteTest.java:585 tripwiresOnSecurityConfigurationComponentDrift now declares copiedByBuilderSeededFrom = 27, not 24
- OBSERVED: the drift tripwire exists and hard-codes the copy count — read at
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` §
  `tripwiresOnSecurityConfigurationComponentDrift` (`:519`), whose `:521` declares
  `int copiedByBuilderSeededFrom = 24;` and `:524` compares it to
  `SecurityConfiguration.class.getRecordComponents().length`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: SecurityConfigurations.java builderSeededFrom now makes 27 preset.X() calls, Javadoc says "the other twenty-six"
- OBSERVED: the copy really does copy 24 — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityConfigurations.java` §
  `builderSeededFrom` (`:54-79`), 24 `.component(preset.component())` calls, with the Javadoc at `:47`
  stating *"leaves the other twenty-three"*.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: pom.xml carries no version.cui.http property; cui-java-bom-1.7.5.pom manages cui-http at 3.1, not 2.2
- OBSERVED: this repository resolves cui-http **2.2** today, so the tripwire passes — read from
  `dependency:tree -pl api-sheriff`: `de.cuioss:cui-http:jar:2.2:compile` and `:generators:2.2:test`.
  The parent is `cui-java-parent` 1.6.2 at `pom.xml:7`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: cuioss-parent-pom is now at 1.7-SNAPSHOT and 1.7.5 is released and resolved locally
- OBSERVED: the cui-http 3.0 change is real but **UNRELEASED** — read at
  `/Users/oliver/git/cuioss-parent-pom`, commit `6907211` *"chore: update cui-http from 2.2 to 3.0
  (#1433)"* on `main`, while that project's own `pom.xml` is `1.6-SNAPSHOT` and this machine's
  `~/.m2/repository/de/cuioss/cui-java-parent/` tops out at `1.6.2`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: cui-http 3.0 still present at local m2 repo (narrow literal fact, unaffected by 3.1 now being used)
- OBSERVED: `cui-http` **3.0 resolves today** — present in this machine's
  `~/.m2/repository/de/cuioss/cui-http/`. ⛔ This is what removed deliverable 6's availability gate:
  the artifact is published, so the deliverable does **not** wait on `cui-java-parent` 1.6.3, which
  remains unreleased (`/Users/oliver/git/cuioss-parent-pom` is `1.6-SNAPSHOT`, `6907211` on main).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: overridable-property mechanism generalizes: cui-java-bom-1.7.5.pom still manages cui-http via a version property
- OBSERVED: the version is BOM-managed through an overridable property — read at
  `cui-java-bom-1.6.2.pom:24` (`<version.cui.http>2.2</version.cui.http>`) and `:73-74`
  (`cui-http` managed at `${version.cui.http}`). This is the seam the override uses.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: pom.xml properties block declares only version.token-sheriff and version.json-schema-validator; version.quarkus explicitly NOT declared
- OBSERVED: this project already carries three project-owned version properties — read at `pom.xml`
  § `<properties>`: `version.quarkus`, `version.token-sheriff`, `version.json-schema-validator`, with
  a comment distinguishing a *pin* (parent declares nothing) from an *override* (parent declares a
  value). `version.cui.http` is an **override** by that definition.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: actual growth via 3.1 is 24->27 (+3), not the hypothesized 26
- HYPOTHESIS: cui-http 3.0 grows `SecurityConfiguration` from 24 record components to **26** —
  confirm/refute by reading `SecurityConfiguration.class.getRecordComponents()` at the resolved 3.0
  artifact (verify-at-outline). ⚠ **The count 26 is second-hand**, relayed from another repository's
  build failure and NOT read here against 3.0; it is the number to verify, never the number to code
  against. If the growth is not two components, deliverable 6 changes size and the split guard applies.
- Verify-first clause for deliverable 6: re-read the tripwire, the copy, and the resolved cui-http
  version at HEAD before scoping. All three line citations above are `3fc4c83`-relative.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java` — upstream request options
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStage.java` — relay options
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — HTTP/2 client options
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/TlsConfig.java` — policy record
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/UpstreamConfig.java` — only if the knob is per-upstream (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/` loader/validator — schema + boot validation for the new key (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — the control pair (verify-at-outline: exact class TBD)
- OBSERVED: `doc/configuration.adoc` — the knob's documentation
- HYPOTHESIS: `doc/security-threat-model.adoc` — residual-risk entry for the relaxation (verify-at-outline)
- OBSERVED: `pom.xml` — a `<version.cui.http>3.0</version.cui.http>` override in `<properties>` (deliverable-6 fold, 2026-09-06)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityConfigurations.java` — `builderSeededFrom` gains two `.component(preset.component())` calls, and its Javadoc's "other twenty-three" becomes twenty-five (deliverable-6 fold)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` — the drift tripwire's `copiedByBuilderSeededFrom` constant at `:521` (deliverable-6 fold)

### Notes on the deliverable-6 fold entries (2026-09-06)

⛔ **The three entries above are IN the main list on purpose.** They were first authored below this
heading and `corpus surfaces` reported PLAN-03 unchanged at 9 resolved entries — the reader stops at
the first `###`, so a surface declared under a subheading is **invisible to the gate**. That is the
same-act obligation failing silently, which is the exact defect class this epic has measured four
times. Verified after the move: 12 resolved entries.

⛔ **Declared in the SAME act as the fold, per the same-act obligation.** A fold that adds file
surface and leaves the declaration untouched is how this epic's disjointness gate accumulated error;
these three entries are the fold's whole realized footprint.

- OBSERVED: `pom.xml` — a `<version.cui.http>3.0</version.cui.http>` override in `<properties>`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityConfigurations.java` — `builderSeededFrom` gains two `.component(preset.component())` calls, and its Javadoc's "other twenty-three" becomes twenty-five
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` — the drift tripwire's `copiedByBuilderSeededFrom` constant at `:521`

⚠ **`SecurityConfigurations.java` was already inside the declared `config/` directory entry above**,
so the matcher already saw it. It is named explicitly anyway: a directory entry is what made PLAN-06
gate-blind, and a known target should never rely on a directory to be visible.

✅ **No live collision from these three.** `pom.xml` is declared only by PLAN-02 and
`GatewayEdgeRouteTest.java` only by PLAN-06 and PLAN-13 — all three shipped and terminal.

## Dependencies and Sequencing

- Depends on: PLAN-02 for sequencing only — both touch `application.properties` / the config model.
  No logical dependency.
- Overlaps with: PLAN-02 on `application.properties`; PLAN-04 on the gateway.yaml config model and
  the shared documentation. ⚠ PLAN-04 is **blocked on this plan's deliverable 2**, which is the sole
  naming authority for both surfaces — see the epic decision of 2026-08-27 on keeping the pair split
  with a single vocabulary.
- Adjacent to: `MtlsServerCustomizer` and the `client_ca` trust anchor — the INBOUND client-cert
  surface. Untouched: that is the gateway verifying a client, not a host name.
- Adjacent to: `PassthroughRelay` / `SniFrontListener`. Untouched by construction — a passthrough
  connection is relayed at L4 and its certificate is never inspected.

### Deliverable 6 — the split-guard rationale for proceeding at SIX deliverables

⚠ **This spec now carries six deliverables, which trips the scope-bloat split guard.** Proceeding
unsplit is deliberate and the reasoning is recorded here rather than left implicit:

1. **The fold is an operator decision** — *"API updates are part of it"* — and a dependency bump that
   changes an outbound-TLS-adjacent API sits naturally with the plan that owns outbound TLS.
2. **It cannot be split out into its own plan.** The three edits must be atomic with the version
   bump (the tripwire asserts equality, so no ordering is green in between), and a standalone plan
   staged to "fix it ahead" would break `main` on its own merge — there is no green intermediate
   state to hand off between two plans.
3. **It adds one deliverable, not a workstream.** Deliverables 1-5 are one coherent change to
   outbound hostname verification; deliverable 6 is bounded, mechanical, and fully specified — its
   scope cannot grow during execution the way a design-bearing deliverable can.

⛔ **If deliverable 6 turns out to be larger than specified at outline** — the record grew by more
than two components, or 3.0 moved something else this repo consumes — **split it out rather than
absorbing the growth.** The guard exists for exactly that, and a six-deliverable plan has no room.

### Consequences of the fold for the rest of the queue

- ✅ **This plan now SETTLES the version question PLAN-04 is pinned to.** PLAN-04's claim 11 (*"the
  resolved `cui-http` `HttpHandlerBuilder` exposes no hostname-verification method"*) is corroborated
  against the resolved **2.1.0** artifact and is what selects its hand-rolled
  `X509ExtendedTrustManager`. Once this plan lands 3.0, that claim is re-readable against the
  version PLAN-04 will actually compile against. ⚠ **Re-ground it before PLAN-04 is emitted** — if
  3.0 carries `cuioss/cui-http#165`'s knob, PLAN-04 migrates to it, deletes the local trust manager,
  and the TokenSheriff coordination dissolves.
- ⚠ **The epic's pre-diagnosed cui-http 3.0 break is now OWNED rather than unowned.** Its Open Defect entry
  stands as the diagnosis; this deliverable is the remedy.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-03-upstream-hostname-verification.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
