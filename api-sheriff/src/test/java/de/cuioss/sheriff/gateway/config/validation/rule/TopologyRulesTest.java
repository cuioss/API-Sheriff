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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
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

/**
 * Tests for the topology rules the {@link ConfigValidator} runs through {@link TopologyRules}: the
 * supported configuration version, the endpoint-id and route-id uniqueness rules, and the conditional
 * {@code base_url} rule that requires a resolvable alias exactly when an endpoint carries a proxy route.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — topology rules")
class TopologyRulesTest {

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

    private static AnchorConfig matrixAnchor(String name, String prefix, AnchorType type, AccessLevel access,
            @Nullable Require require) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(type)
                .access(access)
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

    private static RouteConfig assetRoute(String id, String prefix, String anchorName, AssetConfig asset,
            HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
                .asset(asset)
                .build();
    }

    private static AssetConfig directoryAsset(String root) {
        return AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                .directory(root).build();
    }

    @Nested
    @DisplayName("Conditional base_url — mandatory exactly with a proxy route (AS-4)")
    class ConditionalBaseUrl {

        private static final String BASE_URL_POINTER = "/endpoint/base_url";

        @Test
        @DisplayName("Should refuse an endpoint carrying a proxy route but no base_url")
        void shouldRefuseProxyRouteEndpointWithoutBaseUrl() {
            EndpointConfig endpoint = endpoint("orders", null, List.of(), route("orders-read", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint), topologyWith());

            assertHasError(errors, BASE_URL_POINTER, "endpoint 'orders' declares proxy route(s) but no base_url");
        }

        @Test
        @DisplayName("Should accept an asset-only endpoint that declares no base_url")
        void shouldAcceptAssetOnlyEndpointWithoutBaseUrl() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", null, "assets",
                    auth(Require.NONE),
                    assetRoute("bundle", "/assets", "assets", directoryAsset("/srv/assets"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith());

            assertTrue(errors.isEmpty(), () -> "an endpoint without a proxy route needs no base_url, got: " + errors);
        }

        @Test
        @DisplayName("Should still refuse a declared base_url alias that does not resolve, proxy route or not")
        void shouldRefuseUnresolvedDeclaredAliasWithoutProxyRoutes() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "MISSING", "assets",
                    auth(Require.NONE),
                    assetRoute("bundle", "/assets", "assets", directoryAsset("/srv/assets"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith());

            assertHasError(errors, BASE_URL_POINTER, "unresolved topology alias: MISSING");
        }

        @Test
        @DisplayName("Should report no base_url violation for a proxy route whose declared alias resolves")
        void shouldAcceptProxyRouteWithResolvableBaseUrl() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-read", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(error -> BASE_URL_POINTER.equals(error.pointer())),
                    () -> "a resolvable base_url on a proxy-route endpoint is valid, got: " + errors);
        }
    }

    @Nested
    @DisplayName("Each enforced rule")
    class RuleViolations {

        @Test
        @DisplayName("Should reject an unsupported config version")
        void shouldRejectUnsupportedVersion() {
            GatewayConfig gateway = GatewayConfig.builder().version(2).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/version", "unsupported config version");
        }

        @Test
        @DisplayName("Should reject a duplicate endpoint id across endpoint files")
        void shouldRejectDuplicateEndpointId() {
            EndpointConfig first = endpoint("orders", "ORDERS", List.of(), route("r1", HttpMethod.GET));
            EndpointConfig second = endpoint("orders", "USERS", List.of(), route("r2", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(first, second),
                    topologyWith("ORDERS", "USERS"));

            assertHasError(errors, "/endpoint/id", "duplicate endpoint id: orders");
        }

        @Test
        @DisplayName("Should reject a duplicate route id across endpoint files")
        void shouldRejectDuplicateRouteId() {
            EndpointConfig first = endpoint("ep-a", "ORDERS", List.of(), route("shared", HttpMethod.GET));
            EndpointConfig second = endpoint("ep-b", "USERS", List.of(), route("shared", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(first, second),
                    topologyWith("ORDERS", "USERS"));

            assertHasError(errors, "/endpoint/routes", "duplicate route id: shared");
        }

        @Test
        @DisplayName("Should reject an enabled endpoint whose base_url alias does not resolve")
        void shouldRejectUnresolvedAliasForEnabledEndpoint() {
            EndpointConfig endpoint = endpoint("orders", "MISSING", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/base_url", "unresolved topology alias: MISSING");
        }
    }
}
