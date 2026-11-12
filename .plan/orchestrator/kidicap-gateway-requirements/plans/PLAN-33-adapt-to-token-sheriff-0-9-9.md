# PLAN-33: Adapt to token-sheriff 0.9.9 — Truthful JWKS Status, Shared Log Sanitiser, Doc Drift, One Unused Dependency

epic: kidicap-gateway-requirements
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-33-adapt-to-token-sheriff-0-9-9.md` and is queued as one row file,
> `queue/PLAN-33.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief, so every per-plan carry is authored here and nowhere else.
> Staged 2026-11-12 from the TokenSheriff separation audit (epic.md Watch "TokenSheriff separation
> audit"; four read-only audits, read from code, nothing run). WS-05 (TokenSheriff separation) is new with
> this plan. This plan uses ONLY token-sheriff 0.9.9 as `main` pins it (`pom.xml` :63); it needs no
> TokenSheriff change. Its sibling PLAN-34 waits for the 0.9.10 release.

## Objective

token-sheriff 0.9.9 made its own JWKS loader status truthful (#830) and added an opt-in staleness bound
(#844), but `RetryingJwksLoader` still reports `OK` whenever its own sticky state is `LOADED`, so a loader
that has left `OK` is never seen leaving it and the staleness bound can never act; its Javadoc and the
threat model still describe the library defect that 0.9.9 fixed. This plan makes the wrapper report the
library's status, replaces the gateway's weaker log sanitiser with the library's public one, corrects the
documentation that 0.9.9 made false, drops the `token-sheriff-client-quarkus` dependency if the operator
approves, and settles by a failing test whether a refresh can overwrite a concurrent widening.

## Deliverables

Proceed as one plan: five deliverables, under the ~6 split guard. D1 is the only one with a behaviour
change on an unauthenticated path; D2 to D4 are independent of it and of each other, so any of them can be
dropped at outline without touching the rest. Record the split-or-proceed rationale as an epic decision.

1. **Truthful JWKS status through `RetryingJwksLoader`, proven by a failing test first.**
   - **Test before fix.** The masking is a behaviour claim read from code only (see Claim Labels). Before
     any production edit, add a test in `RetryingJwksLoaderTest` that is red on `main`: a delegate that
     reports `OK` after load and later reports `UNDEFINED` (the state `HttpJwksLoader.degradeIfStale`
     publishes) while the wrapper still reports `OK` and `keySetState()` is still `LOADED`. Prove it at two
     levels: a hand-written delegate (cheap, exact), and the real `HttpJwksLoader` built with
     `HttpJwksLoaderConfig.builder().maxStaleness(..)` over the existing mock server, whose refresh then
     fails past the bound. If the real-loader leg cannot be driven deterministically, say why and keep the
     hand-written leg. Record both as red on `main` in the PR before the fix is pushed. If a test does NOT
     go red, the premise is refuted: stop, report it, and drop the status half of D1.
   - **The fix.** `getLoaderStatus()` reports the current delegate's status (no sticky `OK`). Settle at
     outline, and state in the PR, what each consumer then sees: the library's `IssuerConfigCache` admits
     an issuer only while `getLoaderStatus() == OK`, so a degraded issuer is rejected fail-closed until a
     refresh installs keys again (`resolveConfig` re-admits it). Enumerate the consumer-visible set with
     one verification criterion per element: `getLoaderStatus()` in each of not-yet-loaded, loading, `OK`,
     degraded-by-staleness, failed-first-load (library recovers on its own refresh), failed-then-wrapper-
     retry (fresh delegate), published-by-unknown-key-id-refresh, closed; `keySetState()` and so the
     readiness payload (`jwks` status token and the counts `issuers` / `issuers_loaded`, which today
     disclose no issuer, URL or host and must keep disclosing none); `getKeyInfo` and `getKeyIds` (a
     degraded delegate keeps answering lookups for its installed keys, as the library documents).
   - **Readiness decision.** Whether a stale-degraded issuer reads `FAILED`/`NOT_LOADED` or stays `LOADED`
     in `IssuerKeySetStatus` is an outline decision (a degraded issuer rejects every token, so `LOADED`
     would report ready for an instance that validates nothing). Decide it, test it, and state the
     readiness consequence in the threat model. Do not change `KeySetState`'s public meaning silently.
   - **Delete only what is proven obsolete.** The wrapper's sticky LOADED/FAILED tracking
     (`recordOutcome`) and the class Javadoc paragraph "Why a wrapper" (:55-62) describe the library
     before 0.9.9. The Javadoc is false and is rewritten. The state tracking is NOT assumed obsolete (see
     Claim Labels): `NOT_LOADED` versus `FAILED` has no `LoaderStatus` counterpart, and the fast first-load
     retry (1 s doubling to 30 s) is still not covered by the library, which refreshes after a failed first
     load only at `refreshIntervalSeconds`. Remove state only where a test shows it carries nothing.
   - **`maxStaleness`: operator decision at outline.** The gateway never sets the library's opt-in
     `maxStaleness`, so the bound is unreachable (see Claim Labels). Decide, in this order: (a) expose it
     as an issuer `jwks` key (suggested spelling `max_staleness_seconds`, one more entry of
     `TokenValidationRules.JwksRefreshKey`, `IssuerConfig.Jwks`, schema, docs, refused on a `file` source
     like the other three cadence keys); (b) the default: omitted means off (the library's own default,
     passed through only when declared), or on with a stated value; (c) or do not expose it and keep only
     the masking fix. Present the availability cost of each to the operator: an enabled bound takes an
     issuer out of service when its JWKS endpoint is unreachable for longer than the bound, even though the
     installed keys are intact. If exposed: a test must go red if the key is deleted entirely (CLAUDE.md
     "a configuration key that parses is not a configuration key that acts" and
     `doc/development/declared-limit-assertion-coverage.adoc`), traced from the YAML key through
     `TokenValidatorProducer.toHttpJwksLoaderConfig` to a rejected token.
   - **Docs.** Replace the stale statements in `doc/security-threat-model.adoc` § `bff-07` ("The library's
     loader status is not truthful after a failed first fetch", the sentence "The upstream defect -- no
     `UNDEFINED` to `OK` transition -- is reported to the library rather than depended on", and the
     "which also repairs the library's re-admission path" claim) with what 0.9.9 does and what the wrapper
     still adds. If a key is exposed, document it in `doc/configuration/authentication-and-oidc.adoc` and
     the GW-07 summary row. A new log record is expected only if D1 adds one; its id is the next free WARN
     id in `doc/LogMessages.adoc` at write time.
2. **Replace `SignatureOnlyTokenVerifier.sanitizeForLog` with the library's `ErrorTextSanitizer`.** The
   gateway copy strips `Character.isISOControl` characters and caps at 128; it passes U+2028, U+2029 and the
   bidirectional controls through into the DEBUG message of an unauthenticated-path rejection (the `iss`
   claim is attacker-controlled before the signature is checked). Use the public
   `de.cuioss.sheriff.token.commons.error.ErrorTextSanitizer` (0.9.9 jar: `sanitize(String)` and
   `sanitize(String, char, int, String)`), delete the private method, and settle at outline which overload
   (the 1-argument form escapes visibly and caps at 256; the 4-argument form replaces with a chosen
   character and appends a marker). State the one visible change: the message no longer drops control
   characters silently. Add the missing test (no test pins this message today): an `iss` containing CR, LF,
   U+2028, U+2029, a bidi control and an over-long value yields a message that holds none of the control
   characters and is bounded, and a matched control with a benign `iss` is echoed unchanged. The other gateway
   sanitiser, `RuleSupport.renderForMessage`, STAYS: its policy (escape everything outside printable ASCII,
   cap 60) is not what the library offers until TokenSheriff adds an escape mode (PLAN-34 D7).
3. **Correct the documentation 0.9.9 made false.** The gateway states in six places that an outright
   `invalid_scope` refresh answer cannot be told apart from an outage (TokenSheriff#763). In code,
   `TokenRefreshCoordinator`: class Javadoc :84-88, the `refreshForScopes` Javadoc :354-357, and the
   `RefreshOutcome.Kind.SCOPE_REFUSED` Javadoc :1025-1026. In docs: ADR-0057 (the bullet "An `invalid_scope`
   refresh answer arms the refresh back-off", :168, which ends "Telling the two apart needs the error code
   from the engine") and `doc/user/bff-session.adoc` (:385 and :504, "gives the gateway nothing to learn from").
   0.9.9 raises `InvalidScopeException` and `RefreshFlow.classify` returns the library's `SCOPE_REFUSED`; the
   gateway code at :565-575 already handles it, joining it to `PRE_REDEMPTION` on purpose so that it takes the
   bounded back-off. Rewrite the passages to say exactly that: the gateway CAN tell the answer apart and
   deliberately gives it the pre-redemption disposition. Keep the gateway's `RefreshOutcome.SCOPE_REFUSED`
   and the library's classification apart in the wording, and do not change the disposition or the
   `ApiSheriff-127` record (its catalogue text lists `invalid_scope` among the causes and stays true). A
   different disposition for `invalid_scope` (for example no WARN because it is no outage) is a behaviour
   decision for the operator, not part of this plan. This also settles, for PLAN-20, its unchecked line
   "whether token-sheriff fixes #763" (Claim Labels). The `api-sheriff/pom.xml` comment the audit named is NOT
   a deliverable: it was already corrected by `0c0680b3` (see Claim Labels).
4. **Drop the unused `token-sheriff-client-quarkus` dependency — only with explicit operator approval.**
   CLAUDE.md requires explicit approval for any dependency change; put this to the operator at outline and
   do nothing here until it is answered. The decision is entangled with PLAN-34: TokenSheriff's own plan
   for the native-image registration would put the DSL-JSON reflection and runtime-init registration INTO
   this extension's deployment module (the only place the library could contribute it), so PLAN-34 D1 would
   have to keep or re-add the dependency. Present both orders: drop now and re-add in PLAN-34 (a second
   approval), or keep it and let PLAN-34 D1 consume it. On approval: remove the dependency from
   `api-sheriff/pom.xml`; prove no behaviour change by the full `verify` including the Quarkus tests, the
   native build and the integration suite (`-Pintegration-tests`), and by the resolved dependency tree
   before and after; re-measure the root `pom.xml` BOM paragraph (below) since the resolved graph changed;
   update the `api-sheriff/pom.xml` comment :72-79 (it names this extension as one of two sources of JAX-RS
   providers) and the root `pom.xml` BOM comment :110 (it lists `-client-quarkus`). **`quarkus-resteasy` is
   NOT removed by this plan**: the extension that remains, `token-sheriff-validation-quarkus`, registers a
   JAX-RS exception mapper and an access-log filter through RESTEasy Classic and declares `quarkus-resteasy`
   optional, so the gateway supplies it. Whether `quarkus-resteasy` (and `quarkus-resteasy-jackson`, which
   may be what supplies the `ObjectMapper` that `GatewayJson` and `ConfigLoader` use) can go is a separate
   operator decision with its own proof; record the finding in the PR, change nothing.
5. **Verify-first: can a refresh overwrite a concurrent widening or step-up landing?** Fix only if a failing
   test proves it. The refresh leader resolves `latest`, runs the engine exchange (network time), then
   persists `rotate(latest, ..)` through `replaceIfPresent`, which checks presence and not that the stored
   record is still the one it derived from. `CallbackEndpoint` writes a merged session for an existing
   session id through `persistReissuingCookie` without any use of the coordinator's single-flight. Build the
   interleaving deterministically in `TokenRefreshCoordinatorTest` (a gated engine exchange, a widening
   landing during it, then the refresh persisting) and assert what the stored record holds afterwards:
   widened scopes `S` and `A`, the refresh token of the widening grant, `acr` and `authTime`. If the test is
   red, fix it with the smallest change that keeps the per-session single-flight (class Javadoc :151; for example a compare against
   the record the rotation was derived from, retried or refused), keep the loser's rotated refresh token from
   leaking (the existing `endSession`/revoke path), and add the matched control (no concurrent write: the
   rotated record is stored). If the test is green, record why (which guard serialises them) and change no
   production code. Login is NOT part of this race: it mints a new session id and destroys the previous
   session (`CallbackEndpoint` :494, :516-518), so only the widening and step-up landing writes an existing
   id.

Done when: `verify -Ppre-commit` and full `verify` pass with zero warnings (the compiler runs with
`-Werror`); `-Pintegration-tests` passes if D1 or D4 changed anything an integration test reads
(`JwksLateIdpReadinessIT`, `JwksKeyRotationIT`); the pull request's open Sonar issue count and the rules
left open are read at the head and stated in the PR (`unknown` for a failed read, never `0`); and every
deliverable that was dropped at outline says why.

## Standing Constraints

- **Pre-1.0 rules.** Never deprecate, never add `@Deprecated`, no backward-compatibility shims: delete what
  D1 and D2 make unnecessary. A rename or removal of a gateway config key needs no migration path.
- **No new dependency without explicit operator approval.** This plan adds none. D4 removes one and is
  itself approval-gated; `quarkus-resteasy` stays.
- **Build only through the executor**: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run
  --command-args "<goals>"`, never `mvn` or `./mvnw`; 10-minute Bash timeout. Run `verify -Ppre-commit` and
  `verify` before every commit that touches build inputs, then `git status --porcelain` and attribute any
  dirty tree before reverting (`doc/development/build-gate-discipline.adoc`). A successful build is not
  evidence that work happened: the D1 and D5 tests must be seen red on `main` first.
- **Zero warnings, enforced by `-Werror`.** A javac warning fails the build; migrate off the warned
  construct, never add `@SuppressWarnings`.
- **Logging.** `de.cuioss.tools.logging.CuiLogger`, `%s` substitution only, exception parameter first,
  `LogRecord` for INFO/WARN/ERROR. Any new record takes the NEXT FREE id of its range (INFO 001-099, WARN
  100-199, ERROR 200-299), read from `doc/LogMessages.adoc` and the log-message classes at write time and
  never assumed, and is documented in `doc/LogMessages.adoc`. No issuer URL, host, key id or token content
  in a log or a readiness payload.
- **ADRs.** No new ADR is expected. If D1's readiness decision or the D4 dependency decision needs one, take
  the next free ordinal at write time from `doc/adr` (never an ordinal copied from another spec) and follow
  `doc/development/adr-writing.adoc`.
- **Testing.** JUnit 5, AAA, no Mockito/PowerMock/Hamcrest, CUI test generator for data, at least 80%
  coverage. Every new or changed control gets a matched positive and negative control.
- **Security.** The change must not loosen a fail-closed posture: a degraded or unloaded issuer rejects
  tokens, readiness stays non-disclosing, attacker-controlled values stay sanitised before they reach a log.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks. In-code suppression with a rationale
  (`// NOSONAR java:SXXXX <why>`) is the project's first choice; never a silent server-side won't-fix.

## Claim Labels

- OBSERVED: `RetryingJwksLoader.getLoaderStatus` returns `LoaderStatus.OK` whenever its own `state()` is `KeySetState.LOADED` and the current delegate's status otherwise — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `getLoaderStatus` (:644-647)
- OBSERVED: the only writer of the sticky state after the first load is `recordOutcome` (called once, from the load completion, :338) and `publishRefreshed` (sets `LOADED`); no code path moves `LOADED` back when a delegate's own status later changes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `recordOutcome`, `publishRefreshed`
- OBSERVED: the library's issuer cache treats an issuer as healthy only while `getJwksLoader().getLoaderStatus() == LoaderStatus.OK`, checked on every `resolveConfig`, and re-admits a recovered issuer — read at `token-sheriff-validation/src/main/java/de/cuioss/sheriff/token/validation/IssuerConfigCache.java` § `isHealthy` (:208-211), `resolveConfig` (:174-200), in /home/oliver/git/TokenSheriff (0.9.9 plus release commit)
- OBSERVED: `HttpJwksLoader` reports `OK` only while a usable key set is installed, publishes `UNDEFINED` after a failed first load with background refresh enabled and `OK` again on a later successful cycle (#830), and, only with `maxStaleness` configured, `degradeIfStale` sets `UNDEFINED` once the time since the last successful load exceeds the bound while installed and retired keys stay in place — read at `token-sheriff-validation/src/main/java/de/cuioss/sheriff/token/validation/jwks/http/HttpJwksLoader.java` § class Javadoc (:60-90) and `degradeIfStale` (:1302-1318), in /home/oliver/git/TokenSheriff
- OBSERVED: 0.9.9 ships `HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder.maxStaleness(Duration)` and `allowedEgressHost`, and a public `ErrorTextSanitizer` with `sanitize(String)` and `sanitize(String, char, int, String)` — read by `javap` on `~/.m2/repository/de/cuioss/sheriff/token/token-sheriff-validation/0.9.9/token-sheriff-validation-0.9.9.jar`
- OBSERVED (asserted absence): `main` never sets the library's `maxStaleness` — `grep -rIl "maxStaleness\|max_staleness\|max-staleness"` over the repository, excluding `.plan/`, `target/` and `node_modules/`, finds no file (Java, properties, YAML, JSON, AsciiDoc); `TokenValidatorProducer.toHttpJwksLoaderConfig` (:388-440) passes only `refreshIntervalSeconds`, `keyRotationGracePeriod` and the egress, TLS and host-name settings
- HYPOTHESIS: because of the two observations above, a delegate that has left `OK` (staleness) is still reported `OK` by the wrapper, so the library's cache keeps the issuer healthy and readiness keeps `LOADED` — this is a mechanism read from code on one side only, NOT run; confirm or refute by the failing test of deliverable 1 at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java` § a new nested test beside `HttpFirstLoad` (:261) (verify-at-outline)
- HYPOTHESIS: the audit's statement that the wrapper's own LOADED/FAILED tracking is "obsolete since 0.9.9" is an overclaim — `KeySetState.NOT_LOADED` and `FAILED` have no `LoaderStatus` counterpart, readiness reads them, and the 1 s to 30 s first-load retry is not provided by the library (0.9.9 starts the refresh after a failed first load but only at `refreshIntervalSeconds`, default 600) — confirm or refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/IssuerKeySetStatus.java` § `KeySetState` (:51), `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java` (:222-227) and the class Javadoc of `HttpJwksLoader` (verify-at-outline)
- OBSERVED: the `RetryingJwksLoader` class Javadoc (:55-62, the audit cited ~:56-65) still says the library's status is "not truthful after a failure", that a failed first fetch "stays non-OK even once keys arrive" and that the library's cache re-admits only on `OK`; the same claim stands in `doc/security-threat-model.adoc` § `bff-07` (:2812-2816 and :2829-2830, NOT in GW-07, which the audit did not list) — both contradicted by the `HttpJwksLoader` Javadoc read above
- OBSERVED: the unknown-key-id refresh, the retired-delegate grace period and `keyIds` all live in the same file as D1's change, and `KeySetState` gates `onUnknownKeyId` (:669) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `getKeyInfo`
- OBSERVED: `SignatureOnlyTokenVerifier.sanitizeForLog` (:181-186) removes characters for which `Character.isISOControl` holds (C0, DEL and C1 only) and truncates to 128, so U+2028, U+2029 and bidirectional controls are not removed; it is used at :138 (the rejection message of an unknown or disabled issuer, which reaches a DEBUG log) and :146 — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifier.java` § `sanitizeForLog`
- OBSERVED (asserted absence): `SignatureOnlyTokenVerifierTest` has no test of the unknown-issuer message or of sanitising — `grep` for `NO_ISSUER_CONFIG`, `No enabled issuer`, `sanitiz` and control-character escapes finds nothing relevant — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifierTest.java`
- OBSERVED: `TokenRefreshCoordinator` says an `invalid_scope` answer is "not told apart" or batched with outages at :84-88 (audit cited ~:75-80), :354-357 (audit ~:350) and :1025-1026 (not in the audit), while `disposeRefusal` (:564-575) already switches on `RefreshFailureClassification.Kind.SCOPE_REFUSED` together with `PRE_REDEMPTION` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java` § class Javadoc, `refreshForScopes`, `disposeRefusal`
- OBSERVED: ADR-0057 :168 and `doc/user/bff-session.adoc` :385 and :504 repeat the claim that the gateway cannot tell `invalid_scope` from an outage, and `doc/LogMessages.adoc` `ApiSheriff-127` (:90) lists `invalid_scope` among the causes of a pre-redemption failure — read at those files
- OBSERVED: the library classifies `InvalidScopeException` as `RefreshFailureClassification.Kind.SCOPE_REFUSED` — read at `token-sheriff-client/src/main/java/de/cuioss/sheriff/token/client/flow/RefreshFlow.java` § `classify` (:313) and `InvalidScopeException` (:43, :67), in /home/oliver/git/TokenSheriff
- OBSERVED (the audit's second half of D3 is STALE): the comment at `api-sheriff/pom.xml` :72-79 does not cite a live `GatewayExceptionMapper`; commit `0c0680b3` rewrote it to say that class "no longer exists" — read at `api-sheriff/pom.xml` § the `quarkus-resteasy` comment. No pom comment fix is owed except the one D4 causes.
- OBSERVED (asserted absence): no file in the repository other than `pom.xml`/`api-sheriff/pom.xml` mentions `token-sheriff-client-quarkus` or the package `de.cuioss.sheriff.token.client.quarkus` — `grep -rIn "client-quarkus\|client\.quarkus"` excluding `.plan/`, `target/`, `node_modules/` finds only `pom.xml:110`, `api-sheriff/pom.xml:76` and `:126`; the many `de.cuioss.sheriff.token.client.*` imports are the `token-sheriff-client` jar, a different artifact
- OBSERVED (asserted absence): no gateway class injects a bean the extension's producer provides — `grep` over `api-sheriff/src/main` for `TokenStore`, `RefreshScheduler`, `TokenLifecycleManager` and for `@Inject` of `TokenEndpointClient`, `DiscoveryResolver`, `UserInfoClient`, `RevocationClient`, `ClientConfiguration` finds none; `BffRuntimeProducer` builds its own `new TokenEndpointClient(clientConfiguration)` (:550). Reading imports alone would not have proved this, because a producer-supplied bean is injected by its type, not by an import of the extension package
- OBSERVED: the extension's deployment module has three `@BuildStep`s — a feature, `AdditionalBeanBuildItem(TokenSheriffClientProducer)` and `ResteasyJaxrsProviderBuildItem(ClientExceptionMapper)` — and registers no reflection and no runtime-initialised class; its runtime module depends on `token-sheriff-client` and `token-sheriff-validation-quarkus` — read at `token-sheriff-quarkus-parent/token-sheriff-client-quarkus-deployment/src/main/java/de/cuioss/sheriff/token/client/quarkus/deployment/TokenSheriffClientProcessor.java` and `token-sheriff-quarkus-parent/token-sheriff-client-quarkus/pom.xml`, in /home/oliver/git/TokenSheriff
- HYPOTHESIS: the TokenSheriff epic `api-sheriff-seams` PLAN-01 puts the DSL-JSON reflection and runtime-init registration into that same deployment module, so PLAN-34 D1 needs the dependency — source is the audit and the TS epic narrative, not code; confirm against the released 0.9.10 at PLAN-34 outline, and put the dependency question to the operator with this knowledge (verify-at-outline)
- OBSERVED: `token-sheriff-validation-quarkus` registers `ProblemDetailExceptionMapper` and `CustomAccessLogFilter` through `ResteasyJaxrsProviderBuildItem` and declares `quarkus-resteasy` with `<optional>true</optional>`; the gateway's main sources use no `jakarta.ws.rs` or RESTEasy type — read at `token-sheriff-quarkus-parent/token-sheriff-validation-quarkus-deployment/src/main/java/de/cuioss/sheriff/token/quarkus/deployment/TokenSheriffProcessor.java` (:201-223) and `token-sheriff-quarkus-parent/token-sheriff-validation-quarkus/pom.xml`, and by `grep` over `api-sheriff/src/main`
- HYPOTHESIS: `quarkus-resteasy-jackson` or `jackson-dataformat-yaml` is what supplies the `ObjectMapper` that `GatewayJson` and `ConfigLoader` use — confirm or refute with `dependency:tree -pl api-sheriff` and the imports of `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/GatewayJson.java` (verify-at-outline; only relevant to the separate `quarkus-resteasy` decision)
- OBSERVED: the refresh persist is `sessionBinding.persist(rotated, now)` (`TokenRefreshCoordinator` :547), which `ServerSessionBinding.persist` (:114-126) maps to `InMemorySessionStore.replaceIfPresent` (:230-241): a presence check under the store monitor, no comparison with the record the rotation was derived from; `rotate` copies `sub`, `sid`, `acr`, `authTime`, `sessionNonce` from `previous` and the granted scopes from the exchange (:815-835)
- OBSERVED: the widening callback writes an existing session id through `sessionBinding.persistReissuingCookie(merged, now)` (`CallbackEndpoint` :454), `merged` built from the live session it resolved earlier (:433-450), and `CallbackEndpoint` references neither the refresh coordinator nor its `inFlight` single-flight map — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java` § `singleFlight` (:397-415)
- OBSERVED: a fresh login does not write an existing session id: it builds the record with `SessionRecord.newSessionId()` (:494), destroys the previous session (:516-518) and calls `bind` (:523) — the audit's "re-login" leg of the race does not exist
- HYPOTHESIS: a refresh whose exchange is in flight can therefore overwrite a widening or step-up merge that landed meanwhile (losing the widened `S`/`A`, the widening grant's refresh token, the updated `acr`) — read from code only, NOT run; confirm or refute by the failing test of deliverable 5 (verify-at-outline)
- OBSERVED: the library ships `StepUpResultVerifier` and `StepUpHandler` (`token-sheriff-client/.../client/flow/`), the gateway constructs a `StepUpHandler` (`BffRuntimeProducer` :703-716) and references `StepUpResultVerifier` nowhere in `api-sheriff/src`; `BffRuntime.stepUpCoordinator()` (:247) is declared and has no production caller, and the producer seeds the coordinator with the silent-satisfaction seam `(sessionRecord, challenge, now) -> Optional.empty()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java`
- OBSERVED: `PLAN-31` is `running` (queue row status), claims `RetryingJwksLoader`, `RetryingJwksLoaderTest`, ADR-0063 and `JwksKeyRotationIT`, and its spec still says `main` pins token-sheriff 0.9.8 (it pins 0.9.9) — read at the epic ledger `queue/PLAN-31.json`, `plans/PLAN-31-jwks-exact-publish-rule.md` and `pom.xml` :63; and staged `PLAN-SEC-06` (epic `security-hardening-tier-1`, deliverable 3) also changes `RetryingJwksLoader.settleRefresh` and `RetryingJwksLoaderTest` — read at that epic's `plans/PLAN-SEC-06-tier-2-quick-wins.md`
- Verify-first clause: before scoping, the consuming phase must (1) re-read `RetryingJwksLoader` at the commit PLAN-31 merged at and re-derive every line number here by symbol; (2) run the D1 failing tests on `main` — if the masking test does not go red, loop back and re-scope D1 to the readiness and documentation half only; (3) read the resolved 0.9.9 `HttpJwksLoader` behaviour for a delegate whose first load failed and whose refresh then succeeds, because D1's fix must not regress the wrapper's recovery of a late identity provider (`JwksLateIdpReadinessIT`); (4) obtain the operator's answers to the two decisions below before scoping D1's key and D4; (5) run the D5 interleaving test before writing any fix. Refutation of (2) or (5) loops back to re-scope, it is not a licence to fix anyway.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`:644 — `getLoaderStatus` (also `recordOutcome` :361, `keySetState` :612, class Javadoc :55-62)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`:261 — `HttpFirstLoad` (the nested group the new tests sit beside; `TrackedLoader` :1460, `FailingLoader` :1476)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/IssuerKeySetStatus.java`:51 — `KeySetState` (only if the readiness decision changes it)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`:222 — the `loadedCount` / `failedCount` verdict
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`:388 — `toHttpJwksLoaderConfig` (only if a `maxStaleness` key is exposed)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducerTest.java`:1029 — the `unknown_kid_refresh_window_seconds` pass-through test, the model for a key test
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java`:98 — `Jwks` record, only if a `maxStaleness` key is exposed (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/TokenValidationRules.java`:214 — `JwksRefreshKey`, only if a key is exposed (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java`:103 — `IssuerConfig.Jwks` native reflection registration, only if the record changes (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/resources/schema/gateway.schema.json`:492 — the `jwks` cadence keys, only if a key is exposed (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/ConfigModelContractTest.java`:174 — `Jwks` contract cases, only if the record changes (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`:476 — the `jwks` key load and range cases, only if a key is exposed (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifier.java`:181 — `sanitizeForLog`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifierTest.java`:72 — `SignatureOnlyTokenVerifierTest`, gains the sanitiser test
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`:84 — the three stale Javadoc passages (:84-88, :354-357, :1025-1026) and the persist at :547
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java`:108 — the class the D5 interleaving test joins
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/InMemorySessionStore.java`:230 — `replaceIfPresent`, only if D5 is proven and the fix lands in the store (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/ServerSessionBinding.java`:114 — `persist`, only if D5 is proven (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java`:454 — `persistReissuingCookie` call, only if the D5 fix is on the widening side (verify-at-outline)
- OBSERVED: `api-sheriff/pom.xml`:126 — the `token-sheriff-client-quarkus` dependency (D4; the comment :72-79 changes with it)
- OBSERVED: `pom.xml`:110 — the root BOM comment that lists `-client-quarkus` and carries "Re-measure this paragraph on every `${version.token-sheriff}` bump" (:127); re-measure only if D4 lands
- OBSERVED: `doc/security-threat-model.adoc`:2812 — § `bff-07` stale loader-status paragraph (:2805-2846); GW-07 `gw-07-jwks-refresh` (:1349) and its summary row (:4478) only if a key is exposed
- OBSERVED: `doc/configuration/authentication-and-oidc.adoc`:264 — the `jwks` cadence-key table, only if a key is exposed
- OBSERVED: `doc/adr/0057-Session_routes_obtain_their_declared_scopes_by_refreshing_inside_the_grant_and_widening_the_live_session_outside_it.adoc`:168 — the stale "Telling the two apart needs the error code from the engine" consequence
- OBSERVED: `doc/user/bff-session.adoc`:385 — stale "cannot tell that answer" paragraph (and :504)
- HYPOTHESIS: `doc/LogMessages.adoc` — only if D1 adds a log record (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksLateIdpReadinessIT.java`:123 — re-run, edit only if D1 changes the readiness contract (verify-at-outline)

## Dependencies and Sequencing

Operator decisions needed at outline (the plan stops and asks; it does not choose):

- **`maxStaleness`**: expose it as a gateway key or not, its spelling, and its default (off, or on with a
  value) — D1.
- **`token-sheriff-client-quarkus`**: approve or refuse the drop, knowing that TokenSheriff's planned
  native-image registration lives in that extension (PLAN-34 D1) — D4. `quarkus-resteasy` is a separate,
  later decision and not part of this plan.
- Also decide at outline: the readiness reading of a stale-degraded issuer (D1) and, if D5 is proven, which
  side of the race carries the fix.

Sequencing and shared surfaces (read from the ledgers, not guessed):

- Depends on: PLAN-31 merged — or the operator accepting the overlap. PLAN-31 is RUNNING and edits
  `RetryingJwksLoader` (the approximate-rule Javadoc :77-90, `settleRefresh` and its Javadoc :546-552,
  removal of the unused `kid` parameter from `onUnknownKeyId`, `runRefresh`, `settleRefresh`) and
  `RetryingJwksLoaderTest` (:981, :1077 and the `ROTATED_KEY_ID` fixtures), plus ADR-0063, the GW-07
  `gw-07-jwks-refresh` threat-model subsection and `JwksKeyRotationIT`. D1 edits the same file and test
  (class Javadoc :55-62, `getLoaderStatus`, `recordOutcome`). The lines are disjoint but the Javadoc block
  is contiguous and the test file is edited in both: start PLAN-33 after PLAN-31 merges and re-derive every
  line number against the merged file. If the operator accepts the overlap instead, state in the PR which
  commit each region was written against.
- Overlaps with: staged `PLAN-SEC-06` (epic `security-hardening-tier-1`, WS-04): its deliverable 3 also
  edits `RetryingJwksLoader.settleRefresh` and `RetryingJwksLoaderTest` and amends ADR-0063's residual, and
  it duplicates PLAN-31's exact-publish rule, so the operator must settle PLAN-31 versus PLAN-SEC-06 first;
  PLAN-33 sequences after whichever of them is live. `PLAN-SEC-05` (running) shares `doc/security-threat-model.adoc`
  (GW-07 rows) and possibly `doc/LogMessages.adoc` with D1's documentation edits: different sections, a
  textual-merge risk only.
- Overlaps with: PLAN-20 (staged, held by the operator until `PLAN-SEC-05` merges) on
  `TokenRefreshCoordinator` (:65 `refreshForScopes`, the Javadoc D3 corrects and the persist D5 may touch),
  `CallbackEndpoint` (D5), `BffRuntimeProducer`, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`.
  PLAN-33 touches none of PLAN-20's step-up wiring. Prefer PLAN-33 first: D3 settles PLAN-20's unchecked line
  "whether token-sheriff fixes TokenSheriff#763", and D5, if proven, changes the persist PLAN-20's
  refresh-first leg depends on; PLAN-20 then re-grounds against the merged result.
- Adjacent to: **PLAN-20 wires `StepUpCoordinator` and must also call the library's step-up result verifier**
  (`StepUpResultVerifier` / `StepUpHandler.verifyResult`) on the step-up callback, or `acr` and `max_age` are
  never enforced after a step-up; the gateway references the verifier nowhere. That is PLAN-20's work, not
  this plan's. `StepUpCoordinator` is NOT dead code: it is the staged wiring target of PLAN-20 and has no
  caller only because that wiring has not landed. PLAN-33 must not delete it, nor `BffRuntime.stepUpCoordinator()`,
  nor the producer seam that builds it.
- Adjacent to: the refresh-result verification gap (`TokenRefreshCoordinator.rotate` stores the refreshed ID
  token unverified and keeps the previous `sub`, :817-823) is NOT in this plan. Its TokenSheriff seam is
  private; it is PLAN-34 D4 once TokenSheriff publishes it. Whether an interim gateway-side check is wanted
  is an operator call recorded in the epic Watch, not part of D5.
- Adjacent to: PLAN-34 (WS-05) depends on this plan: it shares `RetryingJwksLoader`, the root and
  `api-sheriff/pom.xml`, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`. The version pin
  (`pom.xml` :63) is untouched here; the release workflow owns every bump.
- Touches `*.java` and `pom.xml`: the full quality gate and `verify` apply, and `-Pintegration-tests` for D1
  and D4.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-33-adapt-to-token-sheriff-0-9-9.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
