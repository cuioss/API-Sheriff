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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@EnableGeneratorController
@DisplayName("AuthBranch — per-request authentication branch resolution")
class AuthBranchTest {

    /** No {@code Authorization} header at all. */
    private static final Map<String, List<String>> NO_HEADER = Map.of();
    private static final Map<String, List<String>> BEARER_HEADER =
            Map.of("Authorization", List.of("Bearer some.opaque.token"));
    private static final Map<String, List<String>> BASIC_HEADER =
            Map.of("Authorization", List.of("Basic dXNlcjpwYXNzd29yZA=="));
    private static final Map<String, List<String>> EMPTY_VALUE_HEADER =
            Map.of("Authorization", List.of(""));
    private static final Map<String, List<String>> LOWER_CASE_NAME_HEADER =
            Map.of("authorization", List.of("Bearer some.opaque.token"));

    @Nested
    @DisplayName("routes without session_fallback resolve to their declared posture")
    class PlainPostures {

        static Stream<Arguments> plainPostureRows() {
            return Stream.of(
                    Arguments.of(Require.NONE, null, NO_HEADER, AuthBranch.NONE),
                    Arguments.of(Require.NONE, null, BEARER_HEADER, AuthBranch.NONE),
                    Arguments.of(Require.BEARER, null, NO_HEADER, AuthBranch.BEARER),
                    Arguments.of(Require.BEARER, null, BEARER_HEADER, AuthBranch.BEARER),
                    Arguments.of(Require.BEARER, Boolean.FALSE, NO_HEADER, AuthBranch.BEARER),
                    Arguments.of(Require.BEARER, Boolean.FALSE, BEARER_HEADER, AuthBranch.BEARER),
                    Arguments.of(Require.SESSION, null, NO_HEADER, AuthBranch.SESSION),
                    Arguments.of(Require.SESSION, null, BEARER_HEADER, AuthBranch.SESSION));
        }

        @ParameterizedTest(name = "require={0}, session_fallback={1}, headers={2} -> {3}")
        @MethodSource("plainPostureRows")
        @DisplayName("header presence never moves a route off its single declared branch")
        void resolvesToDeclaredPosture(Require require, @Nullable Boolean sessionFallback,
                Map<String, List<String>> headers, AuthBranch expected) {
            AuthConfig auth = auth(require, sessionFallback);
            PipelineRequest request = request(headers);

            AuthBranch branch = AuthBranch.resolve(auth, request);

            assertEquals(expected, branch);
        }
    }

    @Nested
    @DisplayName("a require:bearer route with session_fallback picks the branch by Authorization presence")
    class SessionFallback {

        static Stream<Arguments> sessionFallbackRows() {
            return Stream.of(
                    Arguments.of("a Bearer header", BEARER_HEADER, AuthBranch.BEARER),
                    Arguments.of("a Basic header", BASIC_HEADER, AuthBranch.BEARER),
                    Arguments.of("an empty-value header", EMPTY_VALUE_HEADER, AuthBranch.BEARER),
                    Arguments.of("a lower-case authorization name", LOWER_CASE_NAME_HEADER, AuthBranch.BEARER),
                    Arguments.of("no Authorization header", NO_HEADER, AuthBranch.SESSION));
        }

        @ParameterizedTest(name = "{0} -> {2}")
        @MethodSource("sessionFallbackRows")
        @DisplayName("Authorization present -> BEARER (any scheme or value), absent -> SESSION")
        void resolvesByAuthorizationPresence(String description, Map<String, List<String>> headers,
                AuthBranch expected) {
            AuthConfig auth = auth(Require.BEARER, Boolean.TRUE);
            PipelineRequest request = request(headers);

            AuthBranch branch = AuthBranch.resolve(auth, request);

            assertEquals(expected, branch, description);
        }

        @Test
        @DisplayName("an unrelated header never counts as Authorization")
        void unrelatedHeaderSelectsSession() {
            AuthConfig auth = auth(Require.BEARER, Boolean.TRUE);
            PipelineRequest request = request(Map.of("proxy-authorization", List.of("Bearer some.opaque.token")));

            AuthBranch branch = AuthBranch.resolve(auth, request);

            assertEquals(AuthBranch.SESSION, branch,
                    "only the Authorization header selects the bearer branch");
        }
    }

    @Test
    @DisplayName("each branch carries its bounded lower-case metric label")
    void labelsAreBoundedLowerCase() {
        assertAll("branch labels",
                () -> assertEquals("none", AuthBranch.NONE.label()),
                () -> assertEquals("bearer", AuthBranch.BEARER.label()),
                () -> assertEquals("session", AuthBranch.SESSION.label()));
    }

    @Test
    @DisplayName("rejects a null auth block or a null request")
    void rejectsNullArguments() {
        AuthConfig auth = auth(Require.BEARER, Boolean.TRUE);
        PipelineRequest request = request(NO_HEADER);

        assertAll("null arguments",
                () -> assertThrows(NullPointerException.class, () -> AuthBranch.resolve(null, request)),
                () -> assertThrows(NullPointerException.class, () -> AuthBranch.resolve(auth, null)));
    }

    private static AuthConfig auth(Require require, @Nullable Boolean sessionFallback) {
        return AuthConfig.builder().require(require).sessionFallback(sessionFallback).build();
    }

    private static PipelineRequest request(Map<String, List<String>> headers) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(List.of())
                .headers(headers)
                .build();
    }
}
