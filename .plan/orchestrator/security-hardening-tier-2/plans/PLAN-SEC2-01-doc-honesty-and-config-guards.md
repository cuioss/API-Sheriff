# PLAN-SEC2-01: Doc honesty and config guards

epic: security-hardening-tier-2
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. Lives at
> `plans/PLAN-SEC2-01-doc-honesty-and-config-guards.md` and is queued as one row file,
> `queue/PLAN-SEC2-01.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).

## Objective

Make tracked documentation and Javadoc state what the code does, and add the small configuration
guards and header hygiene a 2026-11-14 code reading at `main` `6ee137df` found missing. Every item
is low-risk and test-pinnable; this plan ships before the epic's two code plans.

Disclosure is mechanism-level. The background of each item is in the operator's git-ignored
`.plan/local/security_notes_verified.adoc` (entries named per deliverable below). **Read those
entries before starting.** They are local, never quoted into a commit, PR, doc or inbox message.

## Deliverables

1. **Documentation and Javadoc corrections (docs only).** State the mechanism; no attack narrative.
   - `doc/adr/0039-…adoc` (≈29) and `doc/resources/diagrams/integration-test-topology.svg`: the
     management interface serves HTTPS when key material is supplied and plain HTTP otherwise
     (announced by ApiSheriff-115), not "plain HTTP only through a named opt-out" / "HTTPS only".
   - `doc/user/portal.adoc`: say the route catalog shown by `/portal` is public by design.
   - `RpInitiatedLogout.java` (≈100): the logout-state cookie protects the return leg; reword
     "(CSRF defence for logout)" to say exactly that.
   - Event-loop Javadoc in `TerminalDispatch.java` (≈823 vs ≈836-838, 870-873, 884-886) and
     `DispatchStage.java` (≈2828-2830): in production the late callback runs on the client
     connection's loop (the `@VirtualThreads` executor preserves the context); make the passages
     consistent and correct. Javadoc only — no code change in these two classes.
   - Session cookie rename guidance (`doc/user/bff-session.adoc` or
     `doc/configuration/authentication-and-oidc.adoc`, where `oidc.session.cookie_name` is
     documented): renaming a custom cookie name is done together with a sealing-key rotation or a
     full restart, not as a rolling change.
   - Done when: each listed site reads as the code behaves (`order: none`; list the sites in the PR).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-documents:ref-svg-diagrams, pm-dev-java:javadoc
   - Persona: documenter
2. **HTTP/2 header-list bound: announce what is documented.** Verified-notes entry T9. The
   documented bound is 16 KiB but the announced/effective value is the Vert.x default 8192, because
   `EdgeHardeningOptions.boundHeaderListSize` only lowers it. Set the announced
   `maxHeaderListSize` explicitly to the documented bound (or, if the verify-first below shows a
   reason against it, document 8 KiB everywhere — choose one and state why). Correct the over-bound
   wording everywhere: a decoded list over the bound gets 431 plus a stream reset; only a
   compressed block over 1.25× the bound ends the connection. Replace the confounded 17/32 KiB
   cases in `GatewayEdgeHttp2AbuseTest` (1 KiB CONTINUATIONs trip the small-CONTINUATION limit) with
   a single-HEADERS grey-zone case: a list just under the bound is served, one just over gets 431.
   Doc sites: `EdgeHardeningOptions.java` (≈81-86, 116, 181-185), `doc/user/edge-security.adoc`
   (≈597-601), `doc/architecture/tls-and-inbound-security.adoc` (≈161-162),
   `doc/security-threat-model.adoc` (≈2068-2070, 2122-2126, 4727), ADR-0076 (≈189).
   - Done when: the grey-zone test fails at `6ee137df` (a list between 8 and 16 KiB is refused) and
     passes after (`order: test-first`); an `EdgeHardeningOptionsTest` assertion pins the announced
     value.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: implementer
3. **Refuse a boot that configures the token-validation extension's own issuers.** Verified-notes
   entry B7. A `sheriff.token.issuers.*` key supplied through the environment or properties makes the
   token-validation extension build its own validator (eagerly, at startup and from its metrics
   collector), which fetches JWKS outside the gateway's JWKS loader, TLS profile and egress
   allowance. The gateway configures issuers only through `gateway.yaml`. Refuse the boot when any
   `sheriff.token.issuers.` key is in effect, with a new ERROR `LogRecord` (next free identifier,
   derived at write time, documented in `doc/LogMessages.adoc`). Extend ADR-0074's Risks with the
   outbound-fetch consequence.
   - Verify first: confirm in token-sheriff 0.9.9 which config prefix enables the extension's
     producer (`TokenValidatorStartupInitializer`, `ObservedValidatorResolver`) and that the refusal
     can run before the extension's `StartupEvent` observer; if it cannot, refuse in a config
     validation stage that runs earlier and say so.
   - Done when: a `@QuarkusTest` profile setting one `sheriff.token.issuers.` key fails to start with
     the new record, and the default profile starts (matched control) — `order: test-first`.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: security-expert
4. **Configuration echo hygiene.** Verified-notes entry F3. Add a schema `pattern` for a route `id`
   (`^[a-z][a-z0-9-]*$`, the pattern the endpoint id already uses at `endpoint.schema.json` ≈114;
   route id ≈193), then pass the remaining free-form configuration scalars that reach a refusal or a
   log record through `RuleSupport.renderForMessage` (or the equivalent log-side renderer): rule
   classes in `config/validation/rule/` (AnchorRules, AuthRules, ProtocolRules, TerminalActionRules,
   LimitRules, EdgeRules, TopologyRules, CookieRules) and the log echoes in
   `RouteTableBuilder` (≈348, 547) and `WebSocketRelayStage` (≈343, 597). Make the `RuleSupport`
   (≈44-47), `CookieRules` (≈66-67) and `TerminalActionRules` (≈237-238) "every refusal" Javadoc true.
   - Done when: a schema test refuses a route id outside the pattern and accepts the shipped ids
     (matched control); a rule test with a control character in a free-form value sees it rendered
     escaped in the refusal message (`order: test-first`, failing at `6ee137df`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
5. **Management plain-HTTP audit predicate.** Local note E6 (2026-11-14). Verify whether Quarkus
   3.40.1's management interface enables TLS from
   `quarkus.management.ssl.certificate.credentials-provider` alone. If it does not,
   `ManagementPlainHttpAudit` must not treat that key as key material: narrow the predicate to
   key-bearing settings so ApiSheriff-115 fires. If it does, document that in the management TLS docs
   and keep the predicate.
   - Done when: either a test with only the credentials-provider key sees ApiSheriff-115 (failing at
     `6ee137df`) with the key-material case silent as control, or the verification is recorded in the
     PR and the doc states the supported settings (`order: test-first` for the code branch).
   - Skills: pm-dev-java:java-quarkus, pm-dev-java:junit-core
   - Persona: implementer
6. **Failed-relay head: drop CDN-targeted cache fields.** Verified-notes Info "R3 residual".
   `TerminalDispatch.prepareFailedRelayHead` already forces `Cache-Control: no-store` and drops
   `Expires`, `Age`, `ETag`, `Last-Modified`; also drop `CDN-Cache-Control` and `Surrogate-Control`
   (RFC 9213 targeted fields take precedence over `Cache-Control` at a CDN).
   - Done when: `TerminalDispatchLateBodyTimeoutTest` (or a sibling) with an upstream head carrying
     both fields sees neither on the failed relay, failing at `6ee137df` (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims; a breaking schema tightening (D4) is allowed.
- **No new dependency.** Logging through `CuiLogger` with `%s`, exception first; every new
  INFO/WARN/ERROR record is a `LogRecord` documented in `doc/LogMessages.adoc` with the next free
  identifier, derived at write time.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every code fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise — record it
  in the landing message and drop that part.
- Do not change code in `edge/upstream/**` (Javadoc in `DispatchStage` excepted), `bff/**` other than
  the `RpInitiatedLogout` comment, `edge/admission/Source*`, `detection/**`,
  `auth/RetryingJwksLoader*` or ADR-0063.
- Documentation states the mechanism only — no attack recipe, no rates, nothing about weaknesses
  that remain open.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated pre-commit formatter churn.
- Sonar: an open finding is fixed in a pushed commit, or dismissed only on the operator's selection;
  an `undecidable` read blocks the merge.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch are NOT written into any tracked
text (PR body, commit message, documentation, inbox message). Append a dated entry to the git-ignored
`.plan/local/security_notes.adoc` instead — plan id, location, what was observed, why it was not
fixed — and say only that a local note was recorded.

## Claim Labels

- OBSERVED: ADR-0039 says the management interface is plain HTTP only through a named opt-out — read at `doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc:29` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: ADR-0039 line 29 at 6ee137df reads plain HTTP only through a named opt-out; re-read at base and at the merged tree
- OBSERVED: the announced HTTP/2 header-list size is the Vert.x default 8192 and the gateway only lowers it — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java:300-304` § `boundHeaderListSize` and quarkus-vertx-http 3.40.1 `HttpServerOptionsUtils:349,363-364` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java boundHeaderListSize at 6ee137df only lowered a value above 16 KiB; merged apply() sets it unconditionally
- OBSERVED: the gateway has no reference to `sheriff.token.issuers` and no boot refusal for it; only the extension's health package is excluded — read at `api-sheriff/src/main/resources/application.properties:383` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: application.properties at 6ee137df carries only the health-package exclusion at 383, no active sheriff.token.issuers key and no refusal (comments mention the key, so no reference is imprecise)
- HYPOTHESIS: a gateway refusal can run before token-sheriff 0.9.9's `TokenValidatorStartupInitializer.onStartup` builds the extension's validator — confirm at token-sheriff-validation-quarkus 0.9.9 `TokenValidatorStartupInitializer` and the gateway's startup ordering (verify-at-outline, D3)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: ExtensionIssuerNamespaceGate observes StartupEvent at PLATFORM_BEFORE-1, ahead of default-priority observers; read at ExtensionIssuerNamespaceGate.java:86 and :112, ordering pinned by ExtensionIssuerNamespaceGateTest
- OBSERVED: the route `id` in the endpoint schema has no pattern while the endpoint `id` has `^[a-z][a-z0-9-]*$` — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json:114,193` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: endpoint.schema.json route id had no pattern at 6ee137df; merged tree carries ^[a-z][a-z0-9-]*$ at :195
- HYPOTHESIS: Quarkus 3.40.1's management interface does not enable TLS from a credentials provider alone — confirm at quarkus-vertx-http 3.40.1 management TLS wiring vs `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java` (verify-at-outline, D5)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: ManagementPlainHttpAuditTest:168-184 boots with the credentials provider alone and the management port answers plain HTTP with the audit warning present
- OBSERVED: `prepareFailedRelayHead` does not strip `CDN-Cache-Control` / `Surrogate-Control` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java:1031-1040` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: TerminalDispatch.prepareFailedRelayHead at 6ee137df removed only Expires/Age/ETag/Last-Modified; merged UNSTORABLE_ANSWER_REMOVES adds CDN-Cache-Control and Surrogate-Control (:118)
- Verify-first clause: D3 and D5 settle their HYPOTHESIS before code; a refuted D5 premise becomes the documentation branch, a refuted D3 ordering moves the refusal earlier.

## Expected Surface

- OBSERVED: `doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc`
- OBSERVED: `doc/resources/diagrams/integration-test-topology.svg`
- OBSERVED: `doc/user/portal.adoc`
- OBSERVED: `doc/user/bff-session.adoc`
- OBSERVED: `doc/configuration/authentication-and-oidc.adoc`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogout.java` — comment only
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — Javadoc and `prepareFailedRelayHead`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` — Javadoc only
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptionsTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeHttp2AbuseTest.java`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — edge passages only, not `gw-07-jwks-refresh`
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/` — the issuer-key boot refusal (new class) and its `LogRecord` in `ConfigLogMessages.java` (verify-at-outline)
- OBSERVED: `doc/adr/0074-The_token-validation_extensions_metrics_collector_observes_the_gateway_validator_and_its_health_probes_stay_excluded.adoc`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — rule classes and `RuleSupport.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAuditTest.java`
- OBSERVED: `doc/configuration/tls-and-egress.adoc`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatchLateBodyTimeoutTest.java`

## Dependencies and Sequencing

- Depends on: none (tier-1 epic closed; follow-up PR #620 merged as `a6c36b37`).
- Overlaps with: PLAN-SEC2-02 on `DispatchStage.java` / `TerminalDispatch.java` (Javadoc here, code there) and the two shared doc files — PLAN-SEC2-02 runs after this plan.
- Adjacent to: `auth/RetryingJwksLoader*` and ADR-0063 (kidicap epic surface) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-01-doc-honesty-and-config-guards.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the
git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file
under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. The inbox exception's qualifiers and the sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
