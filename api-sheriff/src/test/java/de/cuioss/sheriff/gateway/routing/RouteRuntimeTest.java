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
package de.cuioss.sheriff.gateway.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

@EnableGeneratorController
@DisplayName("Routing — matcher and protocol selection")
class RouteRuntimeTest {

    @Nested
    @DisplayName("RouteMatcher")
    class RouteMatcherTest {

        @Test
        @DisplayName("Should match a prefix only on segment boundaries")
        void shouldMatchPrefixOnlyOnSegmentBoundaries() {
            var matcher = RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").build());

            assertTrue(matcher.matchesPath("/api"), "Exact prefix matches");
            assertTrue(matcher.matchesPath("/api/users"), "Child path matches");
            assertFalse(matcher.matchesPath("/apiary"), "Non-boundary continuation does not match");
            assertFalse(matcher.isExact(), "A path_prefix matcher is not an exact matcher");
            assertEquals("/api", matcher.matchKey(), "The prefix is the match key");
        }

        @Test
        @DisplayName("Should match an exact path by un-normalized string equality only")
        void shouldMatchExactPathByEqualityOnly() {
            var matcher = RouteMatcher.from(MatchConfig.builder().path("/a").build());

            assertTrue(matcher.matchesPath("/a"), "The exact address matches");
            assertFalse(matcher.matchesPath("/a/"), "A trailing-slash variant is a distinct address");
            assertFalse(matcher.matchesPath("/a/b"), "A child path does not match an exact route");
            assertFalse(matcher.matchesPath("/ab"), "A leading-substring continuation does not match");
            assertTrue(matcher.isExact(), "A path matcher is an exact matcher");
            assertEquals("/a", matcher.matchKey(), "The exact path is the match key");
        }

        @Test
        @DisplayName("Should keep a trailing slash significant for an exact path that declares one")
        void shouldKeepTrailingSlashSignificantForExactPath() {
            var matcher = RouteMatcher.from(MatchConfig.builder().path("/a/").build());

            assertTrue(matcher.matchesPath("/a/"), "The declared trailing-slash address matches");
            assertFalse(matcher.matchesPath("/a"), "The slash-less variant is a distinct address");
            assertFalse(matcher.matchesPath("/a/b"), "A child path does not match, even below a slash");
        }

        @Test
        @DisplayName("Should apply the method, host and header matchers on top of an exact path")
        void shouldApplyAllMatchersToExactPath() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .path("/login")
                    .methods(List.of(HttpMethod.POST))
                    .build());

            assertTrue(matcher.matches("/login", HttpMethod.POST, null, Map.of()), "Path and method hold");
            assertFalse(matcher.matches("/login", HttpMethod.GET, null, Map.of()), "Wrong method fails");
            assertFalse(matcher.matches("/login/x", HttpMethod.POST, null, Map.of()), "A child path fails");
        }

        @Test
        @DisplayName("Should apply method, host, and header matchers with AND semantics")
        void shouldApplyAllMatchersWithAndSemantics() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .pathPrefix("/api")
                    .methods(List.of(HttpMethod.GET))
                    .host("gw.example")
                    .headers(List.of(HeaderMatcher.builder().name("X-Tenant").present(true).build()))
                    .build());
            Map<String, String> headers = Map.of("x-tenant", "acme");

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
                    .headers(List.of(HeaderMatcher.builder().name("X-Env").value("prod").build()))
                    .build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", "prod")), "Exact value matches");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", "dev")), "Wrong value fails");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should match a mixed-case configured header name against the lower-case-keyed request headers")
        void shouldMatchMixedCaseHeaderNameCaseInsensitively(String value) {
            var presenceMatcher = headerMatcher(HeaderMatcher.builder().name("X-Tenant").present(true).build());
            var valueMatcher = headerMatcher(HeaderMatcher.builder().name("X-Tenant").value(value).build());
            Map<String, String> headers = Map.of("x-tenant", value);

            assertTrue(presenceMatcher.matches("/api", HttpMethod.GET, null, headers),
                    "A present: true matcher declared as X-Tenant finds the header keyed x-tenant");
            assertTrue(valueMatcher.matches("/api", HttpMethod.GET, null, headers),
                    "A value matcher declared as X-Tenant finds the header keyed x-tenant");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should require the header's absence for present: false")
        void shouldRequireAbsenceForPresentFalse(String value) {
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Internal").present(false).build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of()),
                    "present: false holds when the header is absent");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-internal", value)),
                    "present: false fails when the header is present");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should require both presence and value for present: true together with value")
        void shouldRequireBothPresenceAndValue(String value) {
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Env").present(true).value(value).build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", value)),
                    "The present header carrying the exact value matches");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", value + "-other")),
                    "A present header carrying a different value fails");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of()),
                    "An absent header fails");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should compare header values case-sensitively")
        void shouldCompareHeaderValuesCaseSensitively(String label) {
            String configured = "v" + label.toLowerCase(Locale.ROOT);
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Env").value(configured).build());

            assertFalse(matcher.matches("/api", HttpMethod.GET, null,
                    Map.of("x-env", configured.toUpperCase(Locale.ROOT))),
                    "A value differing only in letter case does not match");
        }

        @Test
        @DisplayName("Should expose lower-case matcher header names, collapsing names that differ only in case")
        void shouldExposeLowerCaseMatcherHeaderNames() {
            var matcher = headerMatcher(
                    HeaderMatcher.builder().name("X-Tenant").present(true).build(),
                    HeaderMatcher.builder().name("x-TENANT").value("acme").build(),
                    HeaderMatcher.builder().name("X-Env").present(false).build());

            assertEquals(List.of("x-tenant", "x-env"), matcher.matchHeaderNames(),
                    "The names are lower-cased in declaration order, case-only duplicates collapsed");
        }

        private RouteMatcher headerMatcher(HeaderMatcher... headers) {
            return RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").headers(List.of(headers)).build());
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
            // MINIMAL (which would silently disable validation on a route nobody configured).
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
            RouteRuntime minimal = runtimeBuilder().securityProfile(SecurityProfile.MINIMAL).build();

            // Assert
            assertEquals(SecurityProfile.LENIENT, lenient.getSecurityProfile(),
                    "an explicitly resolved LENIENT overrides the builder default");
            assertEquals(SecurityProfile.MINIMAL, minimal.getSecurityProfile(),
                    "an explicitly resolved MINIMAL overrides the builder default");
            assertFalse(minimal.getSecurityProfile().skippableValidationEnabled(),
                    "the MINIMAL route reports its skippable half as disabled");
        }

        @Test
        @DisplayName("Should default an omitted securityConfiguration to null, the absent value the stage guards")
        void shouldDefaultSecurityConfigurationToNull() {
            // Arrange + Act — a builder call site that declares no resolved configuration
            RouteRuntime runtime = runtimeBuilder().build();

            // Assert — null is the absent value ThoroughChecksStage resolves on the hot path via
            // requireNonNullElse(route.getSecurityConfiguration(), defaultConfiguration).
            assertNull(runtime.getSecurityConfiguration(),
                    "the default carries no configuration, so the stage falls back to the gateway baseline");
        }

        private RouteRuntime.RouteRuntimeBuilder runtimeBuilder() {
            return RouteRuntime.builder()
                    .id("r")
                    .protocol(Protocol.HTTP)
                    .matcher(RouteMatcher.from(MatchConfig.builder().pathPrefix("/r").build()))
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
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
