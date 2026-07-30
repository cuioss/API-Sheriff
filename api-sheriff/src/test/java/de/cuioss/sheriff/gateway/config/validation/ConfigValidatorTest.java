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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigValidator}: one negative case per enforced cross-cutting
 * rule (including the seven ADR-0007 anchor / effective-auth rules), the D5
 * boot-time hardening rules (real-CIDR {@code trusted_proxies} parsing with
 * full-space rejection and broad-prefix boot-WARN, and the same-prefix
 * route-disjointness rule moved here from {@code RouteTableBuilder}), the structural
 * {@code TRACE}/{@code CONNECT} rejection, the fail-closed ADR-0024 {@code profile: none}
 * refusal on effectively-authenticated and BFF routes, and the single-pass aggregation
 * contract that reports every violation together rather than stopping at the first.
 */
@EnableGeneratorController
@EnableTestLogger
class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static GatewayConfig.GatewayConfigBuilder validGateway() {
        return GatewayConfig.builder().version(1);
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

    private static RouteConfig routeWithHost(String id, String host, HttpMethod... methods) {
        MatchConfig match = MatchConfig.builder()
                .pathPrefix("/" + id)
                .methods(List.of(methods))
                .host(Optional.of(host))
                .build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static GatewayConfig gatewayWithPassthrough(Map<String, String> passthroughSni) {
        return validGateway()
                .tls(Optional.of(TlsConfig.builder().passthroughSni(passthroughSni).build()))
                .build();
    }

    private static EndpointConfig endpoint(String id, String alias, List<HttpMethod> allowedMethods,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .auth(Optional.of(new AuthConfig("none", List.of())))
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

    private static AnchorConfig anchor(String name, String prefix, String require) {
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
                .auth(require == null ? Optional.empty() : Optional.of(new AuthConfig(require, List.of())))
                .build();
    }

    private static AnchorConfig matrixAnchor(String name, String prefix, AnchorType type, AccessLevel access,
            String require) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(type)
                .access(access)
                .auth(require == null ? Optional.empty() : Optional.of(new AuthConfig(require, List.of())))
                .build();
    }

    private static GatewayConfig gatewayWithAnchorAndIssuer(AnchorConfig anchorConfig) {
        return validGateway()
                .anchors(Map.of(anchorConfig.name(), anchorConfig))
                .tokenValidation(Optional.of(new TokenValidationConfig(List.of(
                        IssuerConfig.builder().name("main").issuer("https://idp.example").build()))))
                .build();
    }

    private static GatewayConfig gatewayWithAnchors(Map<String, AnchorConfig> anchors) {
        return validGateway().anchors(anchors).build();
    }

    private static EndpointConfig anchoredEndpoint(String id, String alias, String anchorName, Optional<AuthConfig> auth,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .anchor(anchorName == null ? Optional.empty() : Optional.of(anchorName))
                .auth(auth)
                .routes(List.of(routes))
                .build();
    }

    private static RouteConfig anchoredRoute(String id, String prefix, String anchorName, HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName == null ? Optional.empty() : Optional.of(anchorName))
                .match(match(prefix, methods))
                .build();
    }

    private static RouteConfig assetRoute(String id, String prefix, String anchorName, AssetConfig asset,
            HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName == null ? Optional.empty() : Optional.of(anchorName))
                .match(match(prefix, methods))
                .asset(Optional.of(asset))
                .build();
    }

    private static AssetConfig directoryAsset(String root) {
        return AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                .directory(root == null ? Optional.empty() : Optional.of(root)).build();
    }

    private static AssetConfig upstreamAsset(String alias) {
        return AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                .upstream(alias == null ? Optional.empty() : Optional.of(alias)).build();
    }

    @Nested
    @DisplayName("Terminal-action / anchor-type consistency (ADR-0014)")
    class TerminalActionConsistency {

        @Test
        @DisplayName("Should accept a directory asset route under an asset anchor")
        void shouldAcceptDirectoryAssetUnderAssetAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    Optional.of(new AuthConfig("none", List.of())),
                    assetRoute("bundle", "/assets", "assets", directoryAsset("/srv/assets"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an upstream asset route whose alias resolves")
        void shouldAcceptUpstreamAssetWithResolvableAlias() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    Optional.of(new AuthConfig("none", List.of())),
                    assetRoute("cdn", "/assets", "assets", upstreamAsset("SECONDARY"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB", "SECONDARY"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should reject an asset-type anchor route that declares no asset terminal action")
        void shouldRejectAssetAnchorWithoutAssetBlock() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    Optional.of(new AuthConfig("none", List.of())),
                    anchoredRoute("noasset", "/assets", "assets", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB"));

            assertHasError(errors, "/endpoint/routes", "declares no asset terminal action");
        }

        @Test
        @DisplayName("Should reject an asset block on a route under a proxy anchor")
        void shouldRejectAssetBlockUnderProxyAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api",
                    matrixAnchor("api", "/api", AnchorType.PROXY, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("api-ep", "API", "api",
                    Optional.of(new AuthConfig("none", List.of())),
                    assetRoute("mixed", "/api", "api", directoryAsset("/srv/assets"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertHasError(errors, "/endpoint/routes", "requires an asset-type anchor");
        }

        @Test
        @DisplayName("Should reject an asset block on an unanchored route")
        void shouldRejectAssetBlockOnUnanchoredRoute() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("plain").enabled(true).baseUrl("PLAIN")
                    .auth(Optional.of(new AuthConfig("none", List.of())))
                    .routes(List.of(assetRoute("loose", "/loose", null, directoryAsset("/srv/assets"), HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("PLAIN"));

            assertHasError(errors, "/endpoint/routes", "the route is unanchored");
        }

        @Test
        @DisplayName("Should reject an upstream asset source whose topology alias does not resolve")
        void shouldRejectUnresolvableUpstreamAssetAlias() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    Optional.of(new AuthConfig("none", List.of())),
                    assetRoute("cdn", "/assets", "assets", upstreamAsset("MISSING"), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB"));

            assertHasError(errors, "/endpoint/routes", "does not resolve in the topology");
        }

        @Test
        @DisplayName("Should reject a directory asset source that declares no directory root")
        void shouldRejectDirectoryAssetWithoutRoot() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    Optional.of(new AuthConfig("none", List.of())),
                    assetRoute("bundle", "/assets", "assets", directoryAsset(null), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB"));

            assertHasError(errors, "/endpoint/routes", "no directory root");
        }
    }

    @Nested
    @DisplayName("A well-formed configuration")
    class ValidConfiguration {

        @Test
        @DisplayName("Should report no violations")
        void shouldReportNoViolations() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-list", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report no violations for a route host that no passthrough_sni host claims")
        void shouldAcceptRouteHostWithoutPassthroughCollision() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(),
                    routeWithHost("orders-list", "api.example.com", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report no violations for a passthrough_sni alias resolving without a base path")
        void shouldAcceptResolvablePassthroughAlias() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-list", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report no violations for a passthrough_sni alias resolving to a bare '/' base path")
        void shouldAcceptPassthroughAliasWithTrailingSlashBasePath() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-list", HttpMethod.GET));
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    "ORDERS", new ResolvedUpstream("https", "orders.internal", 443, ""),
                    "SECURE", new ResolvedUpstream("https", "secure.internal", 443, "/")));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topology);

            assertTrue(errors.isEmpty(),
                    () -> "expected no violations for a topology URL ending in a bare '/', got: " + errors);
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
        @DisplayName("Should reject an edge_hardening admission_cap below 1")
        void shouldRejectAdmissionCapBelowOne() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(Optional.of(new EdgeHardeningConfig(Optional.of(0), Optional.of(1)))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/admission_cap", "admission_cap must be at least 1");
        }

        @Test
        @DisplayName("Should reject an edge_hardening websocket_relay_cap below 1")
        void shouldRejectWebsocketRelayCapBelowOne() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(Optional.of(new EdgeHardeningConfig(Optional.of(8), Optional.of(0)))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/websocket_relay_cap",
                    "websocket_relay_cap must be at least 1");
        }

        @Test
        @DisplayName("Should reject a websocket_relay_cap exceeding admission_cap, which could never bind")
        void shouldRejectInvertedCapPair() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(Optional.of(new EdgeHardeningConfig(Optional.of(4), Optional.of(16)))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/websocket_relay_cap", "must not exceed admission_cap");
        }

        @Test
        @DisplayName("Should accept a lowered admission_cap with websocket_relay_cap omitted")
        void shouldAcceptLoweredAdmissionCapWithoutRelayCap() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(Optional.of(new EdgeHardeningConfig(Optional.of(64), Optional.empty()))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    "A partially declared edge_hardening block must not self-reject, but got: " + errors);
            assertEquals(16, gateway.edgeHardening().orElseThrow().effectiveWebsocketRelayCap(),
                    "The implicit relay sub-budget stays a quarter of the effective admission cap");
        }

        @Test
        @DisplayName("Should accept a well-formed edge_hardening block")
        void shouldAcceptValidEdgeHardeningBlock() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(Optional.of(new EdgeHardeningConfig(Optional.of(64), Optional.of(8)))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), "A valid admission budget raises no violation, but got: " + errors);
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

        @Test
        @DisplayName("Should reject effective auth 'bearer' without a token_validation issuer")
        void shouldRejectBearerWithoutIssuer() {
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(Optional.of(new AuthConfig("bearer", List.of())))
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
                    .auth(Optional.of(new AuthConfig("session", List.of())))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/oidc", "requires an oidc block");
        }

        @Test
        @DisplayName("Should reject a route matching a method outside the effective allowed_methods")
        void shouldRejectMethodOutsideEffectiveAllowedMethods() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(HttpMethod.GET),
                    route("orders-post", HttpMethod.POST));

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "outside the effective allowed_methods");
        }

        @Test
        @DisplayName("Should accept a millisecond-precision upstream timeout")
        void shouldAcceptMillisecondPrecisionTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(Optional.of(UpstreamConfig.builder().readTimeoutMs(Optional.of(2500)).build()))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(Optional.of(new AuthConfig("none", List.of())))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    () -> "expected no violations for a millisecond-precision timeout, got: " + errors);
        }

        static Stream<Arguments> fullAddressSpaceTrustedProxies() {
            return Stream.of(
                    Arguments.of("a single full-space IPv4 CIDR", List.of("0.0.0.0/0"), "entire IPv4 address space"),
                    Arguments.of("a single full-space IPv6 CIDR", List.of("::/0"), "entire IPv6 address space"),
                    Arguments.of("a complementary /1 IPv4 pair", List.of("0.0.0.0/1", "128.0.0.0/1"),
                            "entire IPv4 address space"),
                    Arguments.of("a complementary /1 IPv6 pair", List.of("::/1", "8000::/1"),
                            "entire IPv6 address space"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("fullAddressSpaceTrustedProxies")
        @DisplayName("Should reject forwarded.trusted_proxies entries that cover the whole address space")
        void shouldRejectFullAddressSpaceTrustedProxies(String label, List<String> trustedProxies,
                String expectedDetail) {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder().trustedProxies(trustedProxies).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", expectedDetail);
        }

        /**
         * Each entry fails CIDR parsing at a different point: no slash at all, a numeric prefix
         * length outside the address width, a prefix length that is not a number, and an address
         * part that is not an IP literal. All four must be reported identically.
         */
        @ParameterizedTest(name = "trusted_proxies entry \"{0}\" is rejected as malformed")
        @ValueSource(strings = {"not-a-cidr", "10.0.0.0/33", "10.0.0.0/abc", "not-an-ip/24"})
        @DisplayName("Should reject a malformed trusted_proxies CIDR entry with file/pointer context")
        void shouldRejectMalformedCidr(String malformedCidr) {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of(malformedCidr)).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "malformed trusted_proxies CIDR: " + malformedCidr);
        }

        @Test
        @DisplayName("Should boot-WARN a very broad but not total CIDR without failing the boot")
        void shouldWarnBroadButNotTotalCidr() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("10.0.0.0/4")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("trusted_proxies")),
                    () -> "a broad-but-not-total CIDR must not fail the boot, got: " + errors);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "very broad address range");
        }

        @Test
        @DisplayName("Should accept tightly scoped IPv4 and IPv6 trusted_proxies CIDRs without warning")
        void shouldAcceptTightlyScopedCidrs() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("10.0.0.0/8", "2001:db8::/32")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("trusted_proxies")),
                    () -> "well-scoped CIDRs must not fail the boot, got: " + errors);
            assertTrue(TestLoggerFactory.getTestHandler()
                            .resolveLogMessagesContaining(TestLogLevel.WARN, "very broad address range").isEmpty(),
                    "tightly scoped CIDRs (10.0.0.0/8, 2001:db8::/32) must not emit a broad-range WARN");
        }

        @Test
        @DisplayName("Should reject two enabled routes sharing a normalized prefix and overlapping on method")
        void shouldRejectNonDisjointSamePrefixRoutes() {
            RouteConfig first = RouteConfig.builder().id("first").match(match("/api", HttpMethod.GET)).build();
            RouteConfig second = RouteConfig.builder().id("second").match(match("/api", HttpMethod.GET)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), first, second);

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "share prefix '/api' and are not disjoint");
        }

        @Test
        @DisplayName("Should collide '/api' with '/api/' in the same-prefix disjointness rule after normalization")
        void shouldCollideTrailingSlashPrefixInDisjointness() {
            RouteConfig first = RouteConfig.builder().id("first").match(match("/api", HttpMethod.GET)).build();
            RouteConfig second = RouteConfig.builder().id("second").match(match("/api/", HttpMethod.GET)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), first, second);

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "are not disjoint");
        }

        @Test
        @DisplayName("Should accept two same-prefix routes made disjoint by method")
        void shouldAcceptSamePrefixRoutesDisjointByMethod() {
            RouteConfig reader = RouteConfig.builder().id("reader").match(match("/api", HttpMethod.GET)).build();
            RouteConfig writer = RouteConfig.builder().id("writer").match(match("/api", HttpMethod.POST)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), reader, writer);

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.message().contains("not disjoint")),
                    () -> "method-disjoint same-prefix routes must not collide, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a wildcard CORS origin combined with allow_credentials")
        void shouldRejectWildcardOriginWithCredentials() {
            GatewayConfig gateway = validGateway()
                    .securityHeaders(Optional.of(SecurityHeadersConfig.builder()
                            .cors(Optional.of(SecurityHeadersConfig.Cors.builder()
                                    .allowedOrigins(List.of("*"))
                                    .allowCredentials(Optional.of(true))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/security_headers/cors", "wildcard origin '*' is not permitted");
        }

        @Test
        @DisplayName("Should accept cookie session mode without an encryption_key (generate-on-startup)")
        void shouldAcceptCookieSessionWithoutEncryptionKey() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("cookie"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "omitting encryption_key selects the generate-on-startup key mode, got: " + errors);
        }

        @Test
        @DisplayName("Should reject previous_key without encryption_key")
        void shouldRejectPreviousKeyWithoutEncryptionKey() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("cookie"))
                                    .previousKey(Optional.of("${SHERIFF_SESSION_KEY_PREVIOUS}"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/previous_key",
                    "cookie session mode with a previous_key requires an encryption_key");
        }

        @Test
        @DisplayName("Should accept cookie session mode with both an encryption_key and a previous_key")
        void shouldAcceptCookieSessionWithRotationKeys() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("cookie"))
                                    .encryptionKey(Optional.of("${SHERIFF_SESSION_KEY}"))
                                    .previousKey(Optional.of("${SHERIFF_SESSION_KEY_PREVIOUS}"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "rotation composes with the passed-key mode, got: " + errors);
        }

        @Test
        @DisplayName("Should reject server session mode without a store")
        void shouldRejectServerSessionWithoutStore() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("server"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
        }

        @Test
        @DisplayName("Should apply the cookie-mode companion rule to a mixed-case mode value")
        void shouldApplyCookieRuleToMixedCaseMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("  CoOkIe "))
                                    .previousKey(Optional.of("${SHERIFF_SESSION_KEY_PREVIOUS}"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/previous_key",
                    "cookie session mode with a previous_key requires an encryption_key");
        }

        @Test
        @DisplayName("Should apply the server-mode companion rule to a mixed-case mode value")
        void shouldApplyServerRuleToMixedCaseMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("SERVER"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
        }

        @Test
        @DisplayName("Should accept effective auth 'bearer' when a token_validation issuer is present")
        void shouldAcceptBearerWithIssuer() {
            GatewayConfig gateway = validGateway()
                    .tokenValidation(Optional.of(new TokenValidationConfig(
                            List.of(IssuerConfig.builder().name("primary").issuer("https://idp.example").build()))))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(Optional.of(new AuthConfig("bearer", List.of())))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations for a valid bearer config, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a route whose match.host collides case-insensitively with a passthrough_sni host")
        void shouldRejectRouteHostCollidingWithPassthroughSni() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(),
                    routeWithHost("orders-list", "SECURE.EXAMPLE.COM", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertHasError(errors, "/tls/passthrough_sni",
                    "route 'orders-list' matches host 'secure.example.com'");
        }

        @Test
        @DisplayName("Should reject a passthrough_sni alias that does not resolve")
        void shouldRejectUnresolvedPassthroughAlias() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "MISSING"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-list", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/tls/passthrough_sni",
                    "unresolved topology alias 'MISSING' referenced by passthrough_sni host 'secure.example.com'");
        }

        @Test
        @DisplayName("Should reject a passthrough_sni alias resolving to an upstream carrying a base path")
        void shouldRejectPassthroughAliasWithBasePath() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("orders-list", HttpMethod.GET));
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    "ORDERS", new ResolvedUpstream("https", "orders.internal", 443, ""),
                    "SECURE", new ResolvedUpstream("https", "secure.internal", 443, "/api")));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topology);

            assertHasError(errors, "/tls/passthrough_sni", "must resolve to an origin without a base path");
        }
    }

    @Nested
    @DisplayName("The seven anchor / effective-auth rules (ADR-0007)")
    class AnchorRules {

        @Test
        @DisplayName("Rule 1: Should reject anchor prefixes where one contains another")
        void shouldRejectOverlappingAnchorPrefixes() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "api", anchor("api", "/api", null),
                    "apiv1", anchor("apiv1", "/api/v1", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors", "pairwise disjoint");
        }

        @Test
        @DisplayName("Rule 2: Should reject a reference to an undefined anchor")
        void shouldRejectUndefinedAnchorReference() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "ghost",
                    Optional.of(new AuthConfig("none", List.of())),
                    anchoredRoute("r", "/other", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/anchor", "references undefined anchor 'ghost'");
        }

        @Test
        @DisplayName("Rule 3: Should reject a route whose path lies outside its declared anchor namespace")
        void shouldRejectRoutePathOutsideDeclaredAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api",
                    Optional.of(new AuthConfig("none", List.of())),
                    anchoredRoute("r", "/billing", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Rule 4: Should reject an undeclared squatter route inside an anchor namespace")
        void shouldRejectUndeclaredSquatter() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", null,
                    Optional.of(new AuthConfig("none", List.of())),
                    anchoredRoute("r", "/api/secret", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "does not declare it");
        }

        @Test
        @DisplayName("Rule 5: Should reject an effective 'none' auth that weakens a non-none anchor floor")
        void shouldRejectWeakenedAuthFloor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", "bearer")));
            RouteConfig weakening = RouteConfig.builder().id("r").anchor(Optional.of("api"))
                    .match(match("/api/x", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("none", List.of()))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", Optional.empty(), weakening);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "weakens the anchor 'api' floor 'bearer'");
        }

        @Test
        @DisplayName("Rule 6: Should reject a route that resolves no auth from route, endpoint, or anchor")
        void shouldRejectRouteWithoutAnyAuthSource() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", Optional.empty(),
                    anchoredRoute("r", "/api/x", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "route 'r' has no resolvable auth");
        }

        @Test
        @DisplayName("Rule 6: Should accept an endpoint with no auth block when every route supplies its own auth")
        void shouldAcceptEndpointWhereEveryRouteSuppliesOwnAuth() {
            GatewayConfig gateway = validGateway().build();
            RouteConfig selfAuth = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("none", List.of()))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", null, Optional.empty(), selfAuth);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    () -> "an endpoint whose every route declares its own auth must not be rejected, got: " + errors);
        }

        @Test
        @DisplayName("Rule 6: Should catch a route overriding to an auth-less anchor that the endpoint anchor would mask")
        void shouldCatchRouteAnchorOverrideToAuthLessAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secured", anchor("secured", "/api", "bearer"),
                    "open", anchor("open", "/open", null)));
            RouteConfig override = RouteConfig.builder().id("r").anchor(Optional.of("open"))
                    .match(match("/open/x", HttpMethod.GET)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "secured", Optional.empty(), override);

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
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", "bearer")));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", Optional.empty(),
                    anchoredRoute("r", "/api/x", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/token_validation", "requires token_validation with at least one issuer");
        }

        @Test
        @DisplayName("Should accept a well-formed anchored configuration")
        void shouldAcceptValidAnchoredConfig() {
            GatewayConfig gateway = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", "bearer")))
                    .tokenValidation(Optional.of(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build()))))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("api-ep", "API", "api", Optional.empty(),
                    anchoredRoute("r", "/api/orders", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "a valid anchored config should have no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report multiple anchor violations together in one pass")
        void shouldAggregateAnchorViolations() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "api", anchor("api", "/api", null),
                    "apiv1", anchor("apiv1", "/api/v1", null)));
            EndpointConfig squatter = anchoredEndpoint("s", "S", null,
                    Optional.of(new AuthConfig("none", List.of())),
                    anchoredRoute("sr", "/api/secret", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(squatter), topologyWith("S"));

            assertAll("both the disjointness and squatter violations surface together",
                    () -> assertTrue(errors.size() >= 2, () -> "expected at least two violations, got: " + errors),
                    () -> assertHasError(errors, "/anchors", "pairwise disjoint"),
                    () -> assertHasError(errors, "/endpoint/routes", "does not declare it"));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("A nested anchor prefix is never disjoint from its parent for any generated segment")
        void shouldRejectNestedAnchorPrefixesForAnyGeneratedSegment(String segment) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "outer", anchor("outer", "/" + segment, null),
                    "inner", anchor("inner", "/" + segment + "/sub", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors", "pairwise disjoint");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Sibling anchor prefixes sharing a leading substring stay disjoint for any generated segment")
        void shouldAcceptDisjointSiblingAnchorsForAnyGeneratedSegment(String segment) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "alpha", anchor("alpha", "/" + segment + "-a", null),
                    "beta", anchor("beta", "/" + segment + "-b", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "sibling anchors sharing only a leading substring must stay disjoint, got: " + errors);
        }
    }

    @Nested
    @DisplayName("gRPC anchor-namespace containment exemption (ADR-0007)")
    class GrpcNamespaceExemption {

        private static final String ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.Echo";
        private static final String SECURE_ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.SecureEcho";

        private static RouteConfig grpcRoute(String id, String prefix, String anchorName, Optional<AuthConfig> auth) {
            return RouteConfig.builder()
                    .id(id)
                    .protocol(Optional.of(Protocol.GRPC))
                    .anchor(anchorName == null ? Optional.empty() : Optional.of(anchorName))
                    .match(match(prefix, HttpMethod.POST))
                    .auth(auth)
                    .build();
        }

        @Test
        @DisplayName("Rule 3 exemption: a gRPC route on a bare service path outside its declared anchor is accepted")
        void shouldExemptGrpcRouteFromDeclaredAnchorContainment() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc",
                    Optional.of(new AuthConfig("none", List.of())),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "a gRPC route on a service-rooted path outside its anchor namespace must not be rejected, got: "
                            + errors);
        }

        @Test
        @DisplayName("Rule 3/4 exemption: two gRPC routes under one anchor on bare service paths boot cleanly (native IT scenario)")
        void shouldAcceptTwoGrpcRoutesUnderOneAnchorOnBareServicePaths() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc",
                    Optional.of(new AuthConfig("none", List.of())),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", Optional.empty()),
                    grpcRoute("grpc-bearer", SECURE_ECHO_PATH, "grpc", Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "the two gRPC routes that failed the native IT boot must now validate cleanly, got: " + errors);
        }

        @Test
        @DisplayName("Rule 4 exemption: a gRPC route that would squat inside a catch-all anchor namespace is accepted")
        void shouldExemptGrpcRouteFromUndeclaredSquatterRule() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("root", anchor("root", "/", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", null,
                    Optional.of(new AuthConfig("none", List.of())),
                    grpcRoute("grpc-echo", ECHO_PATH, null, Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "a gRPC route inside a catch-all anchor namespace must not be rejected as a squatter, got: "
                            + errors);
        }

        @Test
        @DisplayName("Containment stays enforced for a websocket route outside its declared anchor namespace")
        void shouldStillEnforceContainmentForNonGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            RouteConfig websocket = RouteConfig.builder().id("ws").anchor(Optional.of("api"))
                    .protocol(Optional.of(Protocol.WEBSOCKET))
                    .match(match("/billing", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("none", List.of()))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", Optional.empty(), websocket);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Auth floor stays enforced for a gRPC route: effective 'none' still weakens a non-none anchor floor")
        void shouldStillEnforceAuthFloorForGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", "bearer")));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc", Optional.empty(),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", Optional.of(new AuthConfig("none", List.of()))));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertHasError(errors, "/endpoint/routes", "weakens the anchor 'grpc' floor 'bearer'");
        }
    }

    @Nested
    @DisplayName("The fail-closed access→auth matrix (ADR-0013)")
    class AccessAuthMatrix {

        @Test
        @DisplayName("Rule bff→authenticated: Should reject a type 'bff' anchor that is not access: authenticated")
        void shouldRejectBffAnchorThatIsNotAuthenticated() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "portal", matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.PUBLIC, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/portal", "is type 'bff' and must declare access: authenticated");
        }

        @ParameterizedTest
        @EnumSource(value = AnchorType.class, names = {"PROXY", "ASSET"})
        @DisplayName("Rule public+auth: Should reject an access: public anchor declaring an auth block for any non-bff type")
        void shouldRejectPublicAnchorDeclaringAuthBlock(AnchorType type) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", type, AccessLevel.PUBLIC, "bearer")));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        @ParameterizedTest
        @ValueSource(strings = {"none", "bearer", "session"})
        @DisplayName("Rule public+auth: Should reject an access: public anchor for every auth-floor value in the vocabulary")
        void shouldRejectPublicAnchorForEveryAuthFloorValue(String require) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        static Stream<Arguments> authenticatedAnchorsWithoutBackedFloor() {
            return Stream.of(
                    Arguments.of("no auth floor at all", null, "declares no non-'none' auth floor"),
                    Arguments.of("an explicit 'none' floor", "none", "declares no non-'none' auth floor"),
                    Arguments.of("a bearer floor with no token_validation issuer", "bearer",
                            "access: authenticated bearer floor requires token_validation with at least one issuer"),
                    Arguments.of("a session floor with no oidc block", "session",
                            "access: authenticated session floor requires an oidc block"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("authenticatedAnchorsWithoutBackedFloor")
        @DisplayName("Rules authenticated→floor and authenticated backing: Should reject an access: authenticated anchor without a backed auth floor")
        void shouldRejectAuthenticatedAnchorWithoutBackedFloor(String label, String require, String expectedDetail) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure", expectedDetail);
        }

        @Test
        @DisplayName("Should accept a type 'bff' anchor that is access: authenticated with a backed bearer floor")
        void shouldAcceptAuthenticatedBffWithBackedBearerFloor() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.AUTHENTICATED, "bearer"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a bff+authenticated anchor with a backed bearer floor must satisfy the matrix, got: " + errors);
        }

        @ParameterizedTest
        @EnumSource(value = AnchorType.class, names = {"PROXY", "ASSET"})
        @DisplayName("Should accept an access: public anchor with no auth block for any non-bff type")
        void shouldAcceptPublicAnchorWithoutAuthBlock(AnchorType type) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", type, AccessLevel.PUBLIC, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a public anchor declaring no auth block must satisfy the matrix, got: " + errors);
        }

        @Test
        @DisplayName("Should report every matrix violation together in one pass")
        void shouldAggregateMatrixViolationsInOnePass() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "portal", matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.PUBLIC, null),
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, "bearer"),
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll("all three matrix violations surface together",
                    () -> assertTrue(errors.size() >= 3, () -> "expected at least three violations, got: " + errors),
                    () -> assertHasError(errors, "/anchors/portal",
                            "is type 'bff' and must declare access: authenticated"),
                    () -> assertHasError(errors, "/anchors/open",
                            "is access: public and must not declare an auth block"),
                    () -> assertHasError(errors, "/anchors/secure", "declares no non-'none' auth floor"));
        }
    }

    @Nested
    @DisplayName("The TRACE / CONNECT verbs")
    class StructuralVerbRejection {

        @Test
        @DisplayName("Should not be representable in the HttpMethod model")
        void shouldNotBeRepresentableInModel() {
            assertAll("forbidden verbs are absent from the enum",
                    () -> assertThrows(IllegalArgumentException.class, () -> HttpMethod.valueOf("TRACE"),
                            "TRACE must not be a representable HttpMethod"),
                    () -> assertThrows(IllegalArgumentException.class, () -> HttpMethod.valueOf("CONNECT"),
                            "CONNECT must not be a representable HttpMethod"));
        }
    }

    @Nested
    @DisplayName("The aggregating validate pass")
    class Aggregation {

        @Test
        @DisplayName("Should report every violation together in a single pass")
        void shouldReportEveryViolationInOnePass() {
            GatewayConfig gateway = GatewayConfig.builder().version(2).build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("MISSING")
                    .auth(Optional.of(new AuthConfig("none", List.of())))
                    .allowedMethods(List.of(HttpMethod.GET))
                    .routes(List.of(route("orders-post", HttpMethod.POST)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertAll("all three independent violations surface together",
                    () -> assertTrue(errors.size() >= 3, () -> "expected at least three violations, got: " + errors),
                    () -> assertHasError(errors, "/version", "unsupported config version"),
                    () -> assertHasError(errors, "/endpoint/base_url", "unresolved topology alias: MISSING"),
                    () -> assertHasError(errors, "/endpoint/routes", "outside the effective allowed_methods"));
        }
    }

    @Nested
    @DisplayName("Fail-closed WebSocket allowlist (ADR-0015)")
    class WebSocketAllowlist {

        private static GatewayConfig gatewayWithIssuer() {
            return validGateway()
                    .tokenValidation(Optional.of(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build()))))
                    .build();
        }

        private static EndpointConfig webSocketEndpoint(String alias, RouteConfig route) {
            return EndpointConfig.builder()
                    .id("ws-ep").enabled(true).baseUrl(alias)
                    .auth(Optional.of(new AuthConfig("none", List.of())))
                    .routes(List.of(route))
                    .build();
        }

        private static RouteConfig webSocketRoute(String id, Optional<WebSocketConfig> websocket,
                Optional<AuthConfig> auth) {
            return RouteConfig.builder()
                    .id(id)
                    .protocol(Optional.of(Protocol.WEBSOCKET))
                    .match(match("/" + id, HttpMethod.GET))
                    .auth(auth)
                    .websocket(websocket)
                    .build();
        }

        private static Optional<AuthConfig> bearer() {
            return Optional.of(new AuthConfig("bearer", List.of()));
        }

        @Test
        @DisplayName("Should reject a bearer WebSocket route with no websocket block (fail-closed)")
        void shouldRejectBearerWebSocketRouteWithAbsentAllowedOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            EndpointConfig endpoint = webSocketEndpoint("WS", webSocketRoute("chat", Optional.empty(), bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "must declare a non-empty allowed_origins allowlist");
        }

        @Test
        @DisplayName("Should reject a bearer WebSocket route with an empty allowed_origins allowlist")
        void shouldRejectBearerWebSocketRouteWithEmptyAllowedOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = new WebSocketConfig(List.of(), Optional.empty());
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", Optional.of(websocket), bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "fail-closed");
        }

        @ParameterizedTest(name = "wildcard entry \"{0}\" is rejected")
        @ValueSource(strings = {"*", "https://*.example.com"})
        @DisplayName("Should reject wildcard entries in allowed_origins")
        void shouldRejectWildcardAllowedOrigin(String wildcard) {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = new WebSocketConfig(List.of(wildcard), Optional.empty());
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", Optional.of(websocket), bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "wildcards are not permitted");
        }

        @ParameterizedTest(name = "idle_timeout_seconds = {0} is rejected")
        @ValueSource(ints = {0, -1, -300})
        @DisplayName("Should reject a non-positive idle_timeout_seconds")
        void shouldRejectNonPositiveIdleTimeout(int timeout) {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), Optional.of(timeout));
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", Optional.of(websocket), bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "idle_timeout_seconds must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a bearer WebSocket route with exact origins and a positive idle timeout")
        void shouldAcceptBearerWebSocketRouteWithExactOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), Optional.of(60));
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", Optional.of(websocket), bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should not require allowed_origins for a non-bearer WebSocket route")
        void shouldNotRequireAllowedOriginsForNonBearerWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = webSocketEndpoint("WS", webSocketRoute("chat", Optional.empty(), Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(),
                    () -> "a non-bearer WebSocket route may omit allowed_origins; got: " + errors);
        }

        @Test
        @DisplayName("Should reject a non-websocket route that declares a websocket block")
        void shouldRejectWebSocketBlockOnNonWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), Optional.of(60));
            RouteConfig httpRoute = RouteConfig.builder()
                    .id("http-with-ws")
                    .protocol(Optional.of(Protocol.HTTP))
                    .match(match("/http-with-ws", HttpMethod.GET))
                    .auth(Optional.empty())
                    .websocket(Optional.of(websocket))
                    .build();
            EndpointConfig endpoint = webSocketEndpoint("WS", httpRoute);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes",
                    "declares a websocket block but its protocol is not 'websocket'");
        }
    }

    @Nested
    @DisplayName("The BFF OIDC/session fold rules (D1)")
    class BffOidcFoldRules {

        private static GatewayConfig gatewayWithOidc(OidcConfig oidc) {
            return validGateway().oidc(Optional.of(oidc)).build();
        }

        @Test
        @DisplayName("Should accept a user_info block whose default_view claims all lie within the allowlist")
        void shouldAcceptUserInfoWithDefaultViewWithinAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(Optional.of(OidcConfig.UserInfo.builder()
                            .path(Optional.of("/session/userinfo"))
                            .allowedClaims(List.of("sub", "name", "roles"))
                            .defaultView(List.of("sub", "name"))
                            .build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should accept a user_info block with an empty allowlist as the secure closed default")
        void shouldAcceptUserInfoWithEmptyAllowlistSecureDefault() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(Optional.of(OidcConfig.UserInfo.builder()
                            .path(Optional.of("/session/userinfo"))
                            .build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "an empty allowlist is the secure closed default and must not be an error, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a default_view claim that lies outside the operator allowlist")
        void shouldRejectDefaultViewClaimOutsideAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(Optional.of(OidcConfig.UserInfo.builder()
                            .path(Optional.of("/session/userinfo"))
                            .allowedClaims(List.of("sub", "name"))
                            .defaultView(List.of("sub", "email"))
                            .build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/default_view", "not in allowed_claims");
        }

        @ParameterizedTest(name = "user_info path \"{0}\" is rejected as non-absolute")
        @ValueSource(strings = {"session/userinfo", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject a malformed user_info path that is not an absolute gateway path")
        void shouldRejectNonAbsoluteUserInfoPath(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(Optional.of(OidcConfig.UserInfo.builder().path(Optional.of(path)).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/path", "must be an absolute gateway path");
        }

        @ParameterizedTest(name = "off-path login value \"{0}\" is rejected")
        @ValueSource(strings = {"login", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject an off-path login value")
        void shouldRejectOffPathLoginValue(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(Optional.of(OidcConfig.Login.builder().path(Optional.of(path)).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/login/path", "must be an absolute gateway path");
        }

        @Test
        @DisplayName("Should accept an absolute login path")
        void shouldAcceptAbsoluteLoginPath() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(Optional.of(OidcConfig.Login.builder().path(Optional.of("/session/login")).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "max_sessions = {0} is rejected")
        @ValueSource(ints = {0, -1, -1000})
        @DisplayName("Should reject a non-positive session max_sessions bound")
        void shouldRejectNonPositiveMaxSessions(int maxSessions) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(Optional.of(OidcConfig.Session.builder().maxSessions(Optional.of(maxSessions)).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/session/max_sessions", "must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a positive session max_sessions bound")
        void shouldAcceptPositiveMaxSessions() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(Optional.of(OidcConfig.Session.builder().maxSessions(Optional.of(10000)).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is rejected")
        @ValueSource(ints = {0, -1, 39, 8193, 65536})
        @DisplayName("Should reject a session max_cookie_size outside the viable budget bounds")
        void shouldRejectOutOfBoundsMaxCookieSize(int maxCookieSize) {
            // Arrange — below the floor no sealed value could ever be emitted; above the ceiling the
            // derived pre-route Cookie cap would exceed what the transport carries.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(Optional.of(OidcConfig.Session.builder()
                            .maxCookieSize(Optional.of(maxCookieSize)).build()))
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertHasError(errors, "/oidc/session/max_cookie_size", "must be between");
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is accepted")
        @ValueSource(ints = {40, 4096, 8192})
        @DisplayName("Should accept a session max_cookie_size on and inside the viable budget bounds")
        void shouldAcceptInBoundsMaxCookieSize(int maxCookieSize) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(Optional.of(OidcConfig.Session.builder()
                            .maxCookieSize(Optional.of(maxCookieSize)).build()))
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }
    }

    @Nested
    @DisplayName("The fail-closed 'profile: none' refusal (ADR-0024)")
    class SecurityProfileNoneRefusal {

        private static final String NONE_PROFILE = "none";
        private static final String REQUIRE_NONE = "none";
        private static final String REQUIRE_BEARER = "bearer";
        private static final String REFUSAL_MESSAGE = "resolves inbound-filter profile 'none'";

        private static RouteConfig profiledRoute(String id, String prefix, String anchorName, String profile,
                Optional<AuthConfig> auth) {
            return RouteConfig.builder()
                    .id(id)
                    .anchor(anchorName == null ? Optional.empty() : Optional.of(anchorName))
                    .match(match(prefix, HttpMethod.GET))
                    .auth(auth)
                    .securityFilter(profile == null ? Optional.empty()
                            : Optional.of(SecurityFilterConfig.builder().profile(Optional.of(profile)).build()))
                    .build();
        }

        private static GatewayConfig gatewayWithGlobalProfile(AnchorConfig anchorConfig, String globalProfile) {
            return validGateway()
                    .anchors(Map.of(anchorConfig.name(), anchorConfig))
                    .securityDefaults(Optional.of(new SecurityDefaultsConfig(Optional.of(globalProfile))))
                    .tokenValidation(Optional.of(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build()))))
                    .build();
        }

        @Test
        @DisplayName("Should reject 'none' on a route under an access: authenticated anchor")
        void shouldRejectNoneOnAuthenticatedAnchor() {
            // Arrange — the anchor's bearer floor makes every route under it effectively authenticated.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, REQUIRE_BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", Optional.empty(),
                    profiledRoute("secure-read", "/secure/read", "secure", NONE_PROFILE, Optional.empty()));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
            assertHasError(errors, "/endpoint/routes", "effective access level is 'authenticated'");
        }

        @Test
        @DisplayName("Should reject 'none' on a route under a type: bff anchor, naming the anchor-type dimension")
        void shouldRejectNoneOnBffAnchor() {
            // Arrange — a bff anchor is required to be access: authenticated (ADR-0013), so a matrix-clean
            // bff fixture necessarily trips both refusal dimensions; the anchor-type one must be named.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("shell", "/shell", AnchorType.BFF, AccessLevel.AUTHENTICATED, REQUIRE_BEARER));
            EndpointConfig endpoint = anchoredEndpoint("bff", "BFF", "shell", Optional.empty(),
                    profiledRoute("shell-view", "/shell/view", "shell", NONE_PROFILE, Optional.empty()));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("BFF"));

            // Assert
            assertHasError(errors, "/endpoint/routes", "anchor 'shell' is type 'bff'");
        }

        @Test
        @DisplayName("Should reject 'none' on a public anchor whose route strengthens the auth floor")
        void shouldRejectNoneOnRouteStrengtheningPublicAnchorFloor() {
            // Arrange — the under-refusal case: the anchor stays access: public, so reading the anchor's
            // static access would let this route through, but the route's own bearer floor makes it
            // effectively authenticated.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null));
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    Optional.of(new AuthConfig(REQUIRE_NONE, List.of())),
                    profiledRoute("open-secured", "/open/secured", "open", NONE_PROFILE,
                            Optional.of(new AuthConfig(REQUIRE_BEARER, List.of()))));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
            assertHasError(errors, "/endpoint/routes", "effective access level is 'authenticated'");
        }

        @Test
        @DisplayName("Should accept 'none' on a genuinely public, effectively-unauthenticated route")
        void shouldAcceptNoneOnPublicUnauthenticatedRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("open",
                    matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    Optional.of(new AuthConfig(REQUIRE_NONE, List.of())),
                    profiledRoute("open-read", "/open/read", "open", NONE_PROFILE, Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a gateway-wide security_defaults 'none' inherited by an authenticated route")
        void shouldRejectGlobalNoneInheritedByAuthenticatedRoute() {
            // Arrange — the route declares no security_filter at all; 'none' reaches it through the
            // gateway-wide fallback, which is the same violation as declaring it per route.
            GatewayConfig gateway = gatewayWithGlobalProfile(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, REQUIRE_BEARER),
                    NONE_PROFILE);
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", Optional.empty(),
                    profiledRoute("secure-read", "/secure/read", "secure", null, Optional.empty()));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
        }

        @Test
        @DisplayName("Should accept a gateway-wide 'none' that no authenticated or BFF route inherits")
        void shouldAcceptGlobalNoneOnPublicRoutesOnly() {
            GatewayConfig gateway = validGateway()
                    .anchors(Map.of("open",
                            matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null)))
                    .securityDefaults(Optional.of(new SecurityDefaultsConfig(Optional.of(NONE_PROFILE))))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    Optional.of(new AuthConfig(REQUIRE_NONE, List.of())),
                    profiledRoute("open-read", "/open/read", "open", null, Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "profile ''{0}'' is accepted on an authenticated route")
        @ValueSource(strings = {"strict", "lenient", "STRICT", "Lenient"})
        @DisplayName("Should accept a non-'none' profile on an authenticated route, case-insensitively")
        void shouldAcceptNonNoneProfileOnAuthenticatedRoute(String profile) {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, REQUIRE_BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", Optional.empty(),
                    profiledRoute("secure-read", "/secure/read", "secure", profile, Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report the refusal alongside an unrelated violation in one pass")
        void shouldAggregateRefusalWithUnrelatedViolation() {
            // Arrange — an unsupported version plus a refused 'none' route: the pass must report both.
            GatewayConfig gateway = validGateway()
                    .version(2)
                    .anchors(Map.of("secure",
                            matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED,
                                    REQUIRE_BEARER)))
                    .tokenValidation(Optional.of(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build()))))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", Optional.empty(),
                    profiledRoute("secure-read", "/secure/read", "secure", NONE_PROFILE, Optional.empty()));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertAll(
                    () -> assertHasError(errors, "/version", "unsupported config version"),
                    () -> assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE));
        }

        @Test
        @DisplayName("Should name the remedy and echo no configured scalar value")
        void shouldNameRemedyWithoutEchoingConfiguredScalars() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, REQUIRE_BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", Optional.empty(),
                    profiledRoute("secure-read", "/secure/read", "secure", NONE_PROFILE, Optional.empty()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            ConfigError refusal = errors.stream()
                    .filter(error -> error.message().contains(REFUSAL_MESSAGE))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a 'none' refusal error, got: " + errors));
            assertAll(
                    () -> assertTrue(refusal.message().contains("secure-read"), "names the route"),
                    () -> assertTrue(refusal.message().contains("declare profile 'strict' or 'lenient'"),
                            "names the remedy"),
                    () -> assertFalse(refusal.message().contains("https://idp.example"), "echoes no configured scalar value"));
        }
    }
}
