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

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the protocol rules the {@link ConfigValidator} runs through {@link ProtocolRules}: the
 * fail-closed WebSocket allowlist (ADR-0015) — required on a bearer and on a session route, exact
 * origins only, a positive idle timeout, and no {@code websocket} block on a route of another
 * protocol — and the {@code upstream.rewrite_location} protocol restriction (AS-11).
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — protocol rules")
class ProtocolRulesTest {

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

    @Nested
    @DisplayName("Fail-closed WebSocket allowlist (ADR-0015)")
    class WebSocketAllowlist {

        private static GatewayConfig gatewayWithIssuer() {
            return validGateway()
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
        }

        private static EndpointConfig webSocketEndpoint(String alias, RouteConfig route) {
            return EndpointConfig.builder()
                    .id("ws-ep").enabled(true).baseUrl(alias)
                    .auth(auth(Require.NONE))
                    .routes(List.of(route))
                    .build();
        }

        private static RouteConfig webSocketRoute(String id, @Nullable WebSocketConfig websocket,
                @Nullable AuthConfig auth) {
            return RouteConfig.builder()
                    .id(id)
                    .protocol(Protocol.WEBSOCKET)
                    .match(match("/" + id, HttpMethod.GET))
                    .auth(auth)
                    .websocket(websocket)
                    .build();
        }

        private static AuthConfig bearer() {
            return auth(Require.BEARER);
        }

        @Test
        @DisplayName("Should reject a bearer WebSocket route with no websocket block (fail-closed)")
        void shouldRejectBearerWebSocketRouteWithAbsentAllowedOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            EndpointConfig endpoint = webSocketEndpoint("WS", webSocketRoute("chat", null, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "must declare a non-empty allowed_origins allowlist");
        }

        @Test
        @DisplayName("Should reject a bearer WebSocket route with an empty allowed_origins allowlist")
        void shouldRejectBearerWebSocketRouteWithEmptyAllowedOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of()).build();
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "fail-closed");
        }

        @ParameterizedTest(name = "wildcard entry \"{0}\" is rejected")
        @ValueSource(strings = {"*", "https://*.example.com"})
        @DisplayName("Should reject wildcard entries in allowed_origins")
        void shouldRejectWildcardAllowedOrigin(String wildcard) {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of(wildcard)).build();
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "wildcards are not permitted");
        }

        @ParameterizedTest(name = "idle_timeout_seconds = {0} is rejected")
        @ValueSource(ints = {0, -1, -300})
        @DisplayName("Should reject a non-positive idle_timeout_seconds")
        void shouldRejectNonPositiveIdleTimeout(int timeout) {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com"))
                    .idleTimeoutSeconds(timeout).build();
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "idle_timeout_seconds must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a bearer WebSocket route with exact origins and a positive idle timeout")
        void shouldAcceptBearerWebSocketRouteWithExactOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com"))
                    .idleTimeoutSeconds(60).build();
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should not require allowed_origins for a public (require: none) WebSocket route")
        void shouldNotRequireAllowedOriginsForPublicWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = webSocketEndpoint("WS", webSocketRoute("chat", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(),
                    () -> "a public (require: none) WebSocket route may omit allowed_origins; got: " + errors);
        }

        @Test
        @DisplayName("Should reject a non-websocket route that declares a websocket block")
        void shouldRejectWebSocketBlockOnNonWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com"))
                    .idleTimeoutSeconds(60).build();
            RouteConfig httpRoute = RouteConfig.builder()
                    .id("http-with-ws")
                    .protocol(Protocol.HTTP)
                    .match(match("/http-with-ws", HttpMethod.GET))
                    .auth(null)
                    .websocket(websocket)
                    .build();
            EndpointConfig endpoint = webSocketEndpoint("WS", httpRoute);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes",
                    "declares a websocket block but its protocol is not 'websocket'");
        }
    }

    @Nested
    @DisplayName("upstream.rewrite_location protocol restriction (AS-11)")
    class RewriteLocationProtocol {

        private static final String REWRITE_LOCATION = "upstream.rewrite_location";

        private List<ConfigError> validateRoute(@Nullable Protocol protocol, @Nullable Boolean rewriteLocation) {
            RouteConfig route = RouteConfig.builder()
                    .id("rewritten")
                    .protocol(protocol)
                    .match(match("/rewritten", HttpMethod.GET))
                    .upstream(UpstreamConfig.builder().rewriteLocation(rewriteLocation).build())
                    .build();
            return validator.validate(validGateway().build(),
                    List.of(endpoint("orders", "ORDERS", List.of(HttpMethod.GET), route)), topologyWith("ORDERS"));
        }

        private static boolean hasRewriteLocationError(List<ConfigError> errors) {
            return errors.stream().anyMatch(error -> error.message().contains(REWRITE_LOCATION));
        }

        /**
         * Whether {@code rewrite_location} is refused for this protocol — the expectation half of the
         * matrix below, as an EXHAUSTIVE switch rather than a hand-written name list. A protocol added
         * to {@link Protocol} fails to compile here until someone decides which side it belongs on,
         * where a {@code names = {...}} selector would simply have stopped covering it and left the
         * validator free to drift with every test still green.
         */
        private static boolean rewriteLocationRefused(Protocol protocol) {
            return switch (protocol) {
                // The rewrite reads an HTTP Location response header, which neither carries.
                case GRPC, WEBSOCKET -> true;
                case HTTP, GRAPHQL -> false;
            };
        }

        @ParameterizedTest
        @EnumSource(Protocol.class)
        @DisplayName("Should refuse rewrite_location: true on exactly the protocols that cannot carry a Location")
        void shouldRefuseRewriteLocationPerProtocol(Protocol protocol) {
            List<ConfigError> errors = validateRoute(protocol, true);

            if (rewriteLocationRefused(protocol)) {
                assertHasError(errors, "/endpoint/routes", "route 'rewritten' declares " + REWRITE_LOCATION);
                assertHasError(errors, "/endpoint/routes",
                        "its protocol is '" + protocol.name().toLowerCase(Locale.ROOT) + "'");
            } else {
                assertFalse(hasRewriteLocationError(errors),
                        () -> protocol + " routes support the Location rewrite, got: " + errors);
            }
        }

        @Test
        @DisplayName("Should accept rewrite_location: true on a route that omits protocol (http)")
        void shouldAcceptWhenProtocolOmitted() {
            List<ConfigError> errors = validateRoute(null, true);

            assertTrue(errors.isEmpty(), () -> "an omitted protocol means http, got: " + errors);
        }

        @ParameterizedTest
        @EnumSource(Protocol.class)
        @DisplayName("Should not refuse any protocol's route whose rewrite_location is false or absent")
        void shouldNotRefuseWhenToggleOff(Protocol protocol) {
            assertAll("only an enabled toggle is refused",
                    () -> assertFalse(hasRewriteLocationError(validateRoute(protocol, false)),
                            "a declared false is not refused"),
                    () -> assertFalse(hasRewriteLocationError(validateRoute(protocol, null)),
                            "an absent key is not refused"));
        }
    }

    /**
     * A WebSocket route whose effective auth is {@code bearer} or {@code session} declares a non-empty
     * {@code allowed_origins}; a {@code require: none} route may omit it. Both authenticated postures
     * run through the same cases, so the requirement cannot hold for one and lapse for the other.
     */
    @Nested
    @DisplayName("websocket allowed_origins — required on a bearer and on a session route")
    class WebSocketOriginAllowlist {

        private static final String ROUTES_POINTER = "/endpoint/routes";
        private static final String ALLOWLIST_KEY = "allowed_origins";
        private static final String ORIGIN = "https://app.example.com";

        /** The violations of one WebSocket route that name the allowlist key. */
        private List<ConfigError> allowlistErrors(Require require, String routeId, @Nullable WebSocketConfig websocket) {
            RouteConfig route = RouteConfig.builder()
                    .id(routeId)
                    .protocol(Protocol.WEBSOCKET)
                    .match(match("/" + routeId, HttpMethod.GET))
                    .websocket(websocket)
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("live").enabled(true).baseUrl("LIVE")
                    .auth(auth(require))
                    .routes(List.of(route))
                    .build();
            return validator.validate(validGateway().build(), List.of(endpoint), topologyWith("LIVE")).stream()
                    .filter(error -> ROUTES_POINTER.equals(error.pointer()) && error.message().contains(ALLOWLIST_KEY))
                    .toList();
        }

        private static WebSocketConfig allowing(String... origins) {
            return WebSocketConfig.builder().allowedOrigins(List.of(origins)).build();
        }

        private void assertRefusedForMissingAllowlist(Require require, @Nullable WebSocketConfig websocket) {
            String routeId = Generators.letterStrings(4, 12).next().toLowerCase(Locale.ROOT);
            String posture = require.name().toLowerCase(Locale.ROOT);

            List<ConfigError> errors = allowlistErrors(require, routeId, websocket);

            assertEquals(1, errors.size(), () -> "exactly one allowlist refusal, got: " + errors);
            ConfigError refusal = errors.getFirst();
            assertAll("the refusal names the file, the route, the key and the route's own posture",
                    () -> assertEquals("endpoints/live.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains("websocket route '" + routeId + "'"),
                            refusal::message),
                    () -> assertTrue(refusal.message().contains("effective auth '" + posture + "'"),
                            refusal::message),
                    () -> assertTrue(refusal.message().contains("non-empty " + ALLOWLIST_KEY), refusal::message));
        }

        @ParameterizedTest(name = "require: {0}")
        @EnumSource(value = Require.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Should refuse an authenticated WebSocket route that declares no websocket block")
        void shouldRefuseAbsentAllowlist(Require require) {
            assertRefusedForMissingAllowlist(require, null);
        }

        @ParameterizedTest(name = "require: {0}")
        @EnumSource(value = Require.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Should refuse an authenticated WebSocket route whose allowed_origins is empty")
        void shouldRefuseEmptyAllowlist(Require require) {
            assertRefusedForMissingAllowlist(require, allowing());
        }

        @ParameterizedTest(name = "require: {0}")
        @EnumSource(value = Require.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Should accept an authenticated WebSocket route that declares an exact origin")
        void shouldAcceptPopulatedAllowlist(Require require) {
            List<ConfigError> errors = allowlistErrors(require, "socket", allowing(ORIGIN));

            assertEquals(List.of(), errors);
        }

        /**
         * The matched control: the same route with no allowlist is admitted once its posture is
         * {@code require: none}, so the refusals above are about the authenticated postures and not
         * about every WebSocket route.
         */
        @Test
        @DisplayName("Should accept a require: none WebSocket route without an allowlist (matched control)")
        void shouldAcceptUnauthenticatedRouteWithoutAllowlist() {
            assertAll("a require: none route may omit the allowlist",
                    () -> assertEquals(List.of(), allowlistErrors(Require.NONE, "socket", null),
                            "no websocket block"),
                    () -> assertEquals(List.of(), allowlistErrors(Require.NONE, "socket", allowing()),
                            "an empty allowed_origins"));
        }

        @ParameterizedTest(name = "require: {0}")
        @EnumSource(Require.class)
        @DisplayName("Should hold a declared origin to the exact-match rule whatever the route's posture")
        void shouldRefuseWildcardOriginOnEveryPosture(Require require) {
            List<ConfigError> errors = allowlistErrors(require, "socket", allowing("https://*.example.com"));

            assertEquals(1, errors.size(), () -> "exactly one per-entry refusal, got: " + errors);
            assertTrue(errors.getFirst().message().contains("wildcards are not permitted"),
                    () -> errors.getFirst().message());
        }
    }
}
