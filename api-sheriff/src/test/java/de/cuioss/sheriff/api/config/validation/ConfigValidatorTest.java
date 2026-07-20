/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.api.config.RouteTableBuilder;
import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.AnchorConfig;
import de.cuioss.sheriff.api.config.model.AnchorType;
import de.cuioss.sheriff.api.config.model.AssetConfig;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ForwardedConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.IssuerConfig;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.OidcConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.config.model.TlsConfig;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigValidator}: one negative case per enforced cross-cutting
 * rule (including the seven ADR-0007 anchor / effective-auth rules), the D5
 * boot-time hardening rules (real-CIDR {@code trusted_proxies} parsing with
 * full-space rejection and broad-prefix boot-WARN, and the same-prefix
 * route-disjointness rule moved here from {@code RouteTableBuilder}), the structural
 * {@code TRACE}/{@code CONNECT} rejection, and the single-pass aggregation contract
 * that reports every violation together rather than stopping at the first.
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

        @Test
        @DisplayName("Should reject a single full-space IPv4 CIDR in forwarded.trusted_proxies")
        void shouldRejectTrustAllCidr() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder().trustedProxies(List.of("0.0.0.0/0")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "entire IPv4 address space");
        }

        @Test
        @DisplayName("Should reject a single full-space IPv6 CIDR in forwarded.trusted_proxies")
        void shouldRejectTrustAllIpv6Cidr() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder().trustedProxies(List.of("::/0")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "entire IPv6 address space");
        }

        @Test
        @DisplayName("Should reject a complementary /1 IPv4 pair that together cover the whole address space")
        void shouldRejectComplementaryIpv4HalfPair() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("0.0.0.0/1", "128.0.0.0/1")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "entire IPv4 address space");
        }

        @Test
        @DisplayName("Should reject a complementary /1 IPv6 pair that together cover the whole address space")
        void shouldRejectComplementaryIpv6HalfPair() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("::/1", "8000::/1")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "entire IPv6 address space");
        }

        @Test
        @DisplayName("Should reject a malformed trusted_proxies CIDR entry with file/pointer context")
        void shouldRejectMalformedCidr() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("not-a-cidr")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "malformed trusted_proxies CIDR: not-a-cidr");
        }

        @Test
        @DisplayName("Should reject a trusted_proxies CIDR whose prefix length is out of range")
        void shouldRejectOutOfRangePrefixLength() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder()
                            .trustedProxies(List.of("10.0.0.0/33")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "malformed trusted_proxies CIDR: 10.0.0.0/33");
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
        @DisplayName("Should reject cookie session mode without an encryption_key")
        void shouldRejectCookieSessionWithoutEncryptionKey() {
            GatewayConfig gateway = validGateway()
                    .oidc(Optional.of(OidcConfig.builder()
                            .session(Optional.of(OidcConfig.Session.builder()
                                    .mode(Optional.of("cookie"))
                                    .build()))
                            .build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/encryption_key", "cookie session mode requires an encryption_key");
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

        @Test
        @DisplayName("Rule authenticated→floor: Should reject an access: authenticated anchor declaring no auth floor at all")
        void shouldRejectAuthenticatedAnchorWithNoAuthBlock() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure", "declares no non-'none' auth floor");
        }

        @Test
        @DisplayName("Rule authenticated→floor: Should reject an access: authenticated anchor whose floor is 'none'")
        void shouldRejectAuthenticatedAnchorWithNoneFloor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, "none")));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure", "declares no non-'none' auth floor");
        }

        @Test
        @DisplayName("Rule authenticated backing: Should reject an authenticated bearer floor with no token_validation issuer")
        void shouldRejectAuthenticatedBearerFloorWithoutIssuer() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, "bearer")));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure",
                    "access: authenticated bearer floor requires token_validation with at least one issuer");
        }

        @Test
        @DisplayName("Rule authenticated backing: Should reject an authenticated session floor with no oidc block")
        void shouldRejectAuthenticatedSessionFloorWithoutOidc() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, "session")));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure",
                    "access: authenticated session floor requires an oidc block");
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
}
