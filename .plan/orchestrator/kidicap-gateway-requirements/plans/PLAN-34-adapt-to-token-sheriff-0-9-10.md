# PLAN-34: Adapt to token-sheriff 0.9.10 — Delete the Gateway Copies of What TokenSheriff Now Publishes

epic: kidicap-gateway-requirements
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-34-adapt-to-token-sheriff-0-9-10.md` and is queued as one row file,
> `queue/PLAN-34.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief, so every per-plan carry is authored here and nowhere else.
> Staged 2026-11-12 from the TokenSheriff separation audit (epic.md Watch "TokenSheriff separation
> audit"). **BLOCKED: this plan starts only once `main` pins a released token-sheriff 0.9.10.** The
> version bump is NOT part of this plan: the TokenSheriff release workflow opens the automated bump PR
> (`version.token-sheriff`, `pom.xml` :63) and that PR merges first. Until then this spec is a statement of
> intent against an API that does not exist: every TokenSheriff name below is the audit's PROPOSAL.

## Objective

The 2026-11-12 audit found that most of the remaining gateway code that duplicates token-sheriff exists
because a token-sheriff seam is private, internal or missing. token-sheriff's epic `api-sheriff-seams`
adds those seams in 0.9.10 (its PLAN-01 to PLAN-07). Once 0.9.10 is released and pinned, this plan deletes
the gateway's copy for every seam that actually shipped, keeps the gateway-specific policy around it, and
keeps the behaviour the gateway's tests pin. A deliverable whose TokenSheriff plan did not ship in the
pinned 0.9.10 is removed at outline, never waited on.

## Deliverables

Split-or-proceed: seven deliverables, under the 12-deliverable cap but above the ~6 presumptive-split
guard. Proceed as ONE plan: each deliverable maps to exactly one TokenSheriff plan, is independently
droppable, deletes code and adds none of its own, and shares the same verify-first gate. The alternative
cut, if the outline finds the released 0.9.10 covers only part of the set, is by TokenSheriff module:
D1 to D4 (the `token-sheriff-client` side) and D5 to D7 (the validation side); take it only if the
shipped subset makes one plan exceed the operator's review budget, and record it as an epic decision.
Record this rationale there.

**Verify-first gate that binds every deliverable.** Before a deliverable is scoped: (a) read the RESOLVED
0.9.10 jar (`unzip -l`, `javap -p`) and its release notes, not TokenSheriff's `main`; (b) confirm the seam
shipped and read its real name, signature, exceptions and default behaviour, because the names in the
audit may not be the ones TokenSheriff shipped; (c) enumerate the gateway-visible behaviour of the code to
be deleted and map each element to the shipped seam; an element the seam does not reproduce is either kept
in the gateway or the deliverable is dropped, never loosened. If (a) fails, REMOVE the deliverable.

1. **D1 — native-image registration (TokenSheriff PLAN-01).** Delete `TokenClientDslJsonReflection`
   (109 lines), `TokenClientDslJsonReflectionTest`, and the build argument
   `--initialize-at-run-time=de.cuioss.sheriff.token.client.flow` (with its comment :233-243) in
   `application.properties`. Today the class registers eight generated `_*_DslJsonConverter` classes and the
   response DTOs for reflection; a missing entry fails open in native (the `cnf.jkt` check skipped, an
   `invalid_grant` misread). Prerequisites: the registration lives in the released `token-sheriff-client-quarkus`
   deployment module, so that artifact must be on the classpath — PLAN-33 D4's dependency decision decides
   whether it still is; if it was dropped there, re-adding it here needs a fresh explicit operator approval.
   **The proof is the native integration suite, not a unit test**: run `-Pintegration-tests` (native image)
   and show the BFF flows that parse each response type (login, refresh, userinfo, PAR, DPoP-bound token)
   still pass, including a leg that would fail open if a converter were unregistered. Update ADR-0082
   (it names the class) and the DSL-JSON passage of `doc/technical_aspects.adoc`.
2. **D2 — authorization-request seam (TokenSheriff PLAN-02).** Delete
   `QueryResponseModeAuthorizationRequestBuilder` (178 lines), `PushedAuthorizationRequests` (231) and the
   config-factory and flow cache of `ScopedEngineFlows` (295), replacing them with the released
   response-mode, prompt, PAR-aware authorize and per-call scope seams. Keep, as a test that survives the
   deletion, the proof that the authorization request is sent with `response_mode=query` and that the Lax
   binding cookie is sent on the resulting top-level GET redirect: the gateway's cookie design depends on
   it, and the library's default stays `form_post`. This deliverable serves existing consumers unchanged
   (`LoginFlow`, `LoginInitiationEndpoint`, `CallbackEndpoint`, `SessionWidening`, `StepUpHandler`
   re-drive, the refresh-for-scopes leg); enumerate each consumer-visible element and keep one criterion
   per element: the browser location carries `client_id` and `request_uri` only when PAR is on (and no
   authorization parameters otherwise), `response_mode=query`, `prompt=none` for the silent widening,
   `scope` per canonical scope set, no `dpop_jkt`, a refused push surfaces the same refusal the callers
   handle today, an unparsable or duplicated parameter is refused, `code_challenge`/`state`/`nonce`
   present exactly once; edges: empty scope set, duplicate scopes (canonicalisation), PAR endpoint
   absent, PAR refusal, the cached per-scope-set flow reused across calls and not shared across
   canonical sets. A criterion the shipped API cannot meet drops that part of the deliverable.
3. **D3 — key material and config validators (TokenSheriff PLAN-03).** Slim `ClientSigningKey` (605
   lines) to what is gateway policy — `Purpose`, the two-key separation rule (FAPI 6.8), the EdDSA
   refusal, the `ClientJwksEndpoint` — and use the released PEM key-pair loader and public-key-to-JWK
   utility for the PEM parse, the RSA / EC P-256 checks, key generation, the key-pair halves probe and
   the public JWK members; keep `KeyFiles` unless the released loader subsumes its bounded read. Replace
   `OidcClientRules.backChannelOriginShapeRefusal` (:491) and `RuleSupport.egressHostRefusal` with the
   released static validators, keeping the gateway's remediation wording and its own refusals the library
   does not make; `RuleSupport` is shared with `TokenValidationRules` (:109), so both callers are in scope.
   Replace the endpoint-presence check in `BffRuntimeProducer` (:1295-1300) with the released
   revoke-if-supported overload only if it keeps today's behaviour: no `revocation_endpoint` sends nothing
   and logs only at DEBUG. Behaviour that must not move: every existing refusal message the configuration
   tests pin; key fingerprints and `kid` values (RFC 7638) are bit-identical for a given PEM; no key
   material in any log. A change in how a blank or `host:port` egress entry is refused is a security
   behaviour change and is stated as such.
4. **D4 — refresh verification and discovery cache (TokenSheriff PLAN-04). SECURITY-SENSITIVE.**
   `TokenRefreshCoordinator.rotate` (:815-835) stores the refreshed ID token without OIDC Core 12.2
   issuer/subject checks and keeps the previous `sub`; the ID token is validated only lazily, when
   `UserInfoEndpoint` projects it (`BffRuntimeProducer` :727). Call the released refresh-result verifier
   from the refresh path before the rotated record is persisted, and treat a verification failure as
   end-of-session, through the existing `endSession` path with its refresh-token revocation, with a new
   reason constant and a bounded, non-disclosing log record. This is the one deliverable that ADDS
   behaviour: it needs a test that is RED on the pinned gateway before the verifier is called (a refresh
   response carrying an ID token with a different `sub`, and one with a different `iss`) and green after,
   plus the matched control (a conforming rotation persists) and the edge set: no ID token in the response
   (the previous one is kept, as today), blank ID token, `sub` unchanged, a verifier that throws. Also
   replace `DiscoveryStatus` (156 lines) and the memoised metadata suppliers (`BffRuntimeProducer` :540,
   :775) with the released caching, refreshing resolver, KEEPING the readiness datum
   `oidc_discovery` exactly as ADR-0079 defines it (`not-attempted`, `resolved`, `failed`; never changes the
   verdict; discloses no issuer, URL or cause). The gateway today caches resolved metadata for the process
   lifetime, so a changed provider endpoint needs a restart; state the new behaviour and its TTL.
5. **D5 — validation public seams (TokenSheriff PLAN-05).**
   - `SignatureOnlyTokenVerifier` (200 lines, which re-assembles pipeline classes the library documents as
     internal) becomes a call to the released signature-only verification, returning the content and the
     `typ` header; the gateway then also gets the library's issuer health gating, token-string limits and
     metrics that its own copy skips. Behaviour to keep: no claim stage, the unknown-issuer rejection with a
     sanitised message (PLAN-33 D2), the `typ` header available to the back-channel logout path.
   - `IdTokenClaimProjection` (181 lines) becomes the released native-JSON claim accessor.
   - `IssuerKeySetStatus` (131) and `GatewayReadinessCheck` (260) become the library's readiness probe over
     the released issuer-config exposure; delete the exclusion `quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*`
     (`application.properties` :343), and update ADR-0074 and its two pinning tests
     (`DefaultProfileReadinessTest`, `ExtensionUnqualifiedBeanExclusionTest`). The readiness payload is a
     consumer-visible contract: the fixed `jwks` token, the counts `issuers` / `issuers_loaded`, no issuer
     name, URL, host or key id, and the BFF datum `oidc=<mode>` / `oidc_discovery` must keep their text; a
     library probe that discloses more is NOT adopted. Pin each field with a test before the swap.
   - `LogoutTokenValidator` (337) and `LogoutTokenReplayGuard` (122) are touched ONLY if TokenSheriff
     decided to own back-channel logout tokens. Its docs call receivers the BFF's concern (CLIENT-19); the
     decision is TokenSheriff's and is read from the 0.9.10 release notes, not assumed. If TokenSheriff
     declared it out of scope, these two classes stay and say so.
6. **D6 — JWKS loader gateway needs (TokenSheriff PLAN-06). FLOOD BOUND ON AN UNAUTHENTICATED PATH.**
   Reduce `RetryingJwksLoader` (738 lines) to what the library does not cover (the first-load retry for a
   `file` source, or nothing) and replace the bounded refresh on an unknown key id and the fast first-load
   retry with the released opt-in knobs. Drop `unknown_kid_refresh_window_seconds` in favour of the
   library's knob: gateway configuration schema, `TokenValidationRules.JwksRefreshKey`, `IssuerConfig.Jwks`,
   `ConfigModelReflection`, the config tests, `doc/configuration/authentication-and-oidc.adoc`, the GW-07
   `gw-07-jwks-refresh` wording of `doc/security-threat-model.adoc` and ADR-0063. This changes a shipped
   configuration key, which pre-1.0 rules allow without a shim, but the BOUND is the security property and
   must not weaken: at most one refresh per issuer per window whatever the number of distinct key ids, off
   the request thread, the fresh set published only by the rule PLAN-31 ships, a failed or discarded
   refresh neither displaces the loaded set nor starts a retry, the retired set kept for the grace period.
   Port `RetryingJwksLoaderTest`'s flood, kid-spray and concurrent-trigger legs to the new knobs BEFORE
   deleting the old code, and keep `JwksKeyRotationIT` green on the native image. If the released knob
   cannot reproduce every one of these properties, the deliverable is dropped and the gateway keeps its
   wrapper. The wrapper's status handling is PLAN-33's; do not re-open it.
7. **D7 — public helper hygiene (TokenSheriff PLAN-07; low value, drop first).** `AuthenticationStage`
   (:217-247) builds the RFC 6750 `insufficient_scope` challenge by string concatenation and extracts the
   Bearer token with a case-insensitive scheme match; use the released framework-neutral challenge builder
   and Bearer extractor, with the scheme case rule decided explicitly (RFC 9110: the scheme is
   case-insensitive; the library's own producer is case-sensitive today). Behaviour to keep: scheme matched
   case-insensitively, token stripped, empty token is no token, challenge `Bearer error="insufficient_scope",
   scope="<sorted, space-separated>"`. `RuleSupport.renderForMessage` moves to the released escape mode of
   the library's text sanitiser only if it reproduces the gateway's policy (escape every character outside
   printable ASCII as `\uXXXX`, cap 60, then `...`); otherwise it STAYS. `UpstreamAssetSource`'s
   `CappedByteArrayBodySubscriber` (:409) moves to the released bounded body handler only if it keeps the
   truncate-and-flag behaviour; this part is optional.

Done when: for each kept deliverable, the code it names is deleted and the named behaviour tests pass;
`verify -Ppre-commit`, full `verify` and `-Pintegration-tests` (native) pass with zero warnings; the
pull request's open Sonar issue count and the rules left open are read at the head and stated in the PR
(`unknown` for a failed read, never `0`); each dropped deliverable says why and names the TokenSheriff
release that did not carry it.

## Standing Constraints

- **Pre-1.0 rules.** Delete the superseded class, key and test directly; no deprecation, no
  `@Deprecated`, no compatibility shim for a removed gateway key. A gateway configuration key removed in
  D6 needs no migration path.
- **No new dependency without explicit operator approval.** This plan adds none. If D1 needs
  `token-sheriff-client-quarkus` and PLAN-33 D4 dropped it, re-adding it is itself an approval-gated
  dependency change.
- **The version bump is not this plan's.** `version.token-sheriff` is moved only by the release
  workflow's automated PR; the plan starts when `main` already pins 0.9.10. The root `pom.xml` BOM comment
  (:110-133) carries "Re-measure this paragraph on every `${version.token-sheriff}` bump" and its numbers
  (the Quarkus line, the single `io.smallrye.config` version) describe 0.9.9: re-measure it with the
  `dependency:tree` command it names, correct it, and record the result, as the first commit of this plan if
  the bump PR did not.
- **Security-sensitive deletions.** D4 (adds an enforcement), D5 (readiness and signature verification) and
  D6 (a flood bound) must not leave a window in which the gateway is weaker than before: the new test goes in
  BEFORE the old code is deleted, and a deleted control is replaced by a test that goes red if the library's
  replacement stops providing it. Fail-closed postures are never loosened; no issuer, URL, host, key id or
  token content in a log or readiness payload.
- **Build only through the executor**: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run
  --command-args "<goals>"`, never `mvn` or `./mvnw`; 10-minute Bash timeout. Run `verify -Ppre-commit` and
  `verify` before every commit that touches build inputs, then `git status --porcelain` and attribute any
  dirty tree before reverting. A successful build is not evidence that work happened: show the new
  behaviour tests red where they exercise new behaviour, and show every deleted control's replacement test
  go red when the replacement is disabled.
- **Zero warnings, enforced by `-Werror`.** A javac warning fails the build; migrate off the warned
  construct, never add `@SuppressWarnings`. A bump can bring new deprecations; fix them at the call site.
- **Logging.** `de.cuioss.tools.logging.CuiLogger`, `%s` substitution only, exception parameter first,
  `LogRecord` for INFO/WARN/ERROR. Any new record (D4's refresh-verification refusal) takes the NEXT FREE id
  of its range (INFO 001-099, WARN 100-199, ERROR 200-299), read from `doc/LogMessages.adoc` and the
  log-message classes at write time and never assumed, and is documented in `doc/LogMessages.adoc`; a
  record that no longer has a source is deleted from the catalogue.
- **ADRs.** New or amended ADRs take the next free ordinal in `doc/adr` at write time, never an ordinal
  copied from another spec, and follow `doc/development/adr-writing.adoc`. ADR-0063, ADR-0074, ADR-0079 and
  ADR-0082 are amended in place where a deliverable changes their statement.
- **Testing.** JUnit 5, AAA, no Mockito/PowerMock/Hamcrest, CUI test generator for data, at least 80%
  coverage. Every control gets a matched positive and negative control. A configuration key that is
  kept or replaced must go red in a test if deleted entirely
  (`doc/development/declared-limit-assertion-coverage.adoc`).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks. In-code suppression with a rationale
  (`// NOSONAR java:SXXXX <why>`) is the project's first choice; never a silent server-side won't-fix.

## Claim Labels

- OBSERVED: `main` pins `version.token-sheriff` 0.9.9 and the TokenSheriff checkout is `0.9.10-SNAPSHOT` with tags ending at 0.9.9, so no 0.9.10 exists — read at `pom.xml` § `version.token-sheriff` (:63) and `git tag` in /home/oliver/git/TokenSheriff (last tag 0.9.9)
- OBSERVED: TokenSheriff's epic `api-sheriff-seams` has seven planned plans and, in its ledger, no plan spec file yet (`plans/` is empty), so even TokenSheriff's own specs do not exist; every TokenSheriff seam below is a proposal — read at `/home/oliver/git/TokenSheriff/.plan/local/worktrees/_orchestrator/.plan/orchestrator/api-sheriff-seams/epic.md` (Vision, Queue annotations) and its empty `plans/` directory
- HYPOTHESIS: each deliverable's TokenSheriff API names, shapes and defaults (for example `buildParameters`, `verifySignature`, `getRawClaims`, `getIssuerConfigs`, `unknownKidRefreshWindow`, `revokeIfSupported`) are the audit's proposals and TokenSheriff may ship different ones — confirm against the resolved 0.9.10 jar (`javap -p`) and release notes at outline (verify-at-outline)
- HYPOTHESIS: all seven TokenSheriff plans ship in 0.9.10; the TokenSheriff epic itself marks PLAN-07 droppable and PLAN-05's logout-token part as an operator decision, so a subset is likely — confirm per plan at `/home/oliver/git/TokenSheriff` release notes for 0.9.10 and the TokenSheriff epic's landings (verify-at-outline)
- OBSERVED (D1): the gateway registers the generated `_*_DslJsonConverter` classes and DTOs for reflection in `TokenClientDslJsonReflection` (109 lines), guarded by `TokenClientDslJsonReflectionTest`, and carries `--initialize-at-run-time=de.cuioss.sheriff.token.client.flow` in `quarkus.native.additional-build-args` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/TokenClientDslJsonReflection.java` and `api-sheriff/src/main/resources/application.properties` (:233-245)
- OBSERVED (D1): the library keeps `SECURE_RANDOM` as a static field in `PkceChallenge` (:43) and in `FlowContext` (:55), and the 0.9.9 `TokenSheriffClientProcessor` registers no reflection and no runtime-initialised class — read at `token-sheriff-client/src/main/java/de/cuioss/sheriff/token/client/flow/PkceChallenge.java`, `FlowContext.java` and `token-sheriff-quarkus-parent/token-sheriff-client-quarkus-deployment/src/main/java/de/cuioss/sheriff/token/client/quarkus/deployment/TokenSheriffClientProcessor.java`, in /home/oliver/git/TokenSheriff
- HYPOTHESIS (D1): TokenSheriff's PLAN-01 puts the registration into that deployment module, which requires the `token-sheriff-client-quarkus` dependency the gateway declares today (`api-sheriff/pom.xml` :126) and PLAN-33 D4 may remove — confirm in the released deployment module at outline (verify-at-outline)
- OBSERVED (D2): the library's `AuthorizationRequestBuilder` hard-codes `response_mode=form_post` (`RESPONSE_MODE_FORM_POST`, :70), `ParClient.pushAuthorizationRequest` is public (:96), and neither `AuthorizationCodeFlow` nor `ClientConfiguration` mentions `ParClient` or `toBuilder` — read at `token-sheriff-client/src/main/java/de/cuioss/sheriff/token/client/flow/AuthorizationRequestBuilder.java`, `ParClient.java`, `AuthorizationCodeFlow.java` and `config/ClientConfiguration.java`
- OBSERVED (D2): the gateway rewrites the finished URL to `response_mode=query` (`QueryResponseModeAuthorizationRequestBuilder`, 178 lines) because a Lax binding cookie is not sent on a cross-site POST, and `PushedAuthorizationRequests` (231 lines) splits the engine-built URL back into parameters, pushes them and builds the `client_id`+`request_uri` redirect, refusing an unparsable URL or a duplicated parameter — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/QueryResponseModeAuthorizationRequestBuilder.java` (class Javadoc :32-50) and `PushedAuthorizationRequests.java` (class Javadoc :41-73)
- OBSERVED (D3): `ClientSigningKey` is 605 lines, `KeyFiles` 92, `OidcClientRules.backChannelOriginShapeRefusal` is at :491, `RuleSupport.egressHostRefusal` at :163 with `HOST_WITH_PORT` at :65, used by `OidcClientRules` (:477) and `TokenValidationRules` (:109); the library's `ClientConfiguration.validateBackChannelOrigin` (:493) and `validateAllowedEgressHosts` (:428) are `private static` — read at the named files in both repositories
- OBSERVED (D3): the gateway checks `metadata.getRevocationEndpoint()` itself before `revocationClient.revoke(endpoint, ..)` and logs at DEBUG when absent — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § (:1295-1300)
- OBSERVED (D4): `TokenRefreshCoordinator.rotate` takes `rotation.idToken()` unverified (:817), keeps `previous.sub()`, and the ID token is validated only in the `UserInfoEndpoint` lambda through `idBridge.validateRefreshedIdToken` (`BffRuntimeProducer` :727); the library's `TokenLifecycleManager.verifiedRefreshedIdToken` is `private static` (:1272, used :873) — read at the named files in both repositories
- OBSERVED (D4): the library's `DiscoveryResolver.resolve()` (:127) keeps no cache or status; the gateway memoises the metadata supplier and tracks it in `DiscoveryStatus` (156 lines) — read at `token-sheriff-client/src/main/java/de/cuioss/sheriff/token/client/discovery/DiscoveryResolver.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` (:396, :538-540)
- HYPOTHESIS (D4): an unverified refreshed ID token with a foreign `sub` or `iss` is accepted by the gateway today and projected to the user-info endpoint — a behaviour claim from code only, NOT run; confirm or refute with the red test D4 requires, at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java` (verify-at-outline)
- OBSERVED (D5): the library documents its `validation/pipeline` package as "internal to Token-Sheriff and not part of the public API", the core `TokenValidator` exposes no issuer configurations, and the extension's `ObservedValidatorResolver` returns an empty issuer list for an externally built validator — read at `token-sheriff-validation/src/main/java/de/cuioss/sheriff/token/validation/pipeline/package-info.java` (:19), the grep for `getIssuerConfigs` in `TokenValidator.java` (absent), and `token-sheriff-quarkus-parent/token-sheriff-validation-quarkus/src/main/java/de/cuioss/sheriff/token/quarkus/observability/ObservedValidatorResolver.java` (:43-69)
- OBSERVED (D5): the gateway excludes the extension's health probes with `quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*` (the audit cited ~:341; it is at :343) and pins the exclusion in `DefaultProfileReadinessTest` and `ExtensionUnqualifiedBeanExclusionTest`; the files `SignatureOnlyTokenVerifier` (200 lines), `IdTokenClaimProjection` (181), `IssuerKeySetStatus` (131), `GatewayReadinessCheck` (260), `LogoutTokenValidator` (337), `LogoutTokenReplayGuard` (122) exist at those sizes — read at the named files
- OBSERVED (D6): `RetryingJwksLoader` is 738 lines (audit: ~700) and implements the unknown-key-id refresh and the 1 s to 30 s first-load retry; the library's `HttpJwksLoader.getKeyInfo` consults the current and the retired key sets only, with no unknown-key-id trigger (:725-745); its background-refresh entry takes an epoch (`performBackgroundRefresh(long)`, :978, :1021) — the audit's "package-private `performBackgroundRefresh()`" reads differently, which does not change the plan — read at the named files in both repositories
- OBSERVED (D6): the key `unknown_kid_refresh_window_seconds` is read in `TokenValidationRules.JwksRefreshKey` (:214), `IssuerConfig.Jwks` (:98), the schema (:492), `doc/configuration/authentication-and-oidc.adoc` (:170, :264), the GW-07 text and summary row of `doc/security-threat-model.adoc` (:1361, :4478), ADR-0063 (:53) and tests in `ConfigModelContractTest`, `ConfigLoaderTest`, `TokenValidatorProducerTest` and `JwksKeyRotationIT` — found by `grep` over the repository excluding `.plan/` and `target/`
- OBSERVED (D7): `AuthenticationStage` builds the challenge by concatenation (:225-227) and extracts the Bearer token case-insensitively (:232-236); the library's `BearerTokenResponseFactory.buildInsufficientScopeHeader` is `private static` (:213) and `BearerTokenProducer.extractBearerTokenFromHeaderMap` is private and case-sensitive on the `Bearer ` prefix (:298-312); `BoundedBodyHandlers` is package-private (:53); `RuleSupport.renderForMessage` escapes outside printable ASCII and caps at 60 (:127, `ECHOED_VALUE_MAX_LENGTH` :99) — read at the named files in both repositories
- OBSERVED: PLAN-20 (staged, held by the operator until `PLAN-SEC-05` merges) lists `PushedAuthorizationRequests`, `ScopedEngineFlows`, `SessionWidening`, `StepUpCoordinator`, `BffRuntime`, `BffRuntimeProducer`, `CallbackEndpoint`, `TokenRefreshCoordinator`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc` and an ADR on its surface; PLAN-31 (running) and staged `PLAN-SEC-06` claim `RetryingJwksLoader`, `RetryingJwksLoaderTest`, ADR-0063 and the GW-07 `gw-07-jwks-refresh` text — read at the epic ledger `plans/PLAN-20-upstream-signalled-step-up.md` (§ Expected Surface), `plans/PLAN-31-jwks-exact-publish-rule.md` and the `security-hardening-tier-1` ledger `plans/PLAN-SEC-06-tier-2-quick-wins.md`
- OBSERVED (asserted absence): no gateway file references `StepUpResultVerifier`; `BffRuntime.stepUpCoordinator()` has no production caller — `grep` over `api-sheriff/src` and `BffRuntime.java` :247
- Verify-first clause: at outline, in this order and before any scoping: (1) `main` pins 0.9.10 and the bump PR merged (a plan started earlier has nothing to adapt to); (2) for each of D1 to D7, read the resolved 0.9.10 jar and release notes and decide ship / partly shipped / not shipped, REMOVING a deliverable that did not ship and recording why; (3) for each kept deliverable, re-read the gateway code at the commit PLAN-33, PLAN-31 and PLAN-20 left `main` at and re-derive every line number by symbol; (4) enumerate the gateway-visible behaviour of each file to be deleted and map each element to the shipped API; a refuted mapping re-scopes or drops the deliverable; (5) for D4 and D6, write the new tests first and show them red or the old controls ported green before deleting anything. Refutation of any step loops back to re-scope.

## Expected Surface

All entries are re-swept at outline against the tree at the commit the 0.9.10 bump merged at; a deliverable
removed at outline removes its entries.

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/TokenClientDslJsonReflection.java`:1 — `TokenClientDslJsonReflection` (D1, deleted)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/TokenClientDslJsonReflectionTest.java`:1 — `TokenClientDslJsonReflectionTest` (D1, deleted)
- OBSERVED: `api-sheriff/src/main/resources/application.properties`:245 — `quarkus.native.additional-build-args` (D1) and :343 `quarkus.arc.exclude-types` (D5)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/QueryResponseModeAuthorizationRequestBuilder.java`:1 — `QueryResponseModeAuthorizationRequestBuilder` (D2, deleted)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/PushedAuthorizationRequests.java`:1 — `PushedAuthorizationRequests` (D2, deleted; also PLAN-20's surface)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java`:1 — `ScopedEngineFlows` (D2, flow cache and config factory)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/PushedAuthorizationRequestsTest.java`:1 — `PushedAuthorizationRequestsTest` (D2, deleted or ported)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlowsTest.java`:1 — `ScopedEngineFlowsTest` (D2, ported)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java`:1 — `LoginFlow`, consumer of the D2 seam (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/client/ClientSigningKey.java`:1 — `ClientSigningKey` (D3, slimmed)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/KeyFiles.java`:1 — `KeyFiles` (D3, only if subsumed)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/OidcClientRules.java`:491 — `backChannelOriginShapeRefusal` (D3)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java`:163 — `egressHostRefusal` (D3) and :127 `renderForMessage` (D7)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/TokenValidationRules.java`:109 — `egressHostRefusal` caller (D3) and :214 `JwksRefreshKey` (D6)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`:727 — wiring of D2, D3 (:1295), D4 (:540, :775) and D5 (:727); PLAN-20's surface too
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`:815 — `rotate` (D4)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java`:108 — `TokenRefreshCoordinatorTest` (D4 red tests)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/DiscoveryStatus.java`:1 — `DiscoveryStatus` (D4, deleted)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifier.java`:1 — `SignatureOnlyTokenVerifier` (D5, deleted or reduced)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifierTest.java`:72 — `SignatureOnlyTokenVerifierTest` (D5, ported)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/IdTokenClaimProjection.java`:1 — `IdTokenClaimProjection` (D5, deleted)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/IssuerKeySetStatus.java`:51 — `IssuerKeySetStatus` (D5, deleted)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`:101 — `GatewayReadinessCheck` (D5, deleted)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/DefaultProfileReadinessTest.java`:1 — `DefaultProfileReadinessTest` (D5, the exclusion pin)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ExtensionUnqualifiedBeanExclusionTest.java`:1 — `ExtensionUnqualifiedBeanExclusionTest` (D5)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/LogoutTokenValidator.java`:1 — `LogoutTokenValidator` (D5, only if TokenSheriff owns logout tokens)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/LogoutTokenReplayGuard.java`:1 — `LogoutTokenReplayGuard` (D5, only if TokenSheriff owns logout tokens)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`:1 — `RetryingJwksLoader` (D6, reduced; also PLAN-31, PLAN-33 and staged PLAN-SEC-06's surface)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`:1 — `RetryingJwksLoaderTest` (D6, flood legs ported)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`:281 — `toJwksLoader` (D6; the file-source wrapper :307)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java`:98 — `Jwks` record (D6, key removed)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java`:103 — `IssuerConfig.Jwks` registration (D6)
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`:492 — `unknown_kid_refresh_window_seconds` (D6)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`:476 — jwks key cases (D6)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/ConfigModelContractTest.java`:174 — `Jwks` contract cases (D6)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducerTest.java`:1029 — the window pass-through test (D6)
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java`:44 — rotation and flood legs (D6; native)
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksLateIdpReadinessIT.java`:123 — late-IdP readiness (D5, D6; native)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java`:217 — challenge builder and `extractBearerToken` (:232) (D7)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java`:409 — `CappedByteArrayBodySubscriber` (D7, optional)
- OBSERVED: `pom.xml`:127 — the "Re-measure this paragraph on every `${version.token-sheriff}` bump" BOM comment (:110-133)
- HYPOTHESIS: `api-sheriff/pom.xml`:126 — the `token-sheriff-client-quarkus` dependency (D1, only if re-added or kept) (verify-at-outline)
- OBSERVED: `doc/security-threat-model.adoc`:1349 — `gw-07-jwks-refresh` (D6), summary row :4478, and the `bff-07` key-set availability paragraph :2805-2846 (D5, D6)
- OBSERVED: `doc/adr/0063-An_unknown_key_id_triggers_one_bounded_JWKS_refresh_per_issuer_and_the_refresh_cadence_is_configurable.adoc`:1 — ADR-0063 (D6)
- OBSERVED: `doc/adr/0074-The_token-validation_extensions_metrics_collector_observes_the_gateway_validator_and_its_health_probes_stay_excluded.adoc`:1 — ADR-0074 (D5)
- OBSERVED: `doc/adr/0079-The_oidc_discovery_readiness_datum_reports_provider-metadata_resolution_and_never_changes_the_verdict.adoc`:1 — ADR-0079 (D4)
- OBSERVED: `doc/adr/0082-The_BFF_OIDC_back-channel_inherits_the_engines_host-address_egress_guard_and_relaxes_it_only_for_declared_hosts.adoc`:83 — names `TokenClientDslJsonReflection` (D1)
- OBSERVED: `doc/configuration/authentication-and-oidc.adoc`:264 — the `jwks` cadence-key table (D6)
- OBSERVED: `doc/technical_aspects.adoc`:1 — DSL-JSON passage (D1)
- OBSERVED: `doc/LogMessages.adoc`:90 — BFF records; a new D4 record and the removal of any record whose source is deleted
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/UserInfoEndpoint.java`:1 — consumer of the D4 and D5 claim and ID-token seams (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffClientSecretModeIT.java`:1 — native proof of D1, D2 and D4 (verify-at-outline)

## Dependencies and Sequencing

- Depends on: **PLAN-33** (hard: both edit `RetryingJwksLoader`, the root and `api-sheriff/pom.xml`,
  `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`; PLAN-33 also decides whether
  `token-sheriff-client-quarkus` stays, which D1 needs) and the **TokenSheriff 0.9.10 release pinned on
  `main` by the automated bump PR**. The bump is not this plan's work. If PLAN-33's `maxStaleness`/status
  change or PLAN-31's publish rule are not merged, D6 has nothing stable to reduce.
- Overlaps with: **PLAN-31** (running; merges before PLAN-33) and staged `PLAN-SEC-06` on
  `RetryingJwksLoader`, `RetryingJwksLoaderTest`, ADR-0063 and the GW-07 `gw-07-jwks-refresh` text — D6
  deletes what those plans change; start D6 only after they merged or were dropped, and re-ground against
  the result.
- Overlaps with: **PLAN-20** (staged, held until `PLAN-SEC-05` merges) on `PushedAuthorizationRequests`,
  `ScopedEngineFlows`, `BffRuntimeProducer`, `CallbackEndpoint`, `TokenRefreshCoordinator`, the threat
  model, `doc/LogMessages.adoc` and ADR ordinals. D2 deletes classes PLAN-20 builds on and D4 edits the
  refresh path PLAN-20's refresh-first leg uses: the natural order is PLAN-20 first, then PLAN-34 re-grounded
  on the merged result; the reverse forces PLAN-20 to be rewritten. The order is an operator decision.
- Overlaps with: the `security-hardening-tier-1` plans only through `doc/security-threat-model.adoc`
  (GW-07 rows), `doc/LogMessages.adoc` and `application.properties`; textual-merge risk.
- Adjacent to: **PLAN-20 must also call the library's step-up result verifier** (`StepUpResultVerifier` /
  `StepUpHandler.verifyResult`) once it wires `StepUpCoordinator`, or `acr`/`max_age` are never enforced;
  that is PLAN-20's work, not D4's, and D2 must not remove the `StepUpHandler` re-drive leg PLAN-20 needs.
  `StepUpCoordinator` is not dead code and is not deleted here.
- Adjacent to: gateway-internal duplicates that are NOT token-sheriff concerns and stay out of this plan
  (the CIDR parse in `forward`/`TcpPeerGate` and `EdgeRules`, the `Cookie` header loop copied five times, the
  trust-all guard in `EgressTrustProfileResolver` and `JwksTrustProfileResolver`) and the refresh coordinator's
  per-session single-flight, which stays (ADR-0046; TokenSheriff's `TokenLifecycleManager` is keyed on a
  server-side store and has no stateless cookie mode).
- Pom comment: the root `pom.xml` paragraph at :110-133 says "Re-measure this paragraph on every
  `${version.token-sheriff}` bump" and quotes the resolved Quarkus line and smallrye-config version of 0.9.9;
  it is part of this plan's first commit, not an afterthought, because a stale paragraph would keep asserting a
  convergence nobody has checked.
- Touches `*.java`, `pom.xml`, `application.properties` and the native image: the full quality gate and
  `verify` apply, and `-Pintegration-tests` is mandatory.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-34-adapt-to-token-sheriff-0-9-10.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
