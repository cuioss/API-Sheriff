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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.LoginChallenge;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnableGeneratorController
@DisplayName("AuthenticationStage — stage 4 auth dispatch (offline bearer validation and session dispatch)")
class AuthenticationStageTest {

    /** An endpoint-level scope no generated token ever carries. */
    private static final String ABSENT_ENDPOINT_SCOPE = "gateway:definitely-absent-scope-xyz";
    /** A second absent scope, standing in for an {@code oidc.scopes} member the token lacks. */
    private static final String ABSENT_OIDC_SCOPE = "absent-oidc-scope";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SESSION_ID = "internal-session-id";
    /** The opaque value the session cookie carries, which the store resolves to the session. */
    private static final String COOKIE_HANDLE = "opaque-cookie-handle";
    private static final String MEDIATED_TOKEN = "mediated-access-token";
    /** The scope a session route needs in the session-branch tests. */
    private static final String SESSION_SCOPE = "orders:read";
    /** The configured {@code oidc.step_up.path} the session stage names on a 403. */
    private static final String STEP_UP_PATH = "/auth/step-up";

    @Test
    @DisplayName("passes a require:none route without ever resolving the lazy validator")
    void passesRequireNoneWithoutResolvingValidator() {
        // Arrange — a provider that fails when resolved proves that a require:none route never
        // triggers the (potentially config-absent) validator producer via Provider#get().
        AuthenticationStage stage = new AuthenticationStage(() -> {
            throw new AssertionError("require:none must not resolve the token validator");
        });
        PipelineRequest request = request(Require.NONE, Set.of(ABSENT_ENDPOINT_SCOPE), Map.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("accepts a valid bearer token on a require:bearer route needing no scope")
    void acceptsValidBearerTokenWithEmptyNeededScopes() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), Set.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
        assertNull(request.responseHeaders().get(WWW_AUTHENTICATE), "an accepted token carries no challenge");
    }

    @Test
    @DisplayName("accepts a valid bearer token that covers every needed scope")
    void acceptsTokenCoveringNeededScopes() {
        // Arrange — the needed set is exactly the token's own granted scopes
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        Set<String> granted = new LinkedHashSet<>(holder.asAccessTokenContent().getScopes());
        assertFalse(granted.isEmpty(), "precondition: the generated token grants at least one scope");
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), granted);

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
        assertNull(request.responseHeaders().get(WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("rejects a missing bearer token 401 with WWW-Authenticate")
    void rejectsMissingBearerToken() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = request(Require.BEARER, Set.of(), Map.of());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.TOKEN_MISSING, thrown.getEventType());
        assertEquals("Bearer", request.responseHeaders().get(WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("rejects a malformed bearer token 401 with WWW-Authenticate")
    void rejectsInvalidBearerToken() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = bearerRequest("not.a.valid.jwt", Set.of());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.TOKEN_INVALID, thrown.getEventType());
        assertEquals("Bearer", request.responseHeaders().get(WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("rejects a token lacking an endpoint scope 403 insufficient_scope naming only the missing scope")
    void rejectsMissingEndpointScopeWithInsufficientScopeChallenge() {
        // Arrange — the token covers everything it grants but not the endpoint-added scope
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        Set<String> needed = new LinkedHashSet<>(holder.asAccessTokenContent().getScopes());
        needed.add(ABSENT_ENDPOINT_SCOPE);
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), needed);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.SCOPE_MISSING, thrown.getEventType());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"" + ABSENT_ENDPOINT_SCOPE + "\"",
                request.responseHeaders().get(WWW_AUTHENTICATE),
                "the challenge names only the missing scope, never a granted one");
    }

    @Test
    @DisplayName("rejects a token lacking an oidc.scopes member 403 — the documented BFF-mode consequence")
    void rejectsMissingOidcScopeMember() {
        // Arrange — needed = oidc.scopes (one member the token lacks) united with an empty endpoint set
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), Set.of(ABSENT_OIDC_SCOPE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.SCOPE_MISSING, thrown.getEventType());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"" + ABSENT_OIDC_SCOPE + "\"",
                request.responseHeaders().get(WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("lists several missing scopes space-separated and sorted in the challenge")
    void listsSeveralMissingScopesSorted() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(),
                Set.of(ABSENT_OIDC_SCOPE, ABSENT_ENDPOINT_SCOPE));

        // Act
        assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert — "absent-oidc-scope" sorts before "gateway:…"
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"" + ABSENT_OIDC_SCOPE + " "
                + ABSENT_ENDPOINT_SCOPE + "\"",
                request.responseHeaders().get(WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("dispatches a require:session route to the wired session stage-4 runtime")
    void dispatchesSessionRouteToWiredSessionStage() {
        // Arrange — a session stage wired with a live session; a require:session request carrying the
        // session cookie must be dispatched here and complete, recording the mediated bearer.
        AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(), sessionStage());
        PipelineRequest request = sessionRequest(Set.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
        assertTrue(request.mediatedBearer().isPresent(),
                "dispatch reached the session stage, which mediated the session's bearer");
        assertEquals(MEDIATED_TOKEN, request.mediatedBearer().orElseThrow());
    }

    @Test
    @DisplayName("relays a require:session request whose session covers the route's needed scopes")
    void relaysSessionCoveringNeededScopes() {
        // Arrange — the session's active scope set holds the one scope the route needs
        AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(),
                sessionStage(Set.of(SESSION_SCOPE)));
        PipelineRequest request = sessionRequest(Set.of(SESSION_SCOPE));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
        assertEquals(MEDIATED_TOKEN, request.mediatedBearer().orElseThrow(),
                "a session covering the needed scopes is relayed");
    }

    @Test
    @DisplayName("refuses a require:session API call whose session lacks a needed scope 403 with problem members")
    void refusesSessionRouteMissingScope() {
        // Arrange — the session carries no scope, so the needed one is neither active nor granted
        AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(), sessionStage());
        PipelineRequest request = sessionRequest(Set.of(SESSION_SCOPE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertAll("the session route enforces its needed scopes",
                () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType()),
                () -> assertEquals(List.of(SESSION_SCOPE),
                        thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                        "the missing scope is named in the problem body"),
                () -> assertEquals(STEP_UP_PATH + "?returnUrl=%2Fapp%2Forders",
                        thrown.getProblemExtensions().get(SessionAuthenticationStage.STEP_UP_URL_MEMBER),
                        "the step-up URL returns to the refused path"),
                () -> assertNull(request.responseHeaders().get(WWW_AUTHENTICATE),
                        "a session refusal carries no bearer challenge"),
                () -> assertTrue(request.mediatedBearer().isEmpty(),
                        "an under-scoped session is never relayed"));
    }

    @Test
    @DisplayName("rejects a require:session route when no session runtime is wired")
    void rejectsSessionRouteWithoutWiredSessionRuntime() {
        // Arrange — a stage built without a session runtime (non-BFF gateway).
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = sessionRequest(Set.of());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> stage.process(request));

        // Assert
        assertTrue(thrown.getMessage().contains("no session runtime is wired"),
                "the unwired session route is a boot-configuration error, not a served request");
    }

    @Test
    @DisplayName("records no mediated bearer for a valid token on a plain require:bearer route")
    void plainBearerRouteRecordsNoMediatedBearer() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), Set.of());

        // Act
        stage.process(request);

        // Assert
        assertTrue(request.mediatedBearer().isEmpty(),
                "only the bearer branch of a session_fallback route forwards the client token");
    }

    @Nested
    @DisplayName("require:bearer with session_fallback — dispatch by Authorization presence")
    class SessionFallbackRoute {

        @ParameterizedTest(name = "token_relay={0}")
        @NullSource
        @ValueSource(booleans = {true, false})
        @DisplayName("a valid bearer is recorded as the mediated bearer, independent of token_relay")
        void validBearerIsRecordedAsMediatedBearer(@Nullable Boolean tokenRelay) {
            // Arrange
            TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
            AuthenticationStage stage = stageFor(holder, sessionStage());
            PipelineRequest request = fallbackRequest(tokenRelay, Set.of(),
                    withSessionCookie("Bearer " + holder.getRawToken()));

            // Act
            stage.process(request);

            // Assert
            assertAll("bearer branch forwards the validated client token",
                    () -> assertEquals(holder.getRawToken(), request.mediatedBearer().orElseThrow(),
                            "the validated client token, never the session's token, is mediated"),
                    () -> assertNull(request.responseHeaders().get(WWW_AUTHENTICATE)));
        }

        @Test
        @DisplayName("a valid bearer lacking a needed scope still answers 403 insufficient_scope")
        void missingScopeBearerAnswersInsufficientScope() {
            // Arrange
            TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
            AuthenticationStage stage = stageFor(holder, sessionStage());
            PipelineRequest request = fallbackRequest(null, Set.of(ABSENT_ENDPOINT_SCOPE),
                    withSessionCookie("Bearer " + holder.getRawToken()));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            // Assert
            assertAll("scope rejection on the bearer branch",
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType()),
                    () -> assertEquals("Bearer error=\"insufficient_scope\", scope=\"" + ABSENT_ENDPOINT_SCOPE
                            + "\"", request.responseHeaders().get(WWW_AUTHENTICATE)),
                    () -> assertTrue(request.mediatedBearer().isEmpty(),
                            "a rejected token is never mediated and the session is never consulted"));
        }

        static Stream<Arguments> rejectedAuthorizationRows() {
            return Stream.of(
                    Arguments.of("a Basic header", "Basic dXNlcjpwYXNzd29yZA==", EventType.TOKEN_MISSING),
                    Arguments.of("an empty-value header", "", EventType.TOKEN_MISSING),
                    Arguments.of("an invalid bearer token", "Bearer not.a.valid.jwt", EventType.TOKEN_INVALID));
        }

        @ParameterizedTest(name = "{0} -> 401 {2}")
        @MethodSource("rejectedAuthorizationRows")
        @DisplayName("a non-validating Authorization answers 401 and never falls through to the session")
        void rejectedAuthorizationNeverReachesSession(String description, String authorization,
                EventType expectedEvent) {
            // Arrange — the request also carries a live session cookie: were it dispatched to the
            // session stage, that stage would accept it and mediate the session's token.
            AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next(), sessionStage());
            PipelineRequest request = fallbackRequest(null, Set.of(), withSessionCookie(authorization));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request),
                    description);

            // Assert
            assertAll(description,
                    () -> assertEquals(expectedEvent, thrown.getEventType()),
                    () -> assertEquals("Bearer", request.responseHeaders().get(WWW_AUTHENTICATE)),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "the session stage was never reached"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "no short-circuit is set"));
        }

        @Test
        @DisplayName("a request without Authorization is dispatched to the session stage and relayed when its session covers the scopes")
        void noAuthorizationIsDispatchedToSessionStage() {
            // Arrange — a failing validator proves the bearer branch is never entered
            AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(),
                    sessionStage(Set.of(SESSION_SCOPE)));
            PipelineRequest request = fallbackRequest(null, Set.of(SESSION_SCOPE), sessionCookieOnly());

            // Act
            stage.process(request);

            // Assert
            assertEquals(MEDIATED_TOKEN, request.mediatedBearer().orElseThrow(),
                    "the session stage mediated the bearer of a session covering the needed scopes");
        }

        @Test
        @DisplayName("a request without Authorization whose session lacks a needed scope answers 403 with problem members")
        void sessionBranchMissingScopeAnswersProblemMembers() {
            // Arrange — the same route, reached without Authorization by a session carrying no scope
            AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(), sessionStage());
            PipelineRequest request = fallbackRequest(null, Set.of(SESSION_SCOPE), sessionCookieOnly());

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            // Assert
            assertAll("the session branch of a session_fallback route gets the session-route outcome",
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType()),
                    () -> assertEquals(List.of(SESSION_SCOPE),
                            thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                            "the missing scope is named in the problem body"),
                    () -> assertEquals(STEP_UP_PATH + "?returnUrl=%2Fapi%2Forders",
                            thrown.getProblemExtensions().get(SessionAuthenticationStage.STEP_UP_URL_MEMBER),
                            "the step-up URL returns to the refused path"),
                    () -> assertNull(request.responseHeaders().get(WWW_AUTHENTICATE),
                            "the session branch answers without the bearer branch's insufficient_scope challenge"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(),
                            "an under-scoped session is never relayed"));
        }

        @Test
        @DisplayName("the session branch without a wired session runtime is a boot-configuration error")
        void sessionBranchWithoutWiredRuntimeIsRejected() {
            // Arrange
            AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
            PipelineRequest request = fallbackRequest(null, Set.of(), Map.of());

            // Act
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> stage.process(request));

            // Assert
            assertTrue(thrown.getMessage().contains("no session runtime is wired"));
        }

        private static Map<String, List<String>> sessionCookieOnly() {
            return Map.of("cookie", List.of(SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + COOKIE_HANDLE),
                    "accept", List.of("application/json"));
        }

        private static Map<String, List<String>> withSessionCookie(String authorization) {
            return Map.of("authorization", List.of(authorization),
                    "cookie", List.of(SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + COOKIE_HANDLE),
                    "accept", List.of("application/json"));
        }

        private static PipelineRequest fallbackRequest(@Nullable Boolean tokenRelay, Set<String> neededScopes,
                Map<String, List<String>> headers) {
            return request(AuthConfig.builder().require(Require.BEARER).tokenRelay(tokenRelay)
                    .sessionFallback(Boolean.TRUE).build(), neededScopes, headers);
        }
    }

    private static AuthenticationStage stageFor(TestTokenHolder holder) {
        TokenValidator validator = TokenValidator.builder().issuerConfig(holder.getIssuerConfig()).build();
        return new AuthenticationStage(() -> validator);
    }

    private static AuthenticationStage stageFor(TestTokenHolder holder, SessionAuthenticationStage sessionStage) {
        TokenValidator validator = TokenValidator.builder().issuerConfig(holder.getIssuerConfig()).build();
        return new AuthenticationStage(() -> validator, sessionStage);
    }

    private static Provider<TokenValidator> failingValidatorProvider() {
        return () -> {
            throw new AssertionError("a require:session route must not resolve the bearer validator");
        };
    }

    /** A session stage over a live session that carries no scope at all. */
    private static SessionAuthenticationStage sessionStage() {
        return sessionStage(Set.of());
    }

    /**
     * A session stage over a live session whose active and granted scope sets are both
     * {@code sessionScopes}. The granted set never exceeds the active one, so a missing scope is never
     * refreshable: the scope-refresh seam hands the session back unchanged and is reached by no test.
     */
    private static SessionAuthenticationStage sessionStage(Set<String> sessionScopes) {
        // The idle timeout equals the session's lifetime, so it is not in play here.
        InMemorySessionStore store = new InMemorySessionStore(16, Duration.ofHours(1), Integer.MAX_VALUE,
                sessionId -> { });
        store.create(SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(MEDIATED_TOKEN)
                .idToken("id-token")
                .sub("subject")
                .expiresAt(NOW.plusSeconds(3600))
                .activeScopes(sessionScopes)
                .grantedScopes(sessionScopes)
                .build(), COOKIE_HANDLE, NOW);
        SessionCookieCodec codec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1));
        return new SessionAuthenticationStage(new ServerSessionBinding(store, codec),
                (session, cookieHeader, now) -> SessionAuthenticationStage.RefreshResult.mediate(
                        new SessionBinding.BoundSession(session, List.of())),
                (session, cookieHeader, requestedScopes, now) -> SessionAuthenticationStage.RefreshResult.mediate(
                        new SessionBinding.BoundSession(session, List.of())),
                (returnUrl, scopes, now) -> new LoginChallenge("https://idp.example/authorize", List.of()),
                (live, returnUrl, neededScopes, now) -> new LoginChallenge("https://idp.example/widen", List.of()),
                SessionAuthenticationStage.OnFailure.REAUTHENTICATE,
                STEP_UP_PATH,
                CLOCK);
    }

    private static PipelineRequest sessionRequest(Set<String> neededScopes) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/app/orders")
                .queryParameters(List.of())
                .headers(Map.of("cookie", List.of(SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + COOKIE_HANDLE),
                        "accept", List.of("application/json")))
                .build();
        request.canonicalPath("/app/orders");
        request.selectedRoute(route(Require.SESSION, neededScopes));
        return request;
    }

    private static RouteRuntime route(Require require, Set<String> neededScopes) {
        return route(AuthConfig.builder().require(require).build(), neededScopes);
    }

    private static RouteRuntime route(AuthConfig auth, Set<String> neededScopes) {
        return RouteRuntime.builder().id("orders")
                .effectiveAuth(auth)
                .neededScopes(neededScopes)
                .build();
    }

    private static PipelineRequest bearerRequest(String token, Set<String> neededScopes) {
        return request(Require.BEARER, neededScopes, Map.of("authorization", List.of("Bearer " + token)));
    }

    private static PipelineRequest request(Require require, Set<String> neededScopes,
            Map<String, List<String>> headers) {
        return request(AuthConfig.builder().require(require).build(), neededScopes, headers);
    }

    private static PipelineRequest request(AuthConfig auth, Set<String> neededScopes,
            Map<String, List<String>> headers) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(List.of())
                .headers(headers)
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(route(auth, neededScopes));
        return request;
    }
}
