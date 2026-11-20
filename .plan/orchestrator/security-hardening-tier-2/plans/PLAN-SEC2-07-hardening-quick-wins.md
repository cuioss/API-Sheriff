# PLAN-SEC2-07: Hardening quick wins and wording corrections

epic: security-hardening-tier-2
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC2-07-hardening-quick-wins.md` and is queued as one row file,
> `queue/PLAN-SEC2-07.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the detail it deliberately keeps out of tracked
> text (see Security Findings).
> See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Ship the small, low-risk fixes that a re-review of the open observations of this epic found on `main` at `00ebea64`: one
audit-log value, one management-listener bound, one boot warning, one per-source bound on the SNI front, and a single sweep over
documents and comments whose statements no longer match the code. No behaviour changes beyond the four named below. One plan,
one PR. Runs after PLAN-SEC2-06 has merged, because both edit the same documents and `EdgeRules.java`.

Disclosure is mechanism-level. Background: the git-ignored `.plan/local/security_notes.adoc` entries SN-03, SN-04, SN-08 and SN-10 and
the documentation-fault list in `.plan/local/security_notes_verified.adoc` (local only; never quote them into tracked text).

## Deliverables

1. **The matched SNI value in the passthrough audit log.** The passthrough route matches on the normalized SNI key but hands the raw
   value on, and the relay's INFO record logs the raw value. Pass the normalized key (or render the value through the project's log
   escaping) so the record carries the matched key only.
   - Done when: a ClientHello whose server name is a configured passthrough host surrounded by whitespace including CR and LF is
     still routed, and the record carries the configured host with no whitespace, failing at `00ebea64`; an exact host is logged
     unchanged (matched control) (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: security-expert
2. **A tighter body bound on the management listener.** Every management route buffers a request body up to the 10 MiB default of
   `quarkus.management.limits.max-body-size`, and no management route needs a body. Set a small bound that the health, readiness,
   liveness and metrics routes do not hit, state why that value, and turn the existing test that pins the 10 MiB control into the
   test of the new bound.
   - Verify first: which body size the shipped management routes accept at the new value on a live listener, and that the form
     limits already applied are unaffected.
   - Done when: a body above the new bound is refused on a live management listener, failing at `00ebea64`; a body below it and every
     probe route still answer (matched controls); the shipped-properties test pins the key (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core
   - Persona: security-expert
3. **A boot warning for two configuration combinations behind the SNI front.** With `tls.passthrough_sni` set, every terminated
   connection reaches the edge from the loopback relay leg, so source detection and the `forwarded.trusted_proxies` judgement see
   that address. Warn at boot, with a new `LogRecord`, when `passthrough_sni` is combined with an `enumeration_detection.action` of
   `block` or `delay`, or with a loopback address in `forwarded.trusted_proxies`; stay silent otherwise. No new configuration key.
   - Verify first: that the rule can read all three settings at validation time, and the next free log identifier.
   - Done when: each combination sees the warning and neither alone does, failing at `00ebea64`; `observe` with passthrough and a
     non-loopback proxy list see none (matched controls); the record is in `doc/LogMessages.adoc` and the consequence in
     `doc/configuration/security-and-edge-hardening.adoc` (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: security-expert
4. **A per-source bound on a passthrough target's dial budget (optional; drop at outline if it needs more than a constant and a map).**
   A source's share of undecided connections (a quarter of the front's cap) exceeds a passthrough target's dial budget (an eighth), so one
   source can hold a target's whole budget. Bound the dials one source may hold per target below the target's budget, state why that
   value, and keep the front cap and the existing shares as they are.
   - Verify first: HYPOTHESIS in Claim Labels; a refutation is recorded, not fixed.
   - Done when: one source cannot hold more than its bound of a target's dials while another source's dial to the same target is
     still admitted, failing at `00ebea64`; a single source below the bound is unaffected (matched control); the GW-07 passage
     of the threat model states the bound (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: security-expert
5. **One sweep over every restated statement.** Each site below states something that the code or a neighbouring document
   contradicts or overstates. First list every site (read each against the code), write each corrected sentence once, apply them all
   in ONE commit, then re-read each restatement against the code it describes:
   - the connection-cap sentences ("bounds open connections whatever they carry"): `application.properties` (cap comment),
     `doc/user/edge-security.adoc`, `doc/security-threat-model.adoc` (the transport-bounds passage): the cap counts a connection only
     after its TLS handshake, and is one counter shared by the redirect and HTTPS listeners; add that one source can hold the whole
     counter and that routing through the SNI front does not bound it, next to the request-head requirement already stated there;
   - `doc/development/tls-edge.adoc` (internal HTTPS port "bound to `localhost` only"): the image starts the application with
     `-Dquarkus.http.host=0.0.0.0` (`Dockerfile.native`), so the internal listener is bound on all interfaces and is reachable
     wherever its port is published; state the container-network consequence;
   - ADR-0003 and ADR-0076: state that a trusted proxy's unresolvable chain is keyed on one source per proxy, that this source is acted
     on, and what that means for the clients behind that proxy;
   - `doc/security-threat-model.adoc` (BFF-02, cookie mode "there is no server store to mark it consumed"): re-read against
     `BffRuntimeProducer.java` and `CallbackEndpoint.java`, which build and use one pending-authorization store for both modes, and
     state exactly what is single-use in each mode (HYPOTHESIS in Claim Labels);
   - `ApiSheriff-146` template and its `doc/LogMessages.adoc` row: the `dial_budget` refusal does not happen "at accept";
   - ADR-0043 and the `SealedSessionCookieCodec` Javadoc: "no two seals compress the same token" and "monotone scope growth" against
     `TokenRefreshCoordinator.java` (a response without an ID token keeps the previous one; the active scope set is replaced by the
     response scope), and "operator-declared scope names" against the names the identity provider returns;
   - `doc/user/tls-edge.adoc` (revocation by "removing every root a certificate chains to"): a trust anchor left in the bundle that is an
     intermediate stays an anchor; say "every certificate of the chain";
   - the header comment of the `/auth` sample `endpoints/idp.yaml` ("a path nobody listed has no way in"): the routes are segment-prefix
     routes, so sub-paths of a listed prefix pass;
   - four sites PLAN-SEC2-06's own sweep left (its landing, L2-1 to L2-3), each read against `main` after that plan: the BFF-09 row of the
     coverage table in `doc/security-threat-model.adoc` gives "no `Sec-Purpose`" as the whole marker rule while the logout leg also refuses
     the legacy `Purpose` and `X-Moz` markers (`ReservedPathHandler.speculativeMarker`); the `CsrfDefence.provesTrustedDocumentNavigation`
     Javadoc says "`Sec-Fetch-Site: none` is not a proof" without the "no `Origin`" qualifier the code applies (a trusted `Origin` is admitted
     whatever the site says); and the placeholder form `unresolved-via-<proxy address>` in the threat model's source-key passage and in
     ADR-0078, where every other occurrence now reads "`unresolved-via-` followed by the proxy's canonical address".
   - Done when: each site is corrected where it is stated and a final read of each restatement against its code finds no
     disagreement (`order: none`); the sweep is one commit.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-dev-java:javadoc
   - Persona: documenter

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; the new record is a `LogRecord` in `doc/LogMessages.adoc`
  with the next free identifier at the time of editing (re-read it).
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. A behaviour change starts with a test that FAILS against the unfixed code at the sha
  its Done-when names; a test that cannot be made to fail refutes the premise: record and drop that part.
- Fixed constants, no new `gateway.yaml` key unless unavoidable (then a test that goes red when the key is deleted).
- **One sweep, one commit.** A change to a statement several documents restate is applied to every restatement at once, and each
  restatement is checked against the code it describes.
- Documentation states the mechanism only. ADR body ceiling is 500 lines: ADR-0003 (170), ADR-0043, ADR-0076 (423) are amended only
  where a statement is corrected; `doc/adr/0060-…` (517) is not edited here.
- Re-read `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `application.properties` and `EdgeRules.java` at the `main` the plan
  starts from (PLAN-SEC2-06 edits the first two and the last).
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry appended to the git-ignored
`.plan/local/security_notes.adoc` (plan id, location, what, why not fixed) — never into the PR, commits, docs or the inbox
message. A verified result of this plan's own deliverables is reported the same way, with the test that shows it.

## Claim Labels

- OBSERVED: the passthrough route matches on `normalizeSni(serverName)` and passes the raw `serverName` to `relay.relay(...)`, and the relay logs it as the SNI label — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java:644-647,715-719` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java:140-150,219` (main `00ebea64`)
- OBSERVED: the ClientHello parser reads the server name as US-ASCII with no character validation beyond length — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ClientHelloSniParser.java:484` (main `00ebea64`)
- OBSERVED: the management body bound is the 10 MiB default, stated in a comment — read at `api-sheriff/src/main/resources/application.properties:229-232` (main `00ebea64`)
- OBSERVED: a source's undecided share is 256 and a passthrough target's dial budget is 128 — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java:208-217` (main `00ebea64`)
- OBSERVED: source detection and the trusted-proxy judgement read the TCP peer, which behind the SNI front is the loopback relay leg — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeys.java:138-150` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java:694` (main `00ebea64`)
- OBSERVED: the image starts the application with `-Dquarkus.http.host=0.0.0.0` and the document says the internal listener is bound to localhost only — read at `api-sheriff/src/main/docker/Dockerfile.native:72` and `doc/development/tls-edge.adoc:362-366` (main `00ebea64`)
- OBSERVED: the cap sentences say the cap bounds connections "whatever they carry" — read at `api-sheriff/src/main/resources/application.properties:142-146` and `doc/user/edge-security.adoc:591-597` (main `00ebea64`)
- OBSERVED: the `ApiSheriff-146` template says "refused at accept" — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java:357-359` (main `00ebea64`)
- OBSERVED: the ADR and the codec Javadoc state "no two seals compress the same" token, "monotone scope growth" and "operator-declared" scope names — read at `doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc:75,79,150` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java:61-67` (main `00ebea64`)
- OBSERVED: the revocation sentence and the sample comment — read at `doc/user/tls-edge.adoc:394-395` and `deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml:23` (main `00ebea64`)
- HYPOTHESIS: the Vert.x handshake-completion order, the connection-cap handler and the management routes accept a body bound as small as the new one without refusing a probe — confirm/refute on a live management listener in `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ManagementListenerTransportTest.java` (verify-at-outline)
- HYPOTHESIS: one source can hold a passthrough target's whole dial budget while its dials stay pending — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `claimDial` by a test with a backend that never answers (verify-at-outline, deliverable 4)
- HYPOTHESIS: `BffRuntimeProducer` builds, and `CallbackEndpoint` consumes single-use, one pending-authorization store in cookie mode as well as in server mode, so the BFF-02 cookie-mode sentence understates the control — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java:597` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java:336` (verify-at-outline)
- HYPOTHESIS: `TokenRefreshCoordinator` keeps the previous ID token when a refresh response omits it and replaces the active scope set with the response scope — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java:823,859-865` (verify-at-outline)
- Verify-first clause: re-ground every `OBSERVED` claim above at the `main` the plan starts from before scoping on it; line numbers move, and PLAN-SEC2-06 edits several of these documents.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRules.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java`
- OBSERVED: `api-sheriff/src/main/resources/application.properties`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRulesTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ManagementListenerTransportTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ShippedApplicationPropertiesTest.java`
- OBSERVED: `doc/adr/0003-forwarded-headers.adoc`
- OBSERVED: `doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc`
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- OBSERVED: `doc/configuration/security-and-edge-hardening.adoc`
- OBSERVED: `doc/development/tls-edge.adoc`
- OBSERVED: `doc/user/tls-edge.adoc`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java` — the `provesTrustedDocumentNavigation` Javadoc only
- OBSERVED: `doc/adr/0078-Per-source_threat_classification_raises_one_ECS_alert_per_episode_from_its_own_ledger.adoc` — the `unresolved-via-` placeholder only
- OBSERVED: `doc/security-threat-model.adoc` — BFF-02, GW-07, the transport-bounds passages, the BFF-09 coverage-table row and the source-key passage naming `unresolved-via-` only
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml`

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-06 (it edits `EdgeRules.java`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `doc/user/edge-security.adoc` and ADR-0076; this plan starts from the `main` that contains it).
- Overlaps with: PLAN-SEC2-06 on those files — so it is sequenced after it, not run beside it.
- Adjacent to: the request-head deadline (a documented front-proxy requirement; an upstream Quarkus request is filed by the operator) stays as decided on 2026-11-17: this plan only adds the per-source consequence to the statement.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-07-hardening-quick-wins.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the git-ignored
`.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file under `.plan/orchestrator/` other than
its own `inbox/{sender}-{seq}` landing message — the orchestrator owns every other ledger write — and reports its outcome through
its PR and that inbox message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
