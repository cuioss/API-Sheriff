/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.pipeline.PipelineRequest;
import de.cuioss.sheriff.api.routing.RouteRuntime;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("AuthenticationStage — stage 4 offline bearer-token validation")
class AuthenticationStageTest {

    private static final String ABSENT_SCOPE = "gateway:definitely-absent-scope-xyz";

    @Test
    @DisplayName("passes a require:none route without inspecting any token")
    void passesRequireNone() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = request(authConfig("none", List.of()), Map.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("passes a require:none route without ever resolving the lazy validator")
    void passesRequireNoneWithoutResolvingValidator() {
        // Arrange — a provider that fails when resolved proves that a require:none route never
        // triggers the (potentially config-absent) validator producer via Provider#get().
        AuthenticationStage stage = new AuthenticationStage(() -> {
            throw new AssertionError("require:none must not resolve the token validator");
        });
        PipelineRequest request = request(authConfig("none", List.of()), Map.of());

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("accepts a valid bearer token on a require:bearer route")
    void acceptsValidBearerToken() {
        // Arrange
        TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
        AuthenticationStage stage = stageFor(holder);
        PipelineRequest request = bearerRequest(holder.getRawToken(), authConfig("bearer", List.of()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    @Test
    @DisplayName("rejects a missing bearer token 401 with WWW-Authenticate")
    void rejectsMissingBearerToken() {
        // Arrange
        AuthenticationStage stage = stageFor(TestTokenGenerators.accessTokens().next());
        PipelineRequest request = request(authConfig("bearer", List.of()), Map.of());

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
        PipelineRequest request = bearerRequest("not.a.valid.jwt", authConfig("bearer", List.of()));

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
        PipelineRequest request = bearerRequest(holder.getRawToken(), authConfig("bearer", List.of(ABSENT_SCOPE)));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.SCOPE_MISSING, thrown.getEventType());
    }

    private static AuthenticationStage stageFor(TestTokenHolder holder) {
        TokenValidator validator = TokenValidator.builder().issuerConfig(holder.getIssuerConfig()).build();
        return new AuthenticationStage(() -> validator);
    }

    private static AuthConfig authConfig(String require, List<String> requiredScopes) {
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
