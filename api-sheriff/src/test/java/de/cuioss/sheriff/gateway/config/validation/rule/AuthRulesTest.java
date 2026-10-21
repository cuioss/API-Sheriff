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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the auth rules the {@link ConfigValidator} runs through {@link AuthRules}: the ADR-0007
 * per-route auth resolvability and anchor-aware effective-auth completeness check, the requirement
 * that an effective {@code bearer} posture has a {@code token_validation} issuer and an effective
 * {@code session} posture an {@code oidc} block, and the refusal of {@code endpoint.scopes} on an
 * endpoint whose every route resolves {@code require: none}. The {@code auth.session_fallback}
 * refusal is tested in {@code AuthRulesSessionFallbackTest}.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — auth rules")
class AuthRulesTest {

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

    private static void assertHasError(List<ConfigError> errors, String pointerContains, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(e -> e.pointer().contains(pointerContains) && e.message().contains(messageContains)),
                () -> "expected an error whose pointer contains '" + pointerContains + "' and message contains '"
                        + messageContains + "', but got: " + errors);
    }

    private static AnchorConfig anchor(String name, String prefix, @Nullable Require require) {
        // The ADR-0007 anchor rules (prefix disjointness, namespace membership, auth floor) are
        // orthogonal to the ADR-0013 access->auth matrix, so these fixtures stay matrix-consistent
        // by construction: an anchor with no auth floor is access: public (public + no auth block is
        // matrix-clean), while an anchor carrying a floor is access: authenticated (a non-'none'
        // floor is what access: authenticated requires).
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(AnchorType.PROXY)
                .access(require == null ? AccessLevel.PUBLIC : AccessLevel.AUTHENTICATED)
                .auth(require == null ? null : auth(require))
                .build();
    }

    private static GatewayConfig gatewayWithAnchors(Map<String, AnchorConfig> anchors) {
        return validGateway().anchors(anchors).build();
    }

    private static EndpointConfig anchoredEndpoint(String id, String alias, String anchorName,
            @Nullable AuthConfig auth, RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .anchor(anchorName)
                .auth(auth)
                .routes(List.of(routes))
                .build();
    }

    private static RouteConfig anchoredRoute(String id, String prefix, String anchorName, HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
                .build();
    }

    @Nested
    @DisplayName("Each enforced rule")
    class RuleViolations {

        @Test
        @DisplayName("Should reject effective auth 'bearer' without a token_validation issuer")
        void shouldRejectBearerWithoutIssuer() {
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.BEARER))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/token_validation", "requires token_validation with at least one issuer");
        }

        @Test
        @DisplayName("Should reject effective auth 'session' without an oidc block")
        void shouldRejectSessionWithoutOidc() {
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.SESSION))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/oidc", "requires an oidc block");
        }

        @Test
        @DisplayName("Should accept effective auth 'bearer' when a token_validation issuer is present")
        void shouldAcceptBearerWithIssuer() {
            GatewayConfig gateway = validGateway()
                    .tokenValidation(new TokenValidationConfig(
                            List.of(IssuerConfig.builder().name("primary").issuer("https://idp.example").build())))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.BEARER))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations for a valid bearer config, got: " + errors);
        }
    }

    @Nested
    @DisplayName("Per-route auth resolvability and the anchor-aware effective-auth check (ADR-0007)")
    class RouteAuthResolution {

        @Test
        @DisplayName("Rule 6: Should reject a route that resolves no auth from route, endpoint, or anchor")
        void shouldRejectRouteWithoutAnyAuthSource() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null,
                    anchoredRoute("r", "/api/x", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "route 'r' has no resolvable auth");
        }

        @Test
        @DisplayName("Rule 6: Should accept an endpoint with no auth block when every route supplies its own auth")
        void shouldAcceptEndpointWhereEveryRouteSuppliesOwnAuth() {
            GatewayConfig gateway = validGateway().build();
            RouteConfig selfAuth = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .auth(auth(Require.NONE)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", null, null, selfAuth);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    () -> "an endpoint whose every route declares its own auth must not be rejected, got: " + errors);
        }

        @Test
        @DisplayName("Rule 6: Should catch a route overriding to an auth-less anchor that the endpoint anchor would mask")
        void shouldCatchRouteAnchorOverrideToAuthLessAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secured", anchor("secured", "/api", Require.BEARER),
                    "open", anchor("open", "/open", null)));
            RouteConfig override = RouteConfig.builder().id("r").anchor("open")
                    .match(match("/open/x", HttpMethod.GET)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "secured", null, override);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            // The endpoint-level anchor 'secured' provides auth, so a per-endpoint check would pass; the route
            // overrides to the auth-less 'open' anchor and declares no own auth, so a per-route check must reject it.
            assertHasError(errors, "/endpoint/routes", "route 'r' has no resolvable auth");
            // Absent this rule the same config escapes validate() and explodes as a RouteTableException during
            // route-table assembly (ADR-0007); confirm that failure mode is now caught by the all-violations pass.
            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> new RouteTableBuilder().build(gateway, List.of(endpoint), topologyWith("ORDERS")));
        }

        @Test
        @DisplayName("Rule 7: Should carry an anchor-provided bearer posture into the effective-auth completeness check")
        void shouldPropagateAnchorAuthIntoEffectiveAuthCheck() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", Require.BEARER)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null,
                    anchoredRoute("r", "/api/x", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/token_validation", "requires token_validation with at least one issuer");
        }
    }

    @Nested
    @DisplayName("endpoint.scopes must act on at least one authenticated route")
    class EndpointScopesNeedAuthentication {

        private static final String SCOPES_MESSAGE = "declares scopes but every one of its routes resolves require: none";

        private EndpointConfig scopedEndpoint(List<String> scopes, RouteConfig... routes) {
            return EndpointConfig.builder().id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.NONE)).scopes(scopes).routes(List.of(routes)).build();
        }

        @Test
        @DisplayName("Should refuse scopes on an endpoint whose routes all resolve require: none")
        void shouldRefuseScopesOnUnauthenticatedEndpoint() {
            List<ConfigError> errors = validator.validate(validGateway().build(),
                    List.of(scopedEndpoint(List.of("orders.read"), route("a", HttpMethod.GET),
                            route("b", HttpMethod.GET))),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/scopes", SCOPES_MESSAGE);
            assertTrue(errors.stream().anyMatch(error -> "endpoints/orders.yaml".equals(error.file())),
                    () -> "the refusal names the endpoint file, got: " + errors);
        }

        @Test
        @DisplayName("Should accept scopes when one route overrides to an authenticated posture")
        void shouldAcceptScopesWithOneAuthenticatedRoute() {
            RouteConfig secured = RouteConfig.builder().id("secured").match(match("/secured", HttpMethod.GET))
                    .auth(auth(Require.BEARER)).build();

            List<ConfigError> errors = validator.validate(validGateway().build(),
                    List.of(scopedEndpoint(List.of("orders.read"), route("open", HttpMethod.GET), secured)),
                    topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(error -> error.message().contains(SCOPES_MESSAGE)),
                    () -> "one authenticated route gives the scopes a consumer, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an unauthenticated endpoint that declares no scopes")
        void shouldAcceptUnscopedUnauthenticatedEndpoint() {
            List<ConfigError> errors = validator.validate(validGateway().build(),
                    List.of(scopedEndpoint(List.of(), route("a", HttpMethod.GET))), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(error -> error.message().contains(SCOPES_MESSAGE)),
                    () -> "an empty scopes list is not a declaration, got: " + errors);
        }
    }
}
