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

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static de.cuioss.sheriff.gateway.testsupport.ValidationAsserts.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
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

    @Nested
    @DisplayName("Conditional base_url — mandatory exactly with a proxy route (AS-4)")
    class ConditionalBaseUrl {

        private static final String BASE_URL_POINTER = "/endpoint/base_url";

        @Test
        @DisplayName("Should refuse an endpoint carrying a proxy route but no base_url")
        void shouldRefuseProxyRouteEndpointWithoutBaseUrl() {
            EndpointConfig endpoint = endpoint("orders", null, List.of(), routeConfig("orders-read", HttpMethod.GET));

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
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("orders-read", HttpMethod.GET));

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
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/version", "unsupported config version");
        }

        @Test
        @DisplayName("Should reject a duplicate endpoint id across endpoint files")
        void shouldRejectDuplicateEndpointId() {
            EndpointConfig first = endpoint("orders", "ORDERS", List.of(), routeConfig("r1", HttpMethod.GET));
            EndpointConfig second = endpoint("orders", "USERS", List.of(), routeConfig("r2", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(first, second),
                    topologyWith("ORDERS", "USERS"));

            assertHasError(errors, "/endpoint/id", "duplicate endpoint id: orders");
        }

        @Test
        @DisplayName("Should reject a duplicate route id across endpoint files")
        void shouldRejectDuplicateRouteId() {
            EndpointConfig first = endpoint("ep-a", "ORDERS", List.of(), routeConfig("shared", HttpMethod.GET));
            EndpointConfig second = endpoint("ep-b", "USERS", List.of(), routeConfig("shared", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(first, second),
                    topologyWith("ORDERS", "USERS"));

            assertHasError(errors, "/endpoint/routes", "duplicate route id: shared");
        }

        @Test
        @DisplayName("Should reject an enabled endpoint whose base_url alias does not resolve")
        void shouldRejectUnresolvedAliasForEnabledEndpoint() {
            EndpointConfig endpoint = endpoint("orders", "MISSING", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/base_url", "unresolved topology alias: MISSING");
        }
    }
}
