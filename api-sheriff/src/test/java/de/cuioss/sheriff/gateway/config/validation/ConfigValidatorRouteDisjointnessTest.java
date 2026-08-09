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
package de.cuioss.sheriff.gateway.config.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Tests for the same-prefix route-disjointness rule of {@link ConfigValidator} (D5, ADR-0009).
 * <p>
 * Two enabled routes that normalize to the same {@code match.path_prefix} collide unless some
 * matcher distinguishes them. The rule decides that by conjunction: the routes collide only when
 * their hosts overlap <em>and</em> their methods overlap <em>and</em> no header matcher tells them
 * apart. Each nested group pins one of those three discriminators — including the asymmetric
 * "declared on one side only" shapes, where an omitted matcher means "matches anything" and
 * therefore still overlaps rather than distinguishing.
 * <p>
 * The sibling {@code ConfigValidatorTest} covers the rule's baseline (a plain same-prefix collision
 * and prefix normalization); this class covers the discriminator matrix, which is where the
 * fail-open risk lives — a matcher wrongly treated as distinguishing would let two colliding routes
 * boot, and the request would then be dispatched to whichever route won an undefined ordering.
 */
@EnableGeneratorController
class ConfigValidatorRouteDisjointnessTest {

    private static final String SHARED_PREFIX = "/api";
    private static final String COLLISION_MESSAGE = "are not disjoint";

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static ResolvedTopology topologyWith(String alias) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        return new ResolvedTopology(map);
    }

    private static RouteConfig route(String id, MatchConfig match) {
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static MatchConfig.MatchConfigBuilder sharedPrefix(HttpMethod... methods) {
        return MatchConfig.builder().pathPrefix(SHARED_PREFIX).methods(List.of(methods));
    }

    private static HeaderMatcher headerWithValue(String name, String value) {
        return HeaderMatcher.builder().name(name).value(value).build();
    }

    private static HeaderMatcher headerWithPresence(String name, boolean present) {
        return HeaderMatcher.builder().name(name).present(present).build();
    }

    /**
     * Validates one endpoint holding exactly the two supplied same-prefix routes.
     */
    private List<ConfigError> validateRoutes(MatchConfig first, MatchConfig second) {
        EndpointConfig endpoint = EndpointConfig.builder()
                .id("orders")
                .enabled(true)
                .baseUrl("ORDERS")
                .auth(new AuthConfig(Require.NONE, List.of()))
                .routes(List.of(route("first", first), route("second", second)))
                .build();

        return validator.validate(GatewayConfig.builder().version(1).build(), List.of(endpoint),
                topologyWith("ORDERS"));
    }

    private static void assertCollides(List<ConfigError> errors) {
        assertTrue(errors.stream().anyMatch(error -> error.message().contains(COLLISION_MESSAGE)),
                () -> "The two same-prefix routes should be reported as not disjoint, got: " + errors);
    }

    private static void assertDisjoint(List<ConfigError> errors) {
        assertTrue(errors.stream().noneMatch(error -> error.message().contains(COLLISION_MESSAGE)),
                () -> "The two same-prefix routes should be accepted as disjoint, got: " + errors);
    }

    @Nested
    @DisplayName("The host discriminator")
    class HostDiscriminator {

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept same-prefix routes bound to two different hosts")
        void shouldAcceptRoutesOnDifferentHosts(String label) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET).host(label + "-a.example.com").build(),
                    sharedPrefix(HttpMethod.GET).host(label + "-b.example.com").build());

            assertDisjoint(errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should reject same-prefix routes whose hosts differ only in case")
        void shouldRejectRoutesWhoseHostsDifferOnlyInCase(String label) {
            String host = label + ".example.com";

            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET).host(host.toUpperCase(Locale.ROOT)).build(),
                    sharedPrefix(HttpMethod.GET).host(host).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject a host-bound route colliding with a host-agnostic route")
        void shouldRejectHostBoundRouteAgainstHostAgnosticRoute() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET).host("api.example.com").build(),
                    sharedPrefix(HttpMethod.GET).build());

            assertCollides(errors);
        }
    }

    @Nested
    @DisplayName("The method discriminator")
    class MethodDiscriminator {

        @Test
        @DisplayName("Should reject a method-agnostic first route colliding with a GET route")
        void shouldRejectMethodAgnosticFirstRoute() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix().build(),
                    sharedPrefix(HttpMethod.GET).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject a GET route colliding with a method-agnostic second route")
        void shouldRejectMethodAgnosticSecondRoute() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET).build(),
                    sharedPrefix().build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject same-prefix routes whose method sets intersect only partly")
        void shouldRejectPartiallyIntersectingMethodSets() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET, HttpMethod.POST).build(),
                    sharedPrefix(HttpMethod.POST, HttpMethod.DELETE).build());

            assertCollides(errors);
        }
    }

    @Nested
    @DisplayName("The header discriminator")
    class HeaderDiscriminator {

        @Test
        @DisplayName("Should accept routes distinguished by two different values of the same header")
        void shouldAcceptRoutesDistinguishedByHeaderValue() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "beta"))).build());

            assertDisjoint(errors);
        }

        @Test
        @DisplayName("Should accept routes distinguished by opposite presence requirements on one header")
        void shouldAcceptRoutesDistinguishedByHeaderPresence() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", true))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", false))).build());

            assertDisjoint(errors);
        }

        @Test
        @DisplayName("Should reject routes matching the same header value")
        void shouldRejectRoutesMatchingTheSameHeaderValue() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject routes requiring the same header presence")
        void shouldRejectRoutesRequiringTheSameHeaderPresence() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", true))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", true))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject routes whose header matchers name different headers")
        void shouldRejectRoutesMatchingDifferentHeaderNames() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Region", "eu"))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject a value matcher against a presence matcher on the same header")
        void shouldRejectValueMatcherAgainstPresenceMatcher() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Tenant", true))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject a presence matcher against a value matcher on the same header")
        void shouldRejectPresenceMatcherAgainstValueMatcher() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Tenant", true))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should reject a header-matched route colliding with a header-agnostic route")
        void shouldRejectHeaderMatchedRouteAgainstHeaderAgnosticRoute() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", "alpha"))).build(),
                    sharedPrefix(HttpMethod.GET).build());

            assertCollides(errors);
        }
    }
}
