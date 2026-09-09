/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Covers {@link BffRuntimeProducer}: the runtime is active (and its reserved handlers and session
 * stage are wired) only when a global {@code oidc} block with {@code session.mode=server} and a
 * {@code redirect_uri} is configured, and inert (bearer-only) otherwise. Assembly resolves no OIDC
 * discovery (that is deferred to first engine use), so the producer builds a working runtime without
 * a live IdP; the live engine round-trips are covered by the Keycloak integration tests.
 */
@EnableGeneratorController
@DisplayName("BffRuntimeProducer — server-mode activation and inert bearer-only default")
class BffRuntimeProducerTest {

    private static final String ORIGIN = "https://gw.example.com";
    private static final String REDIRECT_URI = ORIGIN + "/auth/callback";
    private static final String ISSUER = "https://idp.example.com";
    private static final String SUBJECT = "session-subject";
    /**
     * Deliberately a fixed literal, not generated: the rotated-session assertion's whole content is
     * that the mediated token is <em>this</em> value rather than the pre-refresh one the session was
     * built with, so the two must be distinguishable by construction.
     */
    private static final String ROTATED_ACCESS_TOKEN = "rotated-access-token";

    private final TokenValidator tokenValidator = TokenValidator.builder()
            .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

    @Nested
    @DisplayName("Active server-mode runtime")
    class Active {

        private final BffRuntime runtime = producer(serverModeOidc()).bffRuntime();

        @Test
        @DisplayName("Should activate the runtime and expose the session stage and CSRF defence")
        void shouldActivate() {
            assertTrue(runtime.isActive());
            assertNotNull(runtime.sessionStage());
            assertNotNull(runtime.csrfDefence());
            assertNotNull(runtime.stepUpCoordinator());
        }

        /**
         * Assembly must not perform an OIDC discovery round-trip — that is deferred to first engine
         * use, which is what lets the gateway boot without a live IdP. A bare
         * {@code assertDoesNotThrow} cannot say this: a producer that <em>did</em> resolve discovery
         * against a reachable IdP would complete just as quietly. The issuer here is therefore on
         * {@code 192.0.2.0/24} (RFC 5737 TEST-NET-1, guaranteed unroutable), so a discovery attempt
         * would burn the connect timeout instead of returning — which the preemptive bound catches.
         */
        @Test
        @DisplayName("Should assemble without resolving OIDC discovery (no live IdP required)")
        void shouldAssembleWithoutDiscovery() {
            // Arrange — a well-formed server-mode configuration whose issuer nothing can reach
            OidcConfig unreachableIssuer = OidcConfig.builder()
                    .issuer("https://192.0.2.1:9999/realms/nowhere")
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/auth/userinfo")
                            .allowedClaims(List.of("sub", "name"))
                            .defaultView(List.of("sub"))
                            .build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .build();

            // Act
            BffRuntime assembled = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> producer(unreachableIssuer).bffRuntime(),
                    "assembly must not reach the IdP — a discovery round-trip against an unroutable "
                            + "issuer would exhaust the connect timeout instead of returning");

            // Assert — and what came back is a fully wired runtime, not a degraded or inert one
            assertTrue(assembled.isActive(),
                    "an unreachable issuer still yields an active runtime, because discovery is deferred");
            assertEquals(401, assembled.dispatch(ReservedEndpoint.USER_INFO,
                            new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                            Instant.parse("2026-07-25T10:00:00Z")).status(),
                    "the reserved endpoints are wired although no discovery ever ran");
        }

        @Test
        @DisplayName("Should keep the back-channel path un-gated — an absent logout_token yields the 400 contract")
        void shouldNotGateBackchannelInServerMode() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, "other=value", "POST"),
                    Instant.parse("2026-07-25T10:00:00Z"));

            assertEquals(400, response.status(),
                    "the store-backed binding supports IdP destruction, so the endpoint stays open");
        }

        @Test
        @DisplayName("Should wire the user-info fold reachably — no session yields 401")
        void shouldWireUserInfo() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                    Instant.parse("2026-07-25T10:00:00Z"));
            assertEquals(401, response.status());
        }

        /**
         * The wiring-level half of the response-mode assertion — the seam's own behaviour is pinned by
         * {@code QueryResponseModeAuthorizationRequestBuilderTest}.
         * <p>
         * This exists because the failure mode it guards is an <em>omission</em>, and an omission is
         * invisible to a behavioural test of the seam. The engine's
         * {@link AuthorizationRequestBuilder} emits {@code response_mode=form_post} unconditionally,
         * and its shorter constructors silently install that default: a future refactor that rebuilt
         * {@code AuthorizationCodeFlow} through the 4-argument constructor, or {@code StepUpHandler}
         * through its no-argument one, would compile, pass every seam test, and quietly reintroduce
         * the cross-site POST callback on which the {@code SameSite=Lax} binding cookie is dropped.
         * <p>
         * The assertion is therefore made against the object graph the producer actually built, and
         * it is <strong>type-directed rather than name-directed</strong>: it finds every
         * {@code AuthorizationRequestBuilder} reachable from the assembled runtime and requires each
         * one to be the gateway's query-mode subclass. Renaming an engine field does not break it;
         * reverting a seam to the engine default does — which is exactly the intended sensitivity.
         */
        @Test
        @DisplayName("Should wire the query-mode response builder into every engine authorization seam")
        void shouldWireQueryResponseModeIntoEveryAuthorizationSeam() {
            List<AuthorizationRequestBuilder> wired = reachableInstancesOf(runtime, AuthorizationRequestBuilder.class);

            assertFalse(wired.isEmpty(),
                    "no AuthorizationRequestBuilder was reachable from the assembled runtime — this test "
                            + "must never pass vacuously; if the producer's wiring moved, retarget the walk");
            assertAll("every engine seam that builds an authorization URL carries the query-mode builder",
                    wired.stream().map(builder -> (Executable) () ->
                            assertInstanceOf(QueryResponseModeAuthorizationRequestBuilder.class, builder,
                                    "an engine seam is still on the default builder, which emits "
                                            + "response_mode=form_post")));
        }
    }

    /**
     * Collects every instance of {@code target} reachable from {@code root} by walking instance
     * fields, following lambda captures so a collaborator held only inside a closure is still seen.
     * <p>
     * The walk is bounded to the gateway's and the engine's own packages: it never descends into JDK
     * or container types, which keeps it away from the strongly-encapsulated {@code java.*} modules
     * and stops it wandering through collections and class loaders. A field the JVM refuses to open
     * is skipped rather than failing the walk — the caller's non-empty assertion is what guarantees
     * the result is still meaningful.
     * <p>
     * Every caller that asserts an <em>absence</em> MUST be paired with one asserting the matching
     * presence, because an over-skipped walk returns the empty list too: only the positive control
     * distinguishes "the producer did not wire it" from "the walk could not see it".
     *
     * @param root   the assembled object graph to search
     * @param target the collaborator type to collect
     * @param <T>    the collaborator type
     * @return every reachable instance of {@code target}, in walk order
     */
    private static <T> List<T> reachableInstancesOf(Object root, Class<T> target) {
        List<T> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Object current = pending.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (target.isInstance(current)) {
                found.add(target.cast(current));
                continue;
            }
            for (Class<?> type = current.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(current);
                        if (value != null && isWalkable(value.getClass())) {
                            pending.push(value);
                        }
                    } catch (ReflectiveOperationException | InaccessibleObjectException _) {
                        // A field the JVM will not open tells us nothing; the non-empty assertion above
                        // is what keeps an over-skipped walk from passing vacuously. Only the two
                        // exceptions setAccessible/get can actually raise here are caught: a broader
                        // catch would swallow a genuine defect in the walk itself.
                    }
                }
            }
        }
        return found;
    }

    /** Restricts the walk to gateway and engine types — never JDK, container or collection internals. */
    private static boolean isWalkable(Class<?> type) {
        String name = type.getName();
        return name.startsWith("de.cuioss.sheriff.gateway.") || name.startsWith("de.cuioss.sheriff.token.client.");
    }

    @Nested
    @DisplayName("Active cookie-mode runtime")
    class ActiveCookieMode {

        private final BffRuntime runtime = producer(cookieModeOidc()).bffRuntime();

        @Test
        @DisplayName("Should activate the runtime for session.mode=cookie, exactly as for server mode")
        void shouldActivateForCookieMode() {
            assertTrue(runtime.isActive(), "cookie mode is a recognised BFF mode, not a bearer-only gateway");
            assertNotNull(runtime.sessionStage());
            assertNotNull(runtime.csrfDefence());
            assertNotNull(runtime.stepUpCoordinator());
        }

        @Test
        @DisplayName("Should wire the same reserved endpoints — no session yields 401 from the user-info fold")
        void shouldWireTheSameReservedEndpoints() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                    Instant.parse("2026-07-25T10:00:00Z"));
            assertEquals(401, response.status(), "both modes drive identical wiring above the session binding");
        }

        @Test
        @DisplayName("Should still register the back-channel path, answering a deliberate uncacheable 404")
        void shouldRegisterBackchannelPathGatedTo404() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null,
                            "logout_token=abc.def.ghi", "POST"),
                    Instant.parse("2026-07-25T10:00:00Z"));

            assertEquals(404, response.status(),
                    "the reserved path stays registered and returns a deliberate 404, never falling through");
            assertEquals("no-store", response.headers().get("Cache-Control"),
                    "the gated outcome is served uncacheable exactly as the 200/400 outcomes are");
        }

        @Test
        @DisplayName("Should boot cookie mode without an encryption key, generating one on startup")
        void shouldBootCookieModeWithoutKey() {
            OidcConfig noKey = OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("cookie").build())
                    .build();

            BffRuntime generated = producer(noKey).bffRuntime();

            assertTrue(generated.isActive(),
                    "omitting the key selects generate-on-startup, a supported production mode — not a boot failure");
            assertNotNull(generated.sessionStage());
        }

        @Test
        @DisplayName("Should refuse an encryption key that is not a base64 AES-256 value")
        void shouldRefuseMalformedKey() {
            BffRuntimeProducer nonBase64 = producer(cookieModeOidcWithKey("not-base64-~~~"));
            BffRuntimeProducer aes128 =
                    producer(cookieModeOidcWithKey(Base64.getEncoder().encodeToString(new byte[16])));

            assertThrows(IllegalStateException.class, nonBase64::bffRuntime);
            assertThrows(IllegalStateException.class, aes128::bffRuntime,
                    "an AES-128 key is refused — the codec is specified as AES-256-GCM");
        }
    }

    /**
     * {@code oidc.session.refresh.enabled} is the switch for the whole transparent-refresh path, and
     * this is where it is proven to <em>act</em> rather than merely parse: the key was carried in the
     * config model and in every BFF descriptor while {@link BffRuntimeProducer} read only
     * {@code leewaySeconds()}, so setting it to {@code false} changed nothing at all.
     * <p>
     * The assertion is made against the object graph the producer actually built, because the
     * failure mode is an <em>omission</em> — a producer that silently stopped consulting the key
     * would keep assembling a coordinator and keep every behavioural test green. The three cases
     * below are a matched control set: the disabled case asserts an absence, and the two enabled
     * cases (explicit {@code true}, and the omitted-key default) are what prove the walk can see a
     * coordinator when one is wired, so the absence means "not assembled" and not "not found".
     */
    @Nested
    @DisplayName("Transparent-refresh switch (oidc.session.refresh.enabled)")
    class RefreshSwitch {

        @Test
        @DisplayName("Should assemble the refresh coordinator when refresh.enabled is true")
        void shouldWireCoordinatorWhenEnabled() {
            assertFalse(coordinatorsFor(refreshOidc(Boolean.TRUE)).isEmpty(),
                    "an explicitly enabled refresh must reach the stage's refresh seam");
        }

        @Test
        @DisplayName("Should default to enabled when the key — or the whole refresh block — is omitted")
        void shouldDefaultToEnabled() {
            assertAll("an absent declaration resolves the documented default: refresh on",
                    () -> assertFalse(coordinatorsFor(refreshOidc(null)).isEmpty(),
                            "a refresh block declaring only leeway_seconds keeps refresh on"),
                    () -> assertFalse(coordinatorsFor(serverModeOidc()).isEmpty(),
                            "no refresh block at all resolves the same default"));
        }

        @Test
        @DisplayName("Should assemble no refresh coordinator at all when refresh.enabled is false")
        void shouldOmitCoordinatorWhenDisabled() {
            BffRuntime disabled = producer(refreshOidc(Boolean.FALSE)).bffRuntime();

            assertTrue(disabled.isActive(),
                    "turning refresh off is a policy choice, not a de-activation — the BFF stays wired");
            assertTrue(reachableInstancesOf(disabled, TokenRefreshCoordinator.class).isEmpty(),
                    "refresh.enabled=false must leave no coordinator in the graph; one that is present "
                            + "but unreachable would still hold the engine RefreshFlow and the leeway");
        }

        private List<TokenRefreshCoordinator> coordinatorsFor(OidcConfig oidc) {
            return reachableInstancesOf(producer(oidc).bffRuntime(), TokenRefreshCoordinator.class);
        }
    }

    /**
     * Server-mode configuration whose {@code refresh} block declares {@code leeway_seconds} and the
     * supplied {@code enabled} value — {@code null} standing for the key being omitted, which is the
     * case that must resolve the default.
     */
    private static OidcConfig refreshOidc(@Nullable Boolean enabled) {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("server")
                .ttlSeconds(3600)
                .refresh(OidcConfig.Refresh.builder().enabled(enabled).leewaySeconds(30).build())
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    /**
     * The switch's two <em>decisions</em>, as opposed to the wiring {@link RefreshSwitch} asserts.
     * <p>
     * {@code RefreshSwitch} can only see that a coordinator is or is not present in the object graph;
     * it cannot see what the seams built around it actually do, because the producer holds them as
     * lambdas that no assembled-runtime test can invoke without a live IdP. The three seams are
     * therefore extracted to package-private factories and driven here directly — with a real
     * store-backed binding and hand-built engine objects, no live token endpoint and no test-double
     * framework, exactly as {@code TokenRefreshCoordinatorTest} drives the coordinator itself.
     * <p>
     * Two of the decisions below are security-relevant rather than cosmetic: dropping the refresh
     * token at login when refresh is off keeps a credential the gateway will never redeem out of the
     * session store (and out of the sealed browser cookie in cookie mode), and mapping a
     * {@code FAILED} refresh to {@link Optional#empty()} is what stops the stage mediating the
     * pre-refresh token of a session the engine just revoked.
     */
    @Nested
    @DisplayName("Refresh seams — the decisions the assembly's lambdas carry")
    class RefreshSeams {

        private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
        private static final Duration LEEWAY = Duration.ofSeconds(60);
        private static final Duration SESSION_TTL = Duration.ofHours(8);

        private final InMemorySessionStore store = new InMemorySessionStore(16);
        private final SessionBinding binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));

        @Test
        @DisplayName("Should hand the engine's exchange through untouched when refresh is enabled")
        void shouldRetainRefreshTokenWhenEnabled() {
            String refreshToken = token();
            AuthorizationCodeFlow.AuthenticationResult exchanged = authenticationResult(refreshToken);

            AuthorizationCodeFlow.AuthenticationResult applied =
                    BffRuntimeProducer.applyRefreshPolicy(exchanged, true);

            assertSame(exchanged, applied, "an enabled refresh must not rebuild the engine's result");
            assertEquals(refreshToken, applied.refreshToken(),
                    "the refresh token is what CallbackEndpoint seeds into the session — without it "
                            + "TokenRefreshCoordinator.refresh returns on its first guard");
        }

        @Test
        @DisplayName("Should drop the refresh token at login when refresh is disabled, keeping both other tokens")
        void shouldDropRefreshTokenWhenDisabled() {
            AuthorizationCodeFlow.AuthenticationResult exchanged = authenticationResult(token());

            AuthorizationCodeFlow.AuthenticationResult applied =
                    BffRuntimeProducer.applyRefreshPolicy(exchanged, false);

            assertAll("a credential the gateway will never redeem never reaches the session binding",
                    () -> assertNull(applied.refreshToken(),
                            "the refresh token must be dropped here, not stored and ignored"),
                    () -> assertSame(exchanged.accessToken(), applied.accessToken(),
                            "the validated access token is carried over untouched"),
                    () -> assertSame(exchanged.idToken(), applied.idToken(),
                            "the validated ID token is carried over untouched"));
        }

        @Test
        @DisplayName("Should yield the session unchanged and no cookies on the disabled refresh seam")
        void shouldYieldSessionUnchangedWhenRefreshDisabled() {
            SessionRecord live = session(token());

            Optional<SessionBinding.BoundSession> bound =
                    BffRuntimeProducer.sessionUnchanged().refreshIfNeeded(live, null, NOW);

            assertTrue(bound.isPresent(),
                    "turning refresh off is a policy choice — it must not make a live session unauthenticated");
            assertAll("the gateway mediates the token it was issued until the absolute TTL expires",
                    () -> assertSame(live, bound.orElseThrow().session(),
                            "the resolved session is handed back verbatim"),
                    () -> assertTrue(bound.orElseThrow().setCookieHeaders().isEmpty(),
                            "an unwired seam re-binds nothing, so it emits no Set-Cookie"));
        }

        @Test
        @DisplayName("Should hand back the current session when the mediated token is not near expiry")
        void shouldYieldCurrentSessionWhenNotNearExpiry() {
            SessionRecord live = storedSession(token());
            AtomicInteger engineCalls = new AtomicInteger();

            Optional<SessionBinding.BoundSession> bound = BffRuntimeProducer
                    .nearExpiryRefresh(coordinator(NOW.plusSeconds(600), engineCalls))
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertTrue(bound.isPresent());
            assertSame(live, bound.orElseThrow().session());
            assertEquals(0, engineCalls.get(), "a token outside the leeway must not reach the engine");
        }

        /**
         * The null-refresh-token short circuit sits in {@code TokenRefreshCoordinator.refresh}
         * <em>before</em> the near-expiry check, so a session seeded without a refresh token never
         * refreshes however close to expiry its token is — and the seam reports that as an ordinary
         * authenticated request, which is precisely why the defect this plan was written for was
         * silent. Pinning it here keeps the two halves of the switch honest: whenever the exchange
         * seam drops the token, this is the behaviour the session is left with.
         */
        @Test
        @DisplayName("Should never reach the engine when the session carries no refresh token")
        void shouldShortCircuitWithoutRefreshToken() {
            SessionRecord live = storedSession(null);
            AtomicInteger engineCalls = new AtomicInteger();

            Optional<SessionBinding.BoundSession> bound = BffRuntimeProducer
                    .nearExpiryRefresh(coordinator(NOW, engineCalls))
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertTrue(bound.isPresent(), "a session with no refresh token is still authenticated");
            assertSame(live, bound.orElseThrow().session());
            assertEquals(0, engineCalls.get(),
                    "no refresh token means no refresh at all — silently, and regardless of expiry");
        }

        @Test
        @DisplayName("Should carry the rotated session through the seam when the engine refreshes")
        void shouldYieldRotatedSession() {
            SessionRecord live = storedSession(token());
            AtomicInteger engineCalls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NOW, engineCalls);

            Optional<SessionBinding.BoundSession> bound = BffRuntimeProducer.nearExpiryRefresh(coordinator)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertTrue(bound.isPresent());
            assertEquals(1, engineCalls.get(), "a token inside the leeway drives exactly one engine refresh");
            assertEquals(ROTATED_ACCESS_TOKEN, bound.orElseThrow().session().accessToken(),
                    "the stage must mediate from the rotated token, never the pre-refresh one");
        }

        @Test
        @DisplayName("Should signal unauthenticated when the refresh failed and the session was destroyed")
        void shouldYieldEmptyOnRefreshFailure() {
            SessionRecord live = storedSession(token());
            TokenRefreshCoordinator rejecting = new TokenRefreshCoordinator(LEEWAY, sessionRecord -> NOW,
                    refreshToken -> {
                        throw new ClientProtocolException("token endpoint rejected the refresh grant");
                    },
                    binding);

            Optional<SessionBinding.BoundSession> bound = BffRuntimeProducer.nearExpiryRefresh(rejecting)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertTrue(bound.isEmpty(),
                    "a FAILED outcome must reach the stage as empty so it re-drives the unauthenticated "
                            + "negotiation, rather than mediating the token of a session just revoked");
        }

        private TokenRefreshCoordinator coordinator(Instant accessTokenExpiry, AtomicInteger engineCalls) {
            return new TokenRefreshCoordinator(LEEWAY, sessionRecord -> accessTokenExpiry,
                    refreshToken -> {
                        engineCalls.incrementAndGet();
                        return rotation();
                    },
                    binding);
        }

        private SessionRecord storedSession(@Nullable String refreshToken) {
            SessionRecord live = session(refreshToken);
            store.create(live, NOW);
            return live;
        }

        private static String cookieHeader(SessionRecord live) {
            return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + live.sessionId();
        }

        private static SessionRecord session(@Nullable String refreshToken) {
            return SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .refreshToken(refreshToken)
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
        }

        private static AuthorizationCodeFlow.AuthenticationResult authenticationResult(String refreshToken) {
            Map<String, ClaimValue> accessClaims = new HashMap<>();
            accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            Map<String, ClaimValue> idClaims = new HashMap<>();
            idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            return new AuthorizationCodeFlow.AuthenticationResult(new AccessTokenContent(accessClaims, token()),
                    new IdTokenContent(idClaims, token()), refreshToken);
        }

        /**
         * The rotation the engine seam returns. {@code grantedScope} is {@code null} and
         * {@code scopeDelta} {@code UNDECLARED} — the "the IdP declared no scope on the refresh
         * response" pair — because nothing on the path under test reads either component; picking
         * {@code EQUAL} would assert a scope comparison this fixture never performs.
         */
        private static RotationResult rotation() {
            Map<String, ClaimValue> claims = new HashMap<>();
            claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            return new RotationResult(new AccessTokenContent(claims, ROTATED_ACCESS_TOKEN), token(), token(),
                    300L, true, null, RotationResult.ScopeDelta.UNDECLARED);
        }
    }

    /** Opaque token material — no production code under test parses it, so any non-blank value serves. */
    private static String token() {
        return Generators.letterStrings(16, 32).next();
    }

    @Nested
    @DisplayName("Inert bearer-only runtime")
    class Inert {

        @Test
        @DisplayName("Should stay inert when no oidc block is configured")
        void shouldBeInertWithoutOidc() {
            BffRuntime runtime = producer(null).bffRuntime();
            assertFalse(runtime.isActive());
        }

        @Test
        @DisplayName("Should stay inert for an unrecognised session mode")
        void shouldBeInertForUnrecognisedMode() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(ISSUER)
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("stateless").build())
                    .build();
            assertFalse(producer(oidc).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should stay inert when a mode is set but no redirect_uri is configured")
        void shouldBeInertWithoutRedirectUri() {
            OidcConfig serverNoRedirect = OidcConfig.builder()
                    .issuer(ISSUER)
                    .session(OidcConfig.Session.builder().mode("server").build())
                    .build();
            OidcConfig cookieNoRedirect = OidcConfig.builder()
                    .issuer(ISSUER)
                    .session(OidcConfig.Session.builder().mode("cookie").build())
                    .build();
            assertFalse(producer(serverNoRedirect).bffRuntime().isActive());
            assertFalse(producer(cookieNoRedirect).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should reject reserved dispatch and session-stage access on the inert runtime")
        void shouldRejectUseOfInert() {
            BffRuntime runtime = BffRuntime.inert();
            BffRuntime.ReservedHttpRequest request =
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET");
            Instant now = Instant.now();
            assertThrows(IllegalStateException.class, runtime::sessionStage);
            assertThrows(IllegalStateException.class,
                    () -> runtime.dispatch(ReservedEndpoint.USER_INFO, request, now));
        }
    }

    @Nested
    @DisplayName("Gateway-origin derivation (default-port normalization)")
    class OriginDerivation {

        @Test
        @DisplayName("Should drop the default https port 443 so the origin matches a browser Origin header")
        void shouldNormalizeHttpsDefaultPort() {
            assertEquals("https://gw.example.com",
                    BffRuntimeProducer.originOf("https://gw.example.com:443/auth/callback"));
        }

        @Test
        @DisplayName("Should drop the default http port 80 so the origin matches a browser Origin header")
        void shouldNormalizeHttpDefaultPort() {
            assertEquals("http://gw.example.com",
                    BffRuntimeProducer.originOf("http://gw.example.com:80/auth/callback"));
        }

        @Test
        @DisplayName("Should preserve a non-default explicit port and a portless URL")
        void shouldPreserveNonDefaultPort() {
            assertEquals("https://gw.example.com:8443",
                    BffRuntimeProducer.originOf("https://gw.example.com:8443/auth/callback"));
            assertEquals("https://gw.example.com",
                    BffRuntimeProducer.originOf("https://gw.example.com/auth/callback"));
            assertEquals("http://gw.example.com:8080",
                    BffRuntimeProducer.originOf("http://gw.example.com:8080/auth/callback"));
        }

        @Test
        @DisplayName("Should reject a redirect_uri that is not an absolute URI")
        void shouldRejectRelativeRedirectUri() {
            assertThrows(IllegalStateException.class, () -> BffRuntimeProducer.originOf("/auth/callback"));
        }
    }

    private BffRuntimeProducer producer(@Nullable OidcConfig oidc) {
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc).build();
        return new BffRuntimeProducer(gatewayConfig, new SingletonInstance<>(tokenValidator));
    }

    private static OidcConfig serverModeOidc() {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("server")
                .ttlSeconds(3600)
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .userInfo(OidcConfig.UserInfo.builder()
                        .path("/auth/userinfo")
                        .allowedClaims(List.of("sub", "name"))
                        .defaultView(List.of("sub"))
                        .build())
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    private static OidcConfig cookieModeOidc() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        return cookieModeOidcWithKey(Base64.getEncoder().encodeToString(key));
    }

    private static OidcConfig cookieModeOidcWithKey(String encryptionKey) {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("cookie")
                .ttlSeconds(3600)
                .encryptionKey(encryptionKey)
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .userInfo(OidcConfig.UserInfo.builder()
                        .path("/auth/userinfo")
                        .allowedClaims(List.of("sub", "name"))
                        .defaultView(List.of("sub"))
                        .build())
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied bean; the producer resolves
     * the validator only on the active path via {@link #get()}.
     */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
