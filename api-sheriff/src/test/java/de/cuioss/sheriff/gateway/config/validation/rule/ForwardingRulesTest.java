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
package de.cuioss.sheriff.gateway.config.validation.rule;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code forward}-block rules the {@link ConfigValidator} runs through
 * {@link ForwardingRules}: the forward-mode exclusivity refusal of a dimension declaring both its
 * positive-list and its negative-list, and the refusal of a {@code token_relay: false} route whose
 * effective {@code headers_allow} re-admits {@code Authorization}, and the refusal of a
 * {@code forward} key naming {@code Cookie} — each at the route scope and at the endpoint scope the
 * {@code forward} block is declarable at.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — forwarding")
class ForwardingRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static GatewayConfig.GatewayConfigBuilder validGateway() {
        return GatewayConfig.builder().version(1);
    }

    private static AuthConfig auth(Require require) {
        return AuthConfig.builder().require(require).build();
    }

    private static ResolvedTopology topologyWith(String... aliases) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        for (String alias : aliases) {
            map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        }
        return new ResolvedTopology(map);
    }

    private static MatchConfig match(String pathPrefix, HttpMethod... methods) {
        return MatchConfig.builder().pathPrefix(pathPrefix).methods(List.of(methods)).build();
    }

    private static RouteConfig route(String id, HttpMethod... methods) {
        return RouteConfig.builder().id(id).match(match("/" + id, methods)).build();
    }

    private static EndpointConfig endpoint(String id, String alias, List<HttpMethod> allowedMethods,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .auth(auth(Require.NONE))
                .allowedMethods(allowedMethods)
                .routes(List.of(routes))
                .build();
    }

    private static void assertHasError(List<ConfigError> errors, String pointerContains, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(e -> e.pointer().contains(pointerContains) && e.message().contains(messageContains)),
                () -> "expected an error whose pointer contains '" + pointerContains + "' and message contains '"
                        + messageContains + "', but got: " + errors);
    }

    /** The single refusal recorded at exactly {@code pointer}; fails when there is none or more than one. */
    private static ConfigError singleRefusalAt(List<ConfigError> errors, String pointer) {
        List<ConfigError> refusals = errors.stream().filter(error -> pointer.equals(error.pointer())).toList();
        assertEquals(1, refusals.size(), () -> "expected exactly one refusal at " + pointer + ", got: " + refusals);
        return refusals.getFirst();
    }

    /**
     * The forward-mode exclusivity rule: each {@code forward} dimension resolves exactly one of the
     * three modes, so declaring both {@code *_allow} and {@code *_deny} for one dimension is refused
     * at boot rather than reconciled by a silent precedence guess.
     * <p>
     * Every refusal case is paired with a matched positive control declaring exactly one list, so a
     * rule that started refusing everything — or nothing — fails here instead of passing as a
     * one-sided assertion.
     */
    @Nested
    @DisplayName("Forward-mode exclusivity (a dimension resolves exactly one mode)")
    class ForwardModeExclusivity {

        private EndpointConfig endpointWithForward(ForwardConfig forward) {
            RouteConfig route = RouteConfig.builder()
                    .id("proxied")
                    .match(match("/proxied", HttpMethod.GET))
                    .forward(forward)
                    .build();
            return endpoint("orders", "ORDERS", List.of(HttpMethod.GET), route);
        }

        private List<ConfigError> validateForward(ForwardConfig forward) {
            return validator.validate(validGateway().build(), List.of(endpointWithForward(forward)),
                    topologyWith("ORDERS"));
        }

        @Test
        @DisplayName("Should refuse a route declaring both headers_allow and headers_deny")
        void shouldRefuseBothHeaderLists() {
            List<ConfigError> errors = validateForward(ForwardConfig.builder()
                    .headersAllow(List.of("Accept")).headersDeny(List.of("X-Internal")).setHeaders(Map.of()).build());

            assertAll("the refusal names the route and the dimension",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertHasError(errors, "/endpoint/routes", "proxied"),
                    () -> assertHasError(errors, "/endpoint/routes", "headers_allow"),
                    () -> assertHasError(errors, "/endpoint/routes", "headers_deny"));
        }

        @Test
        @DisplayName("Should refuse a route declaring both query_allow and query_deny")
        void shouldRefuseBothQueryLists() {
            List<ConfigError> errors = validateForward(ForwardConfig.builder()
                    .queryAllow(List.of("page")).queryDeny(List.of("debug")).setHeaders(Map.of()).build());

            assertAll("the refusal names the route and the dimension",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertHasError(errors, "/endpoint/routes", "proxied"),
                    () -> assertHasError(errors, "/endpoint/routes", "query_allow"),
                    () -> assertHasError(errors, "/endpoint/routes", "query_deny"));
        }

        @Test
        @DisplayName("Should report both dimensions in one pass rather than stopping at the first")
        void shouldReportBothDimensionsInOnePass() {
            List<ConfigError> errors = validateForward(ForwardConfig.builder()
                    .headersAllow(List.of("Accept")).headersDeny(List.of("X-Internal"))
                    .queryAllow(List.of("page")).queryDeny(List.of("debug")).setHeaders(Map.of()).build());

            assertAll("ADR-0009 single-pass aggregation: the operator sees the whole list at once",
                    () -> assertEquals(2, errors.size(), () -> "one violation per dimension, got: " + errors),
                    () -> assertHasError(errors, "/endpoint/routes", "headers_allow"),
                    () -> assertHasError(errors, "/endpoint/routes", "query_allow"));
        }

        /**
         * A declared-empty list is declared: pairing {@code headers_allow: []} with a
         * {@code headers_deny} is the same contradiction as pairing two populated lists. This is the
         * case a naive {@code isEmpty()} check would wave through.
         */
        @Test
        @DisplayName("Should refuse a declared-EMPTY list paired with the opposite list")
        void shouldRefuseDeclaredEmptyPairedWithOppositeList() {
            List<ConfigError> errors = validateForward(ForwardConfig.builder()
                    .headersAllow(List.of()).headersDeny(List.of("X-Internal")).setHeaders(Map.of()).build());

            assertEquals(1, errors.size(),
                    () -> "a declared-empty positive-list is still declared, so pairing it with a deny"
                            + " list is refused, got: " + errors);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("singleModeRoutes")
        @DisplayName("Should accept a route declaring exactly one mode per dimension")
        void shouldAcceptSingleModeRoutes(String name, ForwardConfig forward) {
            List<ConfigError> errors = validateForward(forward);

            assertTrue(errors.isEmpty(),
                    () -> "'" + name + "' declares at most one list per dimension and must boot clean, got: "
                            + errors);
        }

        static Stream<Arguments> singleModeRoutes() {
            return Stream.of(
                    Arguments.of("positive-list on both dimensions", ForwardConfig.builder()
                            .headersAllow(List.of("Accept")).queryAllow(List.of("page")).setHeaders(Map.of()).build()),
                    Arguments.of("negative-list on both dimensions", ForwardConfig.builder()
                            .headersDeny(List.of("X-Internal")).queryDeny(List.of("debug")).setHeaders(Map.of()).build()),
                    Arguments.of("positive-list headers, negative-list query", ForwardConfig.builder()
                            .headersAllow(List.of("Accept")).queryDeny(List.of("debug")).setHeaders(Map.of()).build()),
                    Arguments.of("negative-list headers, positive-list query", ForwardConfig.builder()
                            .headersDeny(List.of("X-Internal")).queryAllow(List.of("page")).setHeaders(Map.of()).build()),
                    Arguments.of("forward-all on both dimensions",
                            ForwardConfig.builder().setHeaders(Map.of()).build()),
                    Arguments.of("declared-empty positive-lists, no deny lists",
                            ForwardConfig.builder().headersAllow(List.of()).queryAllow(List.of())
                                    .setHeaders(Map.of()).build()));
        }

        @Test
        @DisplayName("Should accept a route declaring no forward block at all")
        void shouldAcceptRouteWithoutForwardBlock() {
            List<ConfigError> errors = validator.validate(validGateway().build(),
                    List.of(endpoint("orders", "ORDERS", List.of(HttpMethod.GET), route("proxied", HttpMethod.GET))),
                    topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "an absent forward block declares no list, got: " + errors);
        }

        @Test
        @DisplayName("The refusal message tells the operator which lists collided")
        void refusalMessageNamesTheCollidingKeys() {
            List<ConfigError> errors = validateForward(ForwardConfig.builder()
                    .headersAllow(List.of("Accept")).headersDeny(List.of("X-Internal")).setHeaders(Map.of()).build());

            assertTrue(errors.getFirst().message().contains("declares both"),
                    () -> "the message must state that both lists were declared, got: "
                            + errors.getFirst().message());
        }
    }

    @Nested
    @DisplayName("token_relay: false must not re-admit Authorization through headers_allow")
    class TokenRelayForwardConflict {

        private static final String CONFLICT_MESSAGE = "resolves auth.token_relay: false";

        private List<ConfigError> validateSessionRoute(@Nullable Boolean tokenRelay, List<String> headersAllow) {
            RouteConfig relayRoute = RouteConfig.builder().id("relay").match(match("/relay", HttpMethod.GET))
                    .forward(ForwardConfig.builder().headersAllow(headersAllow).build()).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("app").enabled(true).baseUrl("APP")
                    .auth(AuthConfig.builder().require(Require.SESSION).tokenRelay(tokenRelay).build())
                    .routes(List.of(relayRoute)).build();
            return validator.validate(validGateway().oidc(OidcConfig.builder().build()).build(), List.of(endpoint),
                    topologyWith("APP"));
        }

        private boolean hasConflict(List<ConfigError> errors) {
            return errors.stream().anyMatch(error -> error.message().contains(CONFLICT_MESSAGE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION"})
        @DisplayName("Should refuse token_relay: false with Authorization in headers_allow, in any letter case")
        void shouldRefuseAuthorizationInAnyCase(String spelling) {
            List<ConfigError> errors = validateSessionRoute(false, List.of("Accept", spelling));

            assertHasError(errors, "/endpoint/routes", CONFLICT_MESSAGE);
            assertTrue(errors.stream().anyMatch(error -> error.message().contains("route 'relay'")),
                    () -> "the refusal names the route, got: " + errors);
        }

        @Test
        @DisplayName("Should accept token_relay: false when headers_allow does not name Authorization")
        void shouldAcceptWithoutAuthorization() {
            assertFalse(hasConflict(validateSessionRoute(false, List.of("Accept"))));
        }

        @Test
        @DisplayName("Should accept Authorization in headers_allow while token_relay is absent or true")
        void shouldAcceptWhileRelaying() {
            assertAll(
                    () -> assertFalse(hasConflict(validateSessionRoute(null, List.of("Authorization")))),
                    () -> assertFalse(hasConflict(validateSessionRoute(true, List.of("Authorization")))));
        }
    }

    /**
     * The {@code forward} refusals cover the endpoint scope the block is declarable at: a block
     * declared on the endpoint is refused exactly like a route-level one, and a route-level block
     * replacing it wholesale is judged on its own.
     */
    @Nested
    @DisplayName("Endpoint-scope forward refusals")
    class EndpointScopeForward {

        private static final String ENDPOINT_ID = "orders";
        private static final String ALIAS = "ORDERS";
        private static final String ENDPOINT_FORWARD_POINTER = "/endpoint/forward";
        private static final String AUTHORIZATION_REFUSAL = "forward.headers_allow names Authorization";

        private static EndpointConfig.EndpointConfigBuilder endpointWith(AuthConfig auth, RouteConfig... routes) {
            return EndpointConfig.builder().id(ENDPOINT_ID).enabled(true).baseUrl(ALIAS).auth(auth)
                    .routes(List.of(routes));
        }

        private static AuthConfig noTokenRelay() {
            return AuthConfig.builder().require(Require.BEARER).tokenRelay(false).build();
        }

        private List<ConfigError> validate(GatewayConfig gateway, EndpointConfig endpoint) {
            return validator.validate(gateway, List.of(endpoint), topologyWith(ALIAS));
        }

        private static boolean anyMessageContains(List<ConfigError> errors, String fragment) {
            return errors.stream().anyMatch(error -> error.message().contains(fragment));
        }

        @Test
        @DisplayName("Should refuse an endpoint-level forward block declaring both header lists")
        void shouldRefuseEndpointForwardDeclaringBothHeaderLists() {
            ForwardConfig both = ForwardConfig.builder().headersAllow(List.of("Accept"))
                    .headersDeny(List.of("X-Internal")).build();
            EndpointConfig endpoint = endpointWith(auth(Require.NONE), route("r", HttpMethod.GET)).forward(both)
                    .build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FORWARD_POINTER);
            assertTrue(refusal.message().contains("endpoint '" + ENDPOINT_ID
                    + "' declares both headers_allow and headers_deny"), refusal::message);
        }

        @Test
        @DisplayName("Should refuse an endpoint-level forward block declaring both query lists")
        void shouldRefuseEndpointForwardDeclaringBothQueryLists() {
            ForwardConfig both = ForwardConfig.builder().queryAllow(List.of("page")).queryDeny(List.of("debug"))
                    .build();
            EndpointConfig endpoint = endpointWith(auth(Require.NONE), route("r", HttpMethod.GET)).forward(both)
                    .build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FORWARD_POINTER);
            assertTrue(refusal.message().contains("endpoint '" + ENDPOINT_ID
                    + "' declares both query_allow and query_deny"), refusal::message);
        }

        @Test
        @DisplayName("Should accept an endpoint-level forward block declaring one list per dimension (matched control)")
        void shouldAcceptEndpointForwardDeclaringOneListPerDimension() {
            ForwardConfig coherent = ForwardConfig.builder().headersAllow(List.of("Accept"))
                    .queryDeny(List.of("debug")).build();
            EndpointConfig endpoint = endpointWith(auth(Require.NONE), route("r", HttpMethod.GET)).forward(coherent)
                    .build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            assertTrue(errors.stream().noneMatch(error -> ENDPOINT_FORWARD_POINTER.equals(error.pointer())),
                    () -> "a coherent endpoint forward block is not refused, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse Authorization re-admitted by an endpoint-level forward block on a token_relay: false route")
        void shouldRefuseEndpointForwardReadmittingAuthorization() {
            ForwardConfig readmitting = ForwardConfig.builder().headersAllow(List.of("authorization")).build();
            EndpointConfig endpoint = endpointWith(noTokenRelay(), route("r", HttpMethod.GET)).forward(readmitting)
                    .build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FORWARD_POINTER);
            assertTrue(refusal.message().contains("route 'r' resolves auth.token_relay: false but its "
                    + AUTHORIZATION_REFUSAL), refusal::message);
        }

        @Test
        @DisplayName("Should accept a route-level forward block replacing an endpoint block that re-admitted Authorization")
        void shouldAcceptRouteForwardReplacingEndpointAuthorization() {
            ForwardConfig readmitting = ForwardConfig.builder().headersAllow(List.of("Authorization")).build();
            RouteConfig replacing = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .forward(ForwardConfig.builder().headersAllow(List.of("Accept")).build()).build();
            EndpointConfig endpoint = endpointWith(noTokenRelay(), replacing).forward(readmitting).build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            assertFalse(anyMessageContains(errors, AUTHORIZATION_REFUSAL),
                    () -> "the route-level block replaces the endpoint block wholesale, got: " + errors);
        }
    }

    /**
     * Only the {@code cookies} block decides which cookies cross, so a {@code forward} list or
     * {@code set_headers} naming {@code Cookie} is refused at both scopes the {@code forward} block is
     * declarable at, in any letter case. Every refusal has a matched control naming another header.
     */
    @Nested
    @DisplayName("Cookie is governed by the cookies block, never by a forward key")
    class CookieOutsideCookiesBlock {

        private static final String ENDPOINT_ID = "orders";
        private static final String ALIAS = "ORDERS";
        private static final String REFUSAL = "which cookies reach the upstream is decided by the cookies block";

        private List<ConfigError> validateEndpointForward(ForwardConfig forward) {
            EndpointConfig endpoint = EndpointConfig.builder().id(ENDPOINT_ID).enabled(true).baseUrl(ALIAS)
                    .auth(auth(Require.NONE)).forward(forward).routes(List.of(route("r", HttpMethod.GET))).build();
            return validator.validate(validGateway().build(), List.of(endpoint), topologyWith(ALIAS));
        }

        private List<ConfigError> validateRouteForward(ForwardConfig forward) {
            RouteConfig routed = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET)).forward(forward)
                    .build();
            return validator.validate(validGateway().build(),
                    List.of(endpoint(ENDPOINT_ID, ALIAS, List.of(HttpMethod.GET), routed)), topologyWith(ALIAS));
        }

        static Stream<Arguments> forwardsNamingCookie() {
            return Stream.of(
                    Arguments.of("headers_allow", ForwardConfig.builder().headersAllow(List.of("Accept", "Cookie"))
                            .build()),
                    Arguments.of("headers_deny", ForwardConfig.builder().headersDeny(List.of("cookie")).build()),
                    Arguments.of("set_headers", ForwardConfig.builder().setHeaders(Map.of("COOKIE", "a=1")).build()));
        }

        static Stream<Arguments> forwardsNamingAnotherHeader() {
            return Stream.of(
                    Arguments.of("headers_allow", ForwardConfig.builder().headersAllow(List.of("Accept")).build()),
                    Arguments.of("headers_deny", ForwardConfig.builder().headersDeny(List.of("X-Internal")).build()),
                    Arguments.of("set_headers", ForwardConfig.builder().setHeaders(Map.of("X-Gateway", "s")).build()));
        }

        @ParameterizedTest(name = "endpoint forward.{0}")
        @MethodSource("forwardsNamingCookie")
        @DisplayName("Should refuse Cookie in an endpoint-level forward key")
        void shouldRefuseCookieAtEndpointScope(String key, ForwardConfig forward) {
            List<ConfigError> errors = validateEndpointForward(forward);

            ConfigError refusal = singleRefusalAt(errors, "/endpoint/forward/" + key);
            assertAll("the refusal names the scope and the key and points at the cookies block",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("endpoint '" + ENDPOINT_ID + "' names Cookie in forward."
                            + key), refusal::message),
                    () -> assertTrue(refusal.message().contains(REFUSAL), refusal::message),
                    () -> assertEquals("endpoints/" + ENDPOINT_ID + ".yaml", refusal.file()));
        }

        @ParameterizedTest(name = "route forward.{0}")
        @MethodSource("forwardsNamingCookie")
        @DisplayName("Should refuse Cookie in a route-level forward key")
        void shouldRefuseCookieAtRouteScope(String key, ForwardConfig forward) {
            List<ConfigError> errors = validateRouteForward(forward);

            ConfigError refusal = singleRefusalAt(errors, "/endpoint/routes/0/forward/" + key);
            assertAll("the refusal names the route and the key",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("route 'r' names Cookie in forward." + key),
                            refusal::message));
        }

        @ParameterizedTest(name = "endpoint forward.{0}")
        @MethodSource("forwardsNamingAnotherHeader")
        @DisplayName("Should accept another header in an endpoint-level forward key (matched control)")
        void shouldAcceptAnotherHeaderAtEndpointScope(String key, ForwardConfig forward) {
            List<ConfigError> errors = validateEndpointForward(forward);

            assertTrue(errors.isEmpty(), () -> "forward." + key + " naming another header boots clean, got: " + errors);
        }

        @ParameterizedTest(name = "route forward.{0}")
        @MethodSource("forwardsNamingAnotherHeader")
        @DisplayName("Should accept another header in a route-level forward key (matched control)")
        void shouldAcceptAnotherHeaderAtRouteScope(String key, ForwardConfig forward) {
            List<ConfigError> errors = validateRouteForward(forward);

            assertTrue(errors.isEmpty(), () -> "forward." + key + " naming another header boots clean, got: " + errors);
        }

        @Test
        @DisplayName("Should report every forward key naming Cookie in one pass")
        void shouldReportEveryOffendingKey() {
            List<ConfigError> errors = validateRouteForward(ForwardConfig.builder().headersAllow(List.of("Cookie"))
                    .setHeaders(Map.of("Cookie", "a=1")).build());

            assertAll("one refusal per offending key",
                    () -> assertEquals(2, errors.size(), () -> "two violations, got: " + errors),
                    () -> singleRefusalAt(errors, "/endpoint/routes/0/forward/headers_allow"),
                    () -> singleRefusalAt(errors, "/endpoint/routes/0/forward/set_headers"));
        }
    }
}
