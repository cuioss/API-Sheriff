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
package de.cuioss.sheriff.gateway.config.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * boot, and the request would then be dispatched to whichever route won an undefined ordering. The
 * header discriminator mirrors the runtime matcher: names compare exactly as {@code RouteMatcher}
 * normalises them (lower-cased under {@link Locale#ROOT}, so ASCII names compare case-insensitively),
 * values case-sensitively, and a matcher requiring the header (a {@code value} or
 * {@code present: true}) is disjoint from one forbidding it ({@code present: false}). A dedicated
 * group pins the non-ASCII name pair where per-character case folding and {@code Locale.ROOT}
 * lower-casing disagree, so the validator can never judge two names equal that the runtime reads as
 * two different headers.
 * <p>
 * The class also pins the companion refusal of a single matcher declaring {@code present: false}
 * together with {@code value} — a matcher that can never hold — including that every such matcher
 * is reported in one pass, and the intra-route refusal of two matchers naming the same header
 * (compared as the runtime normalises them) that can never hold together: differing values, or one requiring the
 * header while the other forbids it. Redundant but compatible pairs stay valid.
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
                .auth(new AuthConfig(Require.NONE, null, null))
                .routes(List.of(route("first", first), route("second", second)))
                .build();

        return validator.validate(GatewayConfig.builder().version(1).build(), List.of(endpoint),
                topologyWith("ORDERS"));
    }

    /**
     * Validates one endpoint holding exactly the supplied routes.
     */
    private List<ConfigError> validateEndpoint(RouteConfig... routes) {
        EndpointConfig endpoint = EndpointConfig.builder()
                .id("orders")
                .enabled(true)
                .baseUrl("ORDERS")
                .auth(new AuthConfig(Require.NONE, null, null))
                .routes(List.of(routes))
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
    @DisplayName("Exact match.path routes (AS-3)")
    class ExactPathRoutes {

        private MatchConfig.MatchConfigBuilder exact(String path, HttpMethod... methods) {
            return MatchConfig.builder().path(path).methods(List.of(methods));
        }

        @Test
        @DisplayName("Should refuse two exact routes on the same path that nothing distinguishes")
        void shouldRefuseDuplicateExactRoutes() {
            List<ConfigError> errors = validateRoutes(
                    exact(SHARED_PREFIX, HttpMethod.GET).build(),
                    exact(SHARED_PREFIX, HttpMethod.GET).build());

            assertCollides(errors);
            assertTrue(errors.stream().anyMatch(error -> error.message().contains("share path '/api'")),
                    () -> "the refusal names the exact path the routes share, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an exact route and a prefix route declaring the same string")
        void shouldAcceptExactAndPrefixRouteOnTheSameString() {
            List<ConfigError> errors = validateRoutes(
                    exact(SHARED_PREFIX, HttpMethod.GET).build(),
                    sharedPrefix(HttpMethod.GET).build());

            assertDisjoint(errors);
        }

        @Test
        @DisplayName("Should accept two exact routes on the same path distinguished by method")
        void shouldAcceptExactRoutesDistinguishedByMethod() {
            List<ConfigError> errors = validateRoutes(
                    exact(SHARED_PREFIX, HttpMethod.GET).build(),
                    exact(SHARED_PREFIX, HttpMethod.POST).build());

            assertDisjoint(errors);
        }

        @Test
        @DisplayName("Should accept exact routes whose paths differ only by a trailing slash")
        void shouldAcceptExactRoutesDifferingByTrailingSlash() {
            List<ConfigError> errors = validateRoutes(
                    exact(SHARED_PREFIX, HttpMethod.GET).build(),
                    exact(SHARED_PREFIX + "/", HttpMethod.GET).build());

            assertDisjoint(errors);
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

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept a value matcher against a present: false matcher on the same header")
        void shouldAcceptValueMatcherAgainstAbsenceMatcher(String value) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", value))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Tenant", false))).build());

            assertDisjoint(errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept a present: false matcher against a value matcher on the same header")
        void shouldAcceptAbsenceMatcherAgainstValueMatcher(String value) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Tenant", false))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", value))).build());

            assertDisjoint(errors);
        }

        @Test
        @DisplayName("Should reject routes both forbidding the same header")
        void shouldRejectRoutesBothForbiddingTheSameHeader() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", false))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence("X-Internal", false))).build());

            assertCollides(errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should reject the same value under header names differing only in case")
        void shouldRejectSameValueUnderNamesDifferingOnlyInCase(String value) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", value))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("x-tenant", value))).build());

            assertCollides(errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept different values under header names differing only in case")
        void shouldAcceptDifferentValuesUnderNamesDifferingOnlyInCase(String value) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("X-Tenant", value + "-a"))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue("x-tenant", value + "-b"))).build());

            assertDisjoint(errors);
        }
    }

    @Nested
    @DisplayName("The present: false + value contradiction refusal")
    class HeaderMatcherContradiction {

        private static final String CONTRADICTION_MESSAGE = "both present: false and value";

        private static HeaderMatcher contradictory(String name, String value) {
            return HeaderMatcher.builder().name(name).present(false).value(value).build();
        }

        private static List<ConfigError> contradictions(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.message().contains(CONTRADICTION_MESSAGE)).toList();
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should refuse a matcher declaring present: false together with value, naming route and header")
        void shouldRefusePresentFalseWithValue(String value) {
            List<ConfigError> errors = validateEndpoint(route("dead-route",
                    sharedPrefix(HttpMethod.GET).headers(List.of(contradictory("X-Tenant", value))).build()));

            List<ConfigError> refusals = contradictions(errors);
            assertEquals(1, refusals.size(), () -> "Exactly one contradiction refusal expected, got: " + errors);
            String message = refusals.getFirst().message();
            assertAll("The refusal names the route and the header",
                    () -> assertTrue(message.contains("'dead-route'"), () -> "route id missing: " + message),
                    () -> assertTrue(message.contains("'X-Tenant'"), () -> "header name missing: " + message));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept a matcher declaring present: true together with value")
        void shouldAcceptPresentTrueWithValue(String value) {
            HeaderMatcher coherent = HeaderMatcher.builder().name("X-Tenant").present(true).value(value).build();

            List<ConfigError> errors = validateEndpoint(route("live-route",
                    sharedPrefix(HttpMethod.GET).headers(List.of(coherent)).build()));

            assertTrue(contradictions(errors).isEmpty(),
                    () -> "present: true with value is coherent and must not be refused, got: " + errors);
        }

        @Test
        @DisplayName("Should report every contradictory matcher, one refusal per route")
        void shouldReportEveryContradictoryMatcher() {
            List<ConfigError> errors = validateEndpoint(
                    route("dead-one", sharedPrefix(HttpMethod.GET)
                            .headers(List.of(contradictory("X-Tenant", "alpha"))).build()),
                    route("dead-two", sharedPrefix(HttpMethod.POST)
                            .headers(List.of(contradictory("X-Region", "eu"))).build()));

            List<ConfigError> refusals = contradictions(errors);
            assertAll("All violations are collected in one pass",
                    () -> assertEquals(2, refusals.size(), () -> "Two refusals expected, got: " + errors),
                    () -> assertTrue(refusals.stream().anyMatch(error -> error.message().contains("'dead-one'")),
                            () -> "dead-one missing: " + refusals),
                    () -> assertTrue(refusals.stream().anyMatch(error -> error.message().contains("'dead-two'")),
                            () -> "dead-two missing: " + refusals));
        }
    }

    @Nested
    @DisplayName("The intra-route same-name matcher contradiction refusal")
    class IntraRouteSameNameContradiction {

        private static final String PAIR_MESSAGE = "can never hold together";

        private List<ConfigError> validateSingleRoute(String routeId, HeaderMatcher... headers) {
            return validateEndpoint(route(routeId, sharedPrefix(HttpMethod.GET).headers(List.of(headers)).build()));
        }

        private static List<ConfigError> pairRefusals(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.message().contains(PAIR_MESSAGE)).toList();
        }

        private static void assertSinglePairRefusal(List<ConfigError> errors, String routeId, String firstName,
                String secondName, String conflict) {
            List<ConfigError> refusals = pairRefusals(errors);
            assertEquals(1, refusals.size(), () -> "Exactly one pair refusal expected, got: " + errors);
            String message = refusals.getFirst().message();
            assertAll("The refusal names the route, both header matchers and the conflict",
                    () -> assertTrue(message.contains("'" + routeId + "'"), () -> "route id missing: " + message),
                    () -> assertTrue(message.contains("'" + firstName + "'"), () -> "first name missing: " + message),
                    () -> assertTrue(message.contains("'" + secondName + "'"),
                            () -> "second name missing: " + message),
                    () -> assertTrue(message.contains(conflict), () -> "conflict missing: " + message));
        }

        private static void assertNoPairRefusal(List<ConfigError> errors) {
            assertTrue(pairRefusals(errors).isEmpty(),
                    () -> "A redundant but compatible pair must not be refused, got: " + errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should refuse a value matcher and a present: false matcher on the same header in one route")
        void shouldRefuseValueAgainstPresentFalse(String value) {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithValue("X-Tenant", value), headerWithPresence("X-Tenant", false));

            assertSinglePairRefusal(errors, "dead-route", "X-Tenant", "X-Tenant", "forbids");
        }

        @Test
        @DisplayName("Should refuse a present: true matcher and a present: false matcher on the same header")
        void shouldRefusePresentTrueAgainstPresentFalse() {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithPresence("X-Internal", true), headerWithPresence("X-Internal", false));

            assertSinglePairRefusal(errors, "dead-route", "X-Internal", "X-Internal", "forbids");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should refuse two differing values on the same header in one route")
        void shouldRefuseDifferingValues(String value) {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithValue("X-Tenant", value + "-a"), headerWithValue("X-Tenant", value + "-b"));

            assertSinglePairRefusal(errors, "dead-route", "X-Tenant", "X-Tenant", "different values");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should refuse a contradiction between header names differing only in case")
        void shouldRefuseMixedCaseContradiction(String value) {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithValue("X-Tenant", value), headerWithPresence("x-tenant", false));

            assertSinglePairRefusal(errors, "dead-route", "X-Tenant", "x-tenant", "forbids");
        }

        @Test
        @DisplayName("Should compare values case-sensitively, refusing values differing only in case")
        void shouldRefuseValuesDifferingOnlyInCase() {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithValue("X-Tenant", "alpha"), headerWithValue("x-tenant", "ALPHA"));

            assertSinglePairRefusal(errors, "dead-route", "X-Tenant", "x-tenant", "different values");
        }

        @Test
        @DisplayName("Should report every conflicting pair of one route in one pass")
        void shouldReportEveryConflictingPair() {
            List<ConfigError> errors = validateSingleRoute("dead-route",
                    headerWithValue("X-Tenant", "alpha"), headerWithValue("x-tenant", "beta"),
                    headerWithPresence("X-TENANT", false));

            assertEquals(3, pairRefusals(errors).size(),
                    () -> "Each of the three conflicting pairs is refused once, got: " + errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept a value matcher and a present: true matcher on the same header")
        void shouldAcceptValueWithPresentTrue(String value) {
            assertNoPairRefusal(validateSingleRoute("live-route",
                    headerWithValue("X-Tenant", value), headerWithPresence("x-tenant", true)));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept two identical values on the same header")
        void shouldAcceptIdenticalValues(String value) {
            assertNoPairRefusal(validateSingleRoute("live-route",
                    headerWithValue("X-Tenant", value), headerWithValue("x-tenant", value)));
        }

        @Test
        @DisplayName("Should accept two present: true matchers on the same header")
        void shouldAcceptTwoPresentTrue() {
            assertNoPairRefusal(validateSingleRoute("live-route",
                    headerWithPresence("X-Internal", true), headerWithPresence("x-internal", true)));
        }

        @Test
        @DisplayName("Should accept two present: false matchers on the same header")
        void shouldAcceptTwoPresentFalse() {
            assertNoPairRefusal(validateSingleRoute("live-route",
                    headerWithPresence("X-Internal", false), headerWithPresence("x-internal", false)));
        }

        @Test
        @DisplayName("Should accept conflicting-looking matchers that name different headers")
        void shouldAcceptDifferentHeaderNames() {
            assertNoPairRefusal(validateSingleRoute("live-route",
                    headerWithValue("X-Tenant", "alpha"), headerWithPresence("X-Region", false)));
        }
    }

    /**
     * Pins that header-matcher names are compared exactly as {@code RouteMatcher.from} normalises them
     * — {@code toLowerCase(Locale.ROOT)} — and not by {@link String#equalsIgnoreCase}. The two agree
     * on ASCII but not on U+0130 (capital I with dot above): per-character folding equates it with a
     * plain {@code I}, while {@code Locale.ROOT} lower-casing maps it to {@code i} plus a combining
     * dot. The name pair below is therefore one header to {@code equalsIgnoreCase} and two headers to
     * the runtime; every verdict must follow the runtime.
     */
    @Nested
    @DisplayName("Header names compared as the runtime normalises them")
    class RuntimeNameNormalisation {

        /** A header name carrying U+0130, the character per-character case folding conflates. */
        private static final String DOTTED_CAPITAL_I_NAME = "X-İd";

        /** The ASCII name {@code equalsIgnoreCase} equates with {@link #DOTTED_CAPITAL_I_NAME}. */
        private static final String PLAIN_I_NAME = "X-Id";

        @Test
        @DisplayName("Should use a name pair that case folding equates but runtime lower-casing keeps apart")
        void shouldUseNamePairThatOnlyCaseFoldingEquates() {
            assertAll("The fixture names must reproduce the folding/lower-casing disagreement",
                    () -> assertTrue(DOTTED_CAPITAL_I_NAME.equalsIgnoreCase(PLAIN_I_NAME),
                            "equalsIgnoreCase must treat the pair as one name"),
                    () -> assertNotEquals(PLAIN_I_NAME.toLowerCase(Locale.ROOT),
                            DOTTED_CAPITAL_I_NAME.toLowerCase(Locale.ROOT),
                            "Locale.ROOT lower-casing must yield two different runtime names"));
        }

        @Test
        @DisplayName("Should not certify present: false and present: true on runtime-distinct names as disjoint")
        void shouldRejectPresenceMatchersOnRuntimeDistinctNames() {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence(DOTTED_CAPITAL_I_NAME, false))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence(PLAIN_I_NAME, true))).build());

            assertCollides(errors);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should not certify a value matcher and present: false on runtime-distinct names as disjoint")
        void shouldRejectValueAgainstAbsenceOnRuntimeDistinctNames(String value) {
            List<ConfigError> errors = validateRoutes(
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithValue(PLAIN_I_NAME, value))).build(),
                    sharedPrefix(HttpMethod.GET)
                            .headers(List.of(headerWithPresence(DOTTED_CAPITAL_I_NAME, false))).build());

            assertCollides(errors);
        }

        @Test
        @DisplayName("Should not refuse present: false and present: true on runtime-distinct names in one route")
        void shouldAcceptPresenceMatchersOnRuntimeDistinctNamesInOneRoute() {
            List<ConfigError> errors = validateEndpoint(route("live-route", sharedPrefix(HttpMethod.GET)
                    .headers(List.of(headerWithPresence(DOTTED_CAPITAL_I_NAME, false),
                            headerWithPresence(PLAIN_I_NAME, true)))
                    .build()));

            assertTrue(errors.stream().noneMatch(
                            error -> error.message().contains(IntraRouteSameNameContradiction.PAIR_MESSAGE)),
                    () -> "The two matchers read different runtime headers and must not be refused, got: "
                            + errors);
        }
    }
}
