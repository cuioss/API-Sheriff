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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Cors;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Hsts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityHeadersStage — stage 0 response headers, CORS origin handling and stage 2a route headers")
class SecurityHeadersStageTest {

    private static final String ACAO = "Access-Control-Allow-Origin";
    private static final String HSTS = "Strict-Transport-Security";
    private static final String NOSNIFF = "X-Content-Type-Options";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String CSP = "Content-Security-Policy";
    private static final String GLOBAL_POLICY = "default-src 'self'; frame-ancestors 'none'";

    @Nested
    @DisplayName("stage 0 — content_security_policy is served verbatim from the global block")
    class ContentSecurityPolicy {

        @Test
        @DisplayName("emits the configured Content-Security-Policy value verbatim")
        void emitsConfiguredPolicyVerbatim() {
            SecurityHeadersStage stage = new SecurityHeadersStage(
                    SecurityHeadersConfig.builder().contentSecurityPolicy(GLOBAL_POLICY).build());
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            stage.process(request);

            assertEquals(GLOBAL_POLICY, request.responseHeaders().get(CSP),
                    "the policy is served byte-for-byte as configured");
        }

        @Test
        @DisplayName("emits no Content-Security-Policy header when the block omits the key")
        void emitsNoPolicyWhenOmitted() {
            SecurityHeadersStage stage =
                    new SecurityHeadersStage(SecurityHeadersConfig.builder().frameDeny(true).build());
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            stage.process(request);

            assertAll("an omitted policy emits no header while the enabled headers still apply",
                    () -> assertNull(request.responseHeaders().get(CSP)),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)));
        }
    }

    @Test
    @DisplayName("reflects any presented origin when a wildcard origin is configured")
    void wildcardReflectsAnyOrigin() {
        // Arrange — a configured "*" is a real wildcard (credentials disabled); the request Origin is
        // never literally "*", so the stage must accept and reflect an arbitrary presented origin.
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://any.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("emits CORS headers for an explicitly allow-listed origin")
    void exactOriginMatch() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://ok.example", request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("emits no CORS header for an origin that is neither listed nor wildcarded")
    void disallowedOriginEmitsNoCorsHeader() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://evil.example", false);

        // Act
        stage.process(request);

        // Assert
        assertNull(request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("short-circuits a wildcard-origin preflight with 204 and the reflected origin")
    void wildcardPreflightShortCircuits() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.OPTIONS, "https://any.example", true);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
        assertTrue(request.shortCircuitStatus().isPresent(), "a CORS preflight must short-circuit");
        assertEquals(204, request.shortCircuitStatus().orElseThrow().intValue());
    }

    @Test
    @DisplayName("short-circuits a preflight at stage 0 carrying the global security headers")
    void preflightShortCircuitsWithGlobalBlock() {
        Cors cors = Cors.builder().enabled(Boolean.TRUE).allowedOrigins(List.of("https://ok.example"))
                .allowedMethods(List.of("GET")).build();
        SecurityHeadersStage stage = new SecurityHeadersStage(SecurityHeadersConfig.builder()
                .hsts(new Hsts(31536000, true)).frameDeny(true).cors(cors).build());
        PipelineRequest request = corsRequest(HttpMethod.OPTIONS, "https://ok.example", true);

        stage.process(request);

        assertAll("the preflight is answered before route selection, with the global block",
                () -> assertEquals(204, request.shortCircuitStatus().orElseThrow().intValue()),
                () -> assertEquals("max-age=31536000; includeSubDomains",
                        request.responseHeaders().get(HSTS)),
                () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)),
                () -> assertEquals("GET", request.responseHeaders().get("Access-Control-Allow-Methods")));
    }

    @Nested
    @DisplayName("stage 2a — applyRouteHeaders replaces the global block with the route's block")
    class RouteHeaders {

        private final SecurityHeadersConfig global = SecurityHeadersConfig.builder()
                .hsts(new Hsts(31536000, true))
                .contentTypeNosniff(true)
                .frameDeny(true)
                .contentSecurityPolicy(GLOBAL_POLICY)
                .cors(Cors.builder().enabled(Boolean.TRUE).allowedOrigins(List.of("https://ok.example")).build())
                .build();

        @Test
        @DisplayName("drops the global Content-Security-Policy when the anchor block declares none")
        void anchorBlockWithoutPolicyDropsGlobalPolicy() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            assertEquals(GLOBAL_POLICY, request.responseHeaders().get(CSP),
                    "precondition: stage 0 seeded the global policy");

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build());

            assertAll("the anchor block replaces the global block wholesale, policy included",
                    () -> assertNull(request.responseHeaders().get(CSP),
                            "the global policy does not survive an anchor block that omits it"),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)));
        }

        @Test
        @DisplayName("replaces the global Content-Security-Policy with the anchor block's own policy")
        void anchorPolicyReplacesGlobalPolicy() {
            String anchorPolicy = "default-src 'none'; img-src 'self'";
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);

            stage.applyRouteHeaders(request,
                    SecurityHeadersConfig.builder().contentSecurityPolicy(anchorPolicy).build());

            assertAll("exactly one policy, the anchor's, is on the response",
                    () -> assertEquals(anchorPolicy, request.responseHeaders().get(CSP)),
                    () -> assertEquals(1, request.responseHeaders().keySet().stream()
                            .filter(CSP::equalsIgnoreCase).count(),
                            "the global policy is removed, never merged alongside the anchor's"));
        }

        @Test
        @DisplayName("replaces the security names wholesale — an anchor block with only frame_deny drops HSTS and nosniff")
        void replacesSecurityNamesWholesale() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            request.responseHeaders().put("Allow", "GET");

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build());

            assertAll("only the gateway-owned security names are replaced",
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS),
                            "the route block's frame_deny is seeded"),
                    () -> assertNull(request.responseHeaders().get(HSTS),
                            "the global HSTS is dropped — no key-by-key merge"),
                    () -> assertNull(request.responseHeaders().get(NOSNIFF),
                            "the global nosniff is dropped — no key-by-key merge"),
                    () -> assertEquals("https://ok.example", request.responseHeaders().get(ACAO),
                            "the global CORS header is untouched"),
                    () -> assertEquals("GET", request.responseHeaders().get("Allow"),
                            "a non-security entry is untouched"));
        }

        @Test
        @DisplayName("seeds every header the route block enables")
        void seedsEveryEnabledRouteHeader() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage =
                    new SecurityHeadersStage(SecurityHeadersConfig.builder().frameDeny(true).build());
            stage.process(request);

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder()
                    .hsts(new Hsts(600, false)).contentTypeNosniff(true).build());

            assertAll("the route block governs the response from stage 2a on",
                    () -> assertEquals("max-age=600", request.responseHeaders().get(HSTS)),
                    () -> assertEquals("nosniff", request.responseHeaders().get(NOSNIFF)),
                    () -> assertNull(request.responseHeaders().get(FRAME_OPTIONS),
                            "the global frame_deny does not survive a route block that omits it"));
        }

        @Test
        @DisplayName("removes every gateway-owned name when the route resolves no block at all")
        void removesSecurityNamesWhenRouteHasNoBlock() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);

            stage.applyRouteHeaders(request, null);

            assertAll("a null route block leaves no gateway-owned security header behind",
                    () -> assertNull(request.responseHeaders().get(HSTS)),
                    () -> assertNull(request.responseHeaders().get(NOSNIFF)),
                    () -> assertNull(request.responseHeaders().get(FRAME_OPTIONS)),
                    () -> assertNull(request.responseHeaders().get(CSP)),
                    () -> assertEquals("https://ok.example", request.responseHeaders().get(ACAO)));
        }

        @Test
        @DisplayName("re-seeds an identical block unchanged when the route resolves the global block")
        void keepsGlobalValuesWhenRouteResolvesGlobalBlock() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            Map<String, String> afterStageZero = Map.copyOf(request.responseHeaders());

            stage.applyRouteHeaders(request, global);

            assertEquals(afterStageZero, Map.copyOf(request.responseHeaders()),
                    "an unanchored route resolves the global block, so stage 2a changes nothing");
        }

        @Test
        @DisplayName("leaves the short-circuit state untouched")
        void leavesShortCircuitUntouched() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            new SecurityHeadersStage(global).applyRouteHeaders(request, global);

            assertTrue(request.shortCircuitStatus().isEmpty(), "stage 2a never short-circuits a request");
        }
    }

    private static SecurityHeadersStage corsStage(List<String> allowedOrigins, boolean allowCredentials) {
        Cors cors = Cors.builder()
                .enabled(Boolean.TRUE)
                .allowedOrigins(allowedOrigins)
                .allowedMethods(List.of("GET", "POST"))
                .allowCredentials(allowCredentials)
                .build();
        return new SecurityHeadersStage(
                SecurityHeadersConfig.builder().cors(cors).build());
    }

    private static PipelineRequest corsRequest(HttpMethod method, String origin, boolean preflight) {
        Map<String, List<String>> headers = preflight
                ? Map.of("origin", List.of(origin), "access-control-request-method", List.of("GET"))
                : Map.of("origin", List.of(origin));
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/api")
                .queryParameters(Map.of())
                .headers(headers)
                .build();
    }
}
