/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Routing — matcher and protocol selection")
class RouteRuntimeTest {

    @Nested
    @DisplayName("RouteMatcher")
    class RouteMatcherTest {

        @Test
        @DisplayName("Should match a prefix only on segment boundaries")
        void shouldMatchPrefixOnlyOnSegmentBoundaries() {
            var matcher = RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").build());

            assertTrue(matcher.matchesPrefix("/api"), "Exact prefix matches");
            assertTrue(matcher.matchesPrefix("/api/users"), "Child path matches");
            assertFalse(matcher.matchesPrefix("/apiary"), "Non-boundary continuation does not match");
        }

        @Test
        @DisplayName("Should apply method, host, and header matchers with AND semantics")
        void shouldApplyAllMatchersWithAndSemantics() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .pathPrefix("/api")
                    .methods(List.of(HttpMethod.GET))
                    .host(Optional.of("gw.example"))
                    .headers(List.of(HeaderMatcher.builder().name("X-Tenant").present(Optional.of(true)).build()))
                    .build());
            Map<String, String> headers = Map.of("X-Tenant", "acme");

            assertTrue(matcher.matches("/api/x", HttpMethod.GET, "gw.example", headers), "All matchers hold");
            assertFalse(matcher.matches("/api/x", HttpMethod.POST, "gw.example", headers), "Wrong method fails");
            assertFalse(matcher.matches("/api/x", HttpMethod.GET, "other.example", headers), "Wrong host fails");
            assertFalse(matcher.matches("/api/x", HttpMethod.GET, "gw.example", Map.of()), "Missing header fails");
        }

        @Test
        @DisplayName("Should require an exact header value when configured")
        void shouldRequireExactHeaderValue() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .pathPrefix("/api")
                    .headers(List.of(HeaderMatcher.builder().name("X-Env").value(Optional.of("prod")).build()))
                    .build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of("X-Env", "prod")), "Exact value matches");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("X-Env", "dev")), "Wrong value fails");
        }
    }

    @Nested
    @DisplayName("ProtocolProcessorRegistry")
    class ProtocolProcessorRegistryTest {

        private final ProtocolProcessorRegistry registry = new ProtocolProcessorRegistry();

        @Test
        @DisplayName("Should reuse one HTTP processor for HTTP and GraphQL routes")
        void shouldReuseHttpProcessorForGraphql() {
            ProtocolProcessor http = registry.require(Protocol.HTTP, "route-http");
            ProtocolProcessor graphql = registry.require(Protocol.GRAPHQL, "route-graphql");

            assertSame(http, graphql, "GraphQL must reuse the shared HTTP processor instance");
            assertEquals("http", http.id(), "The shared processor is the HTTP processor");
        }

        @Test
        @DisplayName("Should serve gRPC and WebSocket with their dedicated processors")
        void shouldServeGrpcAndWebSocketProcessors() {
            // GRPC is now registered (its boot rejection was removed), so it resolves the dedicated
            // gRPC processor rather than failing boot.
            assertTrue(registry.supports(Protocol.GRPC), "gRPC is now supported");
            assertEquals("grpc", registry.require(Protocol.GRPC, "grpc-route").id(),
                    "a gRPC route resolves the dedicated gRPC processor");

            // WEBSOCKET is likewise registered and resolves the WebSocket processor.
            assertTrue(registry.supports(Protocol.WEBSOCKET), "WebSocket is now supported");
            assertEquals("websocket", registry.require(Protocol.WEBSOCKET, "ws-route").id(),
                    "a WebSocket route resolves the WebSocket processor");
        }
    }

    @Nested
    @DisplayName("RouteRuntime — resolved inbound-filter posture")
    class SecurityPosture {

        @Test
        @DisplayName("Should default an omitted securityProfile to the fail-closed STRICT")
        void shouldDefaultSecurityProfileToStrict() {
            // Arrange — the shape of the five builder call sites that construct a route for an
            // auth / verb-gate / route-selection / relay concern and declare no profile.
            RouteRuntime runtime = runtimeBuilder().build();

            // Assert — never null (which would NPE in the ThoroughChecksStage branch) and never
            // NONE (which would silently disable validation on a route nobody configured).
            assertEquals(SecurityProfile.STRICT, runtime.getSecurityProfile(),
                    "an omitted profile resolves to the most restrictive mode");
            assertTrue(runtime.getSecurityProfile().skippableValidationEnabled(),
                    "the default keeps the skippable validation half switched on");
        }

        @Test
        @DisplayName("Should carry an explicitly resolved profile unchanged")
        void shouldCarryExplicitProfile() {
            // Arrange + Act
            RouteRuntime lenient = runtimeBuilder().securityProfile(SecurityProfile.LENIENT).build();
            RouteRuntime none = runtimeBuilder().securityProfile(SecurityProfile.NONE).build();

            // Assert
            assertEquals(SecurityProfile.LENIENT, lenient.getSecurityProfile(),
                    "an explicitly resolved LENIENT overrides the builder default");
            assertEquals(SecurityProfile.NONE, none.getSecurityProfile(),
                    "an explicitly resolved NONE overrides the builder default");
            assertFalse(none.getSecurityProfile().skippableValidationEnabled(),
                    "the NONE route reports its skippable half as disabled");
        }

        private RouteRuntime.RouteRuntimeBuilder runtimeBuilder() {
            return RouteRuntime.builder()
                    .id("r")
                    .protocol(Protocol.HTTP)
                    .matcher(RouteMatcher.from(MatchConfig.builder().pathPrefix("/r").build()))
                    .effectiveAuth(AuthConfig.builder().require("none").build())
                    .effectiveAllowedMethods(Set.of(HttpMethod.GET));
        }
    }

    @Test
    @DisplayName("HttpProtocolProcessor should serve every standard proxyable verb")
    void httpProcessorShouldServeStandardVerbs() {
        var processor = new HttpProtocolProcessor();

        assertTrue(processor.supports(HttpMethod.GET), "GET is a standard verb");
        assertTrue(processor.supports(HttpMethod.DELETE), "DELETE is a standard verb");
        assertEquals(HttpMethod.values().length, processor.standardMethods().size(),
                "Every proxyable verb is standard");
    }
}
