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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;


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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("AuthenticationStage — stage 4 auth dispatch (offline bearer validation and session dispatch)")
class AuthenticationStageTest {

    private static final String ABSENT_SCOPE = "gateway:definitely-absent-scope-xyz";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SESSION_ID = "opaque-session-id";
    private static final String MEDIATED_TOKEN = "mediated-access-token";

    @Test
    @DisplayName("passes a require:none route without ever resolving the lazy validator")
    void passesRequireNoneWithoutResolvingValidator() {
        // Arrange — a provider that fails when resolved proves that a require:none route never
        // triggers the (potentially config-absent) validator producer via Provider#get().
        AuthenticationStage stage = new AuthenticationStage(() -> {
            throw new AssertionError("require:none must not resolve the token validator");
        });
        PipelineRequest request = request(authConfig(Require.NONE, List.of()), Map.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("accepts a valid bearer token on a require:bearer route")
    void acceptsValidBearerToken() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), authConfig(Require.BEARER, List.of()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("rejects a missing bearer token 401 with WWW-Authenticate")
    void rejectsMissingBearerToken() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = request(authConfig(Require.BEARER, List.of()), Map.of());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.TOKEN_MISSING, thrown.getEventType());
        assertEquals("Bearer", request.responseHeaders().get("WWW-Authenticate"));
    }

    @Test
    @DisplayName("rejects a malformed bearer token 401 with WWW-Authenticate")
    void rejectsInvalidBearerToken() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = bearerRequest("not.a.valid.jwt", authConfig(Require.BEARER, List.of()));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.TOKEN_INVALID, thrown.getEventType());
        assertEquals("Bearer", request.responseHeaders().get("WWW-Authenticate"));
    }

    @Test
    @DisplayName("rejects a valid token lacking a required scope 403")
    void rejectsMissingScope() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), authConfig(Require.BEARER, List.of(ABSENT_SCOPE)));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.SCOPE_MISSING, thrown.getEventType());
    }

    @Test
    @DisplayName("dispatches a require:session route to the wired session stage-4 runtime")
    void dispatchesSessionRouteToWiredSessionStage() {
        // Arrange — a session stage wired with a live session; a require:session request carrying the
        // session cookie must be dispatched here and complete, recording the mediated bearer.
        AuthenticationStage stage = new AuthenticationStage(failingValidatorProvider(), sessionStage());
        PipelineRequest request = sessionRequest(authConfig(Require.SESSION, List.of()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
        assertTrue(request.mediatedBearer().isPresent(),
                "dispatch reached the session stage, which mediated the session's bearer");
        assertEquals(MEDIATED_TOKEN, request.mediatedBearer().orElseThrow());
    }

    @Test
    @DisplayName("rejects a require:session route when no session runtime is wired")
    void rejectsSessionRouteWithoutWiredSessionRuntime() {
        // Arrange — a stage built without a session runtime (non-BFF gateway).
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = sessionRequest(authConfig(Require.SESSION, List.of()));

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> stage.process(request));

        // Assert
        assertTrue(thrown.getMessage().contains("no session runtime is wired"),
                "the unwired session route is a boot-configuration error, not a served request");
    }

    private static AuthenticationStage stageFor(TestTokenHolder holder) {
        TokenValidator validator = TokenValidator.builder().issuerConfig(holder.getIssuerConfig()).build();
        return new AuthenticationStage(() -> validator);
    }

    private static Provider<TokenValidator> failingValidatorProvider() {
        return () -> {
            throw new AssertionError("a require:session route must not resolve the bearer validator");
        };
    }

    private static SessionAuthenticationStage sessionStage() {
        InMemorySessionStore store = new InMemorySessionStore(16);
        store.create(SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(MEDIATED_TOKEN)
                .idToken("id-token")
                .sub("subject")
                .expiresAt(NOW.plusSeconds(3600))
                .build(), NOW);
        SessionCookieCodec codec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1));
        return new SessionAuthenticationStage(new ServerSessionBinding(store, codec),
                (session, cookieHeader, now) -> Optional.of(new SessionBinding.BoundSession(session, List.of())),
                (accessToken, requiredScopes) -> true,
                (returnUrl, now) -> new LoginChallenge("https://idp.example/authorize", List.of()),
                CLOCK);
    }

    private static PipelineRequest sessionRequest(AuthConfig auth) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/app/orders")
                .queryParameters(Map.of())
                .headers(Map.of("cookie", List.of(SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + SESSION_ID),
                        "accept", List.of("application/json")))
                .build();
        request.canonicalPath("/app/orders");
        request.selectedRoute(RouteRuntime.builder().id("orders").effectiveAuth(auth).build());
        return request;
    }

    private static AuthConfig authConfig(Require require, List<String> requiredScopes) {
        return AuthConfig.builder().require(require).requiredScopes(requiredScopes).build();
    }

    private static PipelineRequest bearerRequest(String token, AuthConfig auth) {
        return request(auth, Map.of("authorization", List.of("Bearer " + token)));
    }

    private static PipelineRequest request(AuthConfig auth, Map<String, List<String>> headers) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(Map.of())
                .headers(headers)
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(RouteRuntime.builder().id("orders").effectiveAuth(auth).build());
        return request;
    }
}
