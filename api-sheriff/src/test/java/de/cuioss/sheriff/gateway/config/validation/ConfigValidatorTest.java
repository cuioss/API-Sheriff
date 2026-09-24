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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AssetDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
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
import org.jspecify.annotations.Nullable;
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
 * rule (including the ADR-0007 anchor / effective-auth rules, namespace coverage among them), the D5
 * boot-time hardening rules (real-CIDR {@code trusted_proxies} parsing with
 * full-space rejection and broad-prefix boot-WARN, and the same-prefix
 * route-disjointness rule moved here from {@code RouteTableBuilder}), the structural
 * {@code TRACE}/{@code CONNECT} rejection, the fail-closed ADR-0024 {@code profile: minimal}
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
                .host(host)
                .build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    /**
     * A gateway declaring the given global {@code profile} and — when present — the
     * {@code max_authorization_header_value_length} carve-out budget the boot rule bounds.
     */
    private static GatewayConfig gatewayWithAuthorizationCap(String profile, @Nullable Integer cap) {
        return validGateway()
                .securityDefaults(new SecurityDefaultsConfig(profile, cap, null, null))
                .build();
    }

    private static GatewayConfig gatewayWithPassthrough(Map<String, String> passthroughSni) {
        return validGateway()
                .tls(TlsConfig.builder().passthroughSni(passthroughSni).build())
                .build();
    }

    private static EndpointConfig endpoint(String id, String alias, List<HttpMethod> allowedMethods,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .auth(new AuthConfig(Require.NONE, null, null))
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
                .auth(require == null ? null : new AuthConfig(require, null, null))
                .build();
    }

    private static AnchorConfig matrixAnchor(String name, String prefix, AnchorType type, AccessLevel access,
            @Nullable Require require) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(type)
                .access(access)
                .auth(require == null ? null : new AuthConfig(require, null, null))
                .build();
    }

    private static GatewayConfig gatewayWithAnchorAndIssuer(AnchorConfig anchorConfig) {
        return validGateway()
                .anchors(Map.of(anchorConfig.name(), anchorConfig))
                .tokenValidation(new TokenValidationConfig(List.of(
                        IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
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

    /** An exact ({@code match.path}) route — the AS-2 matcher form the coverage rule reasons about. */
    private static RouteConfig exactRoute(String id, String path, @Nullable String anchorName) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(MatchConfig.builder().path(path).build())
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

    private static AssetConfig upstreamAsset(String alias) {
        return AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                .upstream(alias).build();
    }

    @Nested
    @DisplayName("content_security_policy — header-injection refusal on the global and every anchor block")
    class ContentSecurityPolicyInjection {

        private static final String GLOBAL_POINTER = "/security_headers/content_security_policy";
        private static final String ANCHOR_POINTER = "/anchors/frontend/security_headers/content_security_policy";
        private static final String VALID_POLICY = "default-src 'self'; img-src 'self' data:";

        private static GatewayConfig gatewayWithPolicies(@Nullable String globalPolicy, @Nullable String anchorPolicy) {
            AnchorConfig frontend = AnchorConfig.builder()
                    .name("frontend")
                    .pathPrefix("/frontend")
                    .type(AnchorType.PROXY)
                    .access(AccessLevel.PUBLIC)
                    .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(anchorPolicy).build())
                    .build();
            return validGateway()
                    .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(globalPolicy).build())
                    .anchors(Map.of(frontend.name(), frontend))
                    .build();
        }

        private static List<ConfigError> policyErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.pointer().endsWith("/content_security_policy")).toList();
        }

        @ParameterizedTest(name = "global value #{index}")
        @ValueSource(strings = {"default-src 'self'\r\nSet-Cookie: session=forged", "default-src 'self'\nX-Injected: 1",
                "default-src 'self'\rX-Injected: 1", "default-src 'self'", "default-src\t'self'",
                "default-src'self'", "   "})
        @DisplayName("Should refuse a global policy that is blank or carries a CR, LF or other control character")
        void shouldRefuseInjectingGlobalPolicy(String policy) {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies(policy, null), List.of(), topologyWith());

            assertHasError(errors, GLOBAL_POINTER, "without CR, LF or other control characters");
        }

        @ParameterizedTest(name = "anchor value #{index}")
        @ValueSource(strings = {"default-src 'self'\r\nSet-Cookie: session=forged", "default-src 'self'\nX-Injected: 1",
                "default-src 'self'", "   "})
        @DisplayName("Should refuse an anchor policy that is blank or carries a control character, naming the anchor block")
        void shouldRefuseInjectingAnchorPolicy(String policy) {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies(VALID_POLICY, policy), List.of(),
                    topologyWith());

            assertAll("only the anchor block is named",
                    () -> assertHasError(errors, ANCHOR_POINTER, "without CR, LF or other control characters"),
                    () -> assertTrue(errors.stream().noneMatch(error -> GLOBAL_POINTER.equals(error.pointer())),
                            () -> "the valid global policy must not be reported, got: " + errors));
        }

        @Test
        @DisplayName("Should never echo a raw line terminator of the refused value into the error message")
        void shouldNotEchoRawLineTerminator() {
            List<ConfigError> errors = validator.validate(
                    gatewayWithPolicies("default-src 'self'\r\nSet-Cookie: session=forged", null), List.of(),
                    topologyWith());

            assertAll("the refusal message stays single-line (CWE-117)",
                    policyErrors(errors).stream().map(error -> () -> assertFalse(
                            error.message().contains("\r") || error.message().contains("\n"),
                            () -> "the message must not carry a raw line terminator: " + error.message())));
        }

        @Test
        @DisplayName("Should collect a global and an anchor violation together rather than stopping at the first")
        void shouldCollectGlobalAndAnchorViolationsTogether() {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies("a\nb", "c\rd"), List.of(),
                    topologyWith());

            List<String> pointers = policyErrors(errors).stream().map(ConfigError::pointer).toList();
            assertAll("both blocks are reported in one pass",
                    () -> assertEquals(2, pointers.size(), () -> "exactly one error per block, got: " + pointers),
                    () -> assertTrue(pointers.containsAll(List.of(GLOBAL_POINTER, ANCHOR_POINTER)),
                            () -> "the global and the anchor block are both named, got: " + pointers));
        }

        @Test
        @DisplayName("Should accept a well-formed policy on both blocks, and an omitted policy on either")
        void shouldAcceptWellFormedOrOmittedPolicies() {
            List<ConfigError> declared = validator.validate(gatewayWithPolicies(VALID_POLICY, VALID_POLICY), List.of(),
                    topologyWith());
            List<ConfigError> omitted = validator.validate(gatewayWithPolicies(null, null), List.of(), topologyWith());

            assertAll("a control-character-free policy, or none, raises no content_security_policy error",
                    () -> assertTrue(policyErrors(declared).isEmpty(), () -> "got: " + declared),
                    () -> assertTrue(policyErrors(omitted).isEmpty(), () -> "got: " + omitted));
        }
    }

    @Nested
    @DisplayName("header_modes — a mode may only name a header its own block enables")
    class HeaderModesOrphanRefusal {

        private static final String GLOBAL_MODES = "/security_headers/header_modes/";
        private static final String ANCHOR_MODES = "/anchors/frontend/security_headers/header_modes/";

        private static List<ConfigError> modeErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.pointer().contains("/header_modes/")).toList();
        }

        private static GatewayConfig gatewayWithBlocks(SecurityHeadersConfig global,
                @Nullable SecurityHeadersConfig anchorBlock) {
            AnchorConfig frontend = AnchorConfig.builder()
                    .name("frontend")
                    .pathPrefix("/frontend")
                    .type(AnchorType.PROXY)
                    .access(AccessLevel.PUBLIC)
                    .securityHeaders(anchorBlock)
                    .build();
            return validGateway().securityHeaders(global).anchors(Map.of(frontend.name(), frontend)).build();
        }

        private static SecurityHeadersConfig.HeaderModes allDefault() {
            SecurityHeadersConfig.HeaderMode mode = SecurityHeadersConfig.HeaderMode.DEFAULT;
            return new SecurityHeadersConfig.HeaderModes(mode, mode, mode, mode);
        }

        @Test
        @DisplayName("Should refuse every mode naming a header the global block does not enable, one error per key")
        void shouldRefuseEveryOrphanModeOnTheGlobalBlock() {
            SecurityHeadersConfig global = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(false)
                    .headerModes(allDefault())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(global, null), List.of(), topologyWith());

            assertAll("an absent hsts / csp and a false nosniff / absent frame_deny are all orphans",
                    () -> assertEquals(4, modeErrors(errors).size(), () -> "got: " + errors),
                    () -> assertHasError(errors, GLOBAL_MODES + "hsts", "does not enable hsts"),
                    () -> assertHasError(errors, GLOBAL_MODES + "content_type_nosniff",
                            "does not enable content_type_nosniff"),
                    () -> assertHasError(errors, GLOBAL_MODES + "frame_deny", "does not enable frame_deny"),
                    () -> assertHasError(errors, GLOBAL_MODES + "content_security_policy",
                            "does not enable content_security_policy"));
        }

        @Test
        @DisplayName("Should judge an anchor block's modes against that anchor block, not against the global block")
        void shouldJudgeAnchorModesAgainstTheAnchorBlock() {
            SecurityHeadersConfig global = SecurityHeadersConfig.builder().frameDeny(true).build();
            SecurityHeadersConfig anchorBlock = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(true)
                    .headerModes(SecurityHeadersConfig.HeaderModes.builder()
                            .frameDeny(SecurityHeadersConfig.HeaderMode.DEFAULT).build())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(global, anchorBlock), List.of(),
                    topologyWith());

            assertAll("the global frame_deny does not legitimise the anchor's frame_deny mode (wholesale replacement)",
                    () -> assertEquals(1, modeErrors(errors).size(), () -> "got: " + errors),
                    () -> assertHasError(errors, ANCHOR_MODES + "frame_deny", "does not enable frame_deny"));
        }

        @Test
        @DisplayName("Should accept a mode for every header its block enables, on the global and an anchor block")
        void shouldAcceptModesForEnabledHeaders() {
            SecurityHeadersConfig enabledAll = SecurityHeadersConfig.builder()
                    .hsts(new SecurityHeadersConfig.Hsts(31536000, true))
                    .contentTypeNosniff(true)
                    .frameDeny(true)
                    .contentSecurityPolicy("default-src 'self'")
                    .headerModes(allDefault())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(enabledAll, enabledAll), List.of(),
                    topologyWith());

            assertTrue(modeErrors(errors).isEmpty(), () -> "no mode is an orphan, got: " + errors);
        }
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
                    new AuthConfig(Require.NONE, null, null),
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
                    new AuthConfig(Require.NONE, null, null),
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
    @DisplayName("Terminal-action / anchor-type consistency (ADR-0014)")
    class TerminalActionConsistency {

        @Test
        @DisplayName("Should accept a directory asset route under an asset anchor")
        void shouldAcceptDirectoryAssetUnderAssetAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    new AuthConfig(Require.NONE, null, null),
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
                    new AuthConfig(Require.NONE, null, null),
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
                    new AuthConfig(Require.NONE, null, null),
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
                    new AuthConfig(Require.NONE, null, null),
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
                    .auth(new AuthConfig(Require.NONE, null, null))
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
                    new AuthConfig(Require.NONE, null, null),
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
                    new AuthConfig(Require.NONE, null, null),
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
                    .edgeHardening(new EdgeHardeningConfig(0, 1)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/admission_cap", "admission_cap must be at least 1");
        }

        @Test
        @DisplayName("Should reject an edge_hardening websocket_relay_cap below 1")
        void shouldRejectWebsocketRelayCapBelowOne() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(new EdgeHardeningConfig(8, 0)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/websocket_relay_cap",
                    "websocket_relay_cap must be at least 1");
        }

        @Test
        @DisplayName("Should reject a websocket_relay_cap exceeding admission_cap, which could never bind")
        void shouldRejectInvertedCapPair() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(new EdgeHardeningConfig(4, 16)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/edge_hardening/websocket_relay_cap", "must not exceed admission_cap");
        }

        @Test
        @DisplayName("Should accept a lowered admission_cap with websocket_relay_cap omitted")
        void shouldAcceptLoweredAdmissionCapWithoutRelayCap() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(new EdgeHardeningConfig(64, null)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    "A partially declared edge_hardening block must not self-reject, but got: " + errors);
            assertEquals(16, gateway.edgeHardening().effectiveWebsocketRelayCap(),
                    "The implicit relay sub-budget stays a quarter of the effective admission cap");
        }

        @Test
        @DisplayName("Should accept a well-formed edge_hardening block")
        void shouldAcceptValidEdgeHardeningBlock() {
            GatewayConfig gateway = validGateway()
                    .edgeHardening(new EdgeHardeningConfig(64, 8)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), "A valid admission budget raises no violation, but got: " + errors);
        }

        @Test
        @DisplayName("Should reject a max_authorization_header_value_length below the strict baseline")
        void shouldRejectAuthorizationCapBelowStrictBaseline() {
            // Arrange — strict resolves a 1024-character baseline; 512 would make the 'carve-out'
            // a per-header tightening wearing a relaxation key's name.
            GatewayConfig gateway = gatewayWithAuthorizationCap("strict", 512);
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            // Assert
            assertHasError(errors, "/security_defaults/max_authorization_header_value_length",
                    "is below the resolved baseline max_header_value_length");
            assertEquals(1, errors.size(), () -> "expected exactly one violation, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a max_authorization_header_value_length below the lenient baseline")
        void shouldRejectAuthorizationCapBelowLenientBaseline() {
            // Arrange — the value that is legal under strict (2048 > 1024) is refused under lenient
            // (2048 < 8192), which is what proves the rule compares against the RESOLVED profile
            // rather than against a constant.
            GatewayConfig gateway = gatewayWithAuthorizationCap("lenient", 2048);
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            // Assert
            assertHasError(errors, "/security_defaults/max_authorization_header_value_length",
                    "is below the resolved baseline max_header_value_length");
        }

        @Test
        @DisplayName("Should accept a max_authorization_header_value_length at or above the resolved baseline")
        void shouldAcceptAuthorizationCapAtOrAboveBaseline() {
            // Arrange — the matched positive half: 2048 clears the strict 1024 baseline, and 1024 is
            // the boundary case 'exactly at the baseline', which is not below it and is not refused.
            GatewayConfig above = gatewayWithAuthorizationCap("strict", 2048);
            GatewayConfig atBaseline = gatewayWithAuthorizationCap("strict", 1024);
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            // Act
            List<ConfigError> aboveErrors = validator.validate(above, List.of(endpoint), topologyWith("ORDERS"));
            List<ConfigError> atBaselineErrors =
                    validator.validate(atBaseline, List.of(endpoint), topologyWith("ORDERS"));

            // Assert
            assertTrue(aboveErrors.isEmpty(), () -> "a raised cap raises no violation, but got: " + aboveErrors);
            assertTrue(atBaselineErrors.isEmpty(),
                    () -> "a cap exactly at the baseline is not below it, but got: " + atBaselineErrors);
        }

        @Test
        @DisplayName("Should never refuse an omitted max_authorization_header_value_length")
        void shouldAcceptOmittedAuthorizationCap() {
            // Arrange — an omitted key resolves to the documented default and is never a violation,
            // under either profile.
            GatewayConfig strict = gatewayWithAuthorizationCap("strict", null);
            GatewayConfig lenient = gatewayWithAuthorizationCap("lenient", null);
            GatewayConfig blockAbsent = validGateway().build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            // Act + Assert
            assertAll("an omitted key never refuses",
                    () -> assertTrue(validator.validate(strict, List.of(endpoint), topologyWith("ORDERS")).isEmpty()),
                    () -> assertTrue(validator.validate(lenient, List.of(endpoint), topologyWith("ORDERS")).isEmpty()),
                    () -> assertTrue(
                            validator.validate(blockAbsent, List.of(endpoint), topologyWith("ORDERS")).isEmpty()));
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
                    .auth(new AuthConfig(Require.BEARER, null, null))
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
                    .auth(new AuthConfig(Require.SESSION, null, null))
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
                    .upstream(UpstreamConfig.builder().readTimeoutMs(2500).build())
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig(Require.NONE, null, null))
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
                    .forwarded(ForwardedConfig.builder().trustedProxies(trustedProxies).build())
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
                    .forwarded(ForwardedConfig.builder()
                            .trustedProxies(List.of(malformedCidr)).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "malformed trusted_proxies CIDR: " + malformedCidr);
        }

        /**
         * Matched positive/negative controls straddling the broad-prefix threshold in both address
         * families. The warning fires when one entry spans more than a single operator-provisioned
         * network: broader than an IPv4 {@code /16}, or broader than an IPv6 {@code /48} site
         * allocation. Each family carries a range just past the boundary and one exactly at it, so
         * reverting either constant alone turns at least one case red. The IPv4 {@code /4} row pins the
         * far end of the band: a very broad range that still stops short of the whole address space
         * warns and boots, and is never mistaken for the trust-all refusal.
         */
        static Stream<Arguments> broadPrefixThresholdControls() {
            return Stream.of(
                    Arguments.of("IPv4 /4 is very broad yet short of the whole address space", "10.0.0.0/4", true),
                    Arguments.of("IPv4 /12 spans many provisioned networks", "172.16.0.0/12", true),
                    Arguments.of("IPv4 /8 spans many provisioned networks", "10.0.0.0/8", true),
                    Arguments.of("IPv4 /16 is one provisioned network", "172.16.0.0/16", false),
                    Arguments.of("IPv6 /32 is broader than one site allocation", "2001:db8::/32", true),
                    Arguments.of("IPv6 /48 is one site allocation", "2001:db8::/48", false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("broadPrefixThresholdControls")
        @DisplayName("Should warn only on a trusted_proxies range broader than one provisioned network")
        void shouldApplyBroadPrefixThreshold(String label, String cidr, boolean expectWarning) {
            GatewayConfig gateway = validGateway()
                    .forwarded(ForwardedConfig.builder().trustedProxies(List.of(cidr)).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("trusted_proxies")),
                    () -> "a broad-but-not-total CIDR must not fail the boot, got: " + errors);
            String identifier = ConfigLogMessages.WARN.BROAD_TRUSTED_PROXY.resolveIdentifierString();
            if (expectWarning) {
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier);
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, cidr);
            } else {
                assertTrue(TestLoggerFactory.getTestHandler()
                                .resolveLogMessagesContaining(TestLogLevel.WARN, identifier).isEmpty(),
                        () -> cidr + " is within the broad-prefix threshold and must not emit " + identifier);
            }
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
                    .securityHeaders(SecurityHeadersConfig.builder()
                            .cors(SecurityHeadersConfig.Cors.builder()
                                    .allowedOrigins(List.of("*"))
                                    .allowCredentials(true)
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/security_headers/cors", "wildcard origin '*' is not permitted");
        }

        @Test
        @DisplayName("Should check CORS on the global block only — an anchor cannot carry cors (ADR-0007 Amendment A1)")
        void shouldCheckCorsOnGlobalBlockOnly() {
            SecurityHeadersConfig wildcardWithCredentials = SecurityHeadersConfig.builder()
                    .cors(SecurityHeadersConfig.Cors.builder()
                            .allowedOrigins(List.of("*"))
                            .allowCredentials(true)
                            .build())
                    .build();
            AnchorConfig anchorWithCors = AnchorConfig.builder()
                    .name("open").pathPrefix("/open").type(AnchorType.PROXY).access(AccessLevel.PUBLIC)
                    .securityHeaders(wildcardWithCredentials)
                    .build();
            GatewayConfig gateway = gatewayWithAnchors(Map.of("open", anchorWithCors));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains("cors")),
                    () -> "CORS is evaluated before route selection, so an anchor block is never a CORS source and "
                            + "the gateway schema refuses one at load; the validator checks the global block only, got: "
                            + errors);
        }

        @Test
        @DisplayName("Should accept cookie session mode without an encryption_key (generate-on-startup)")
        void shouldAcceptCookieSessionWithoutEncryptionKey() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("cookie")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "omitting encryption_key selects the generate-on-startup key mode, got: " + errors);
        }

        @Test
        @DisplayName("Should accept cookie session mode with an encryption_key (passed-key mode)")
        void shouldAcceptCookieSessionWithEncryptionKey() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("cookie")
                                    .encryptionKey("${SHERIFF_SESSION_KEY}")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "declaring the one sealing key selects the passed-key mode, got: " + errors);
        }

        @Test
        @DisplayName("Should reject server session mode without a store")
        void shouldRejectServerSessionWithoutStore() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("server")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
        }

        @Test
        @DisplayName("Should not apply the server-mode store rule to a mixed-case cookie mode value")
        void shouldNotApplyServerRuleToMixedCaseCookieMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("  CoOkIe ")
                                    .encryptionKey("${SHERIFF_SESSION_KEY}")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "the canonicalized value is cookie mode, which holds no server-side store, got: " + errors);
        }

        @Test
        @DisplayName("Should apply the server-mode companion rule to a mixed-case mode value")
        void shouldApplyServerRuleToMixedCaseMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("SERVER")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
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
                    .auth(new AuthConfig(Require.BEARER, null, null))
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
    @DisplayName("The anchor / effective-auth rules (ADR-0007)")
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
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("r", "/other", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/anchor", "references undefined anchor 'ghost'");
        }

        @Test
        @DisplayName("Rule 3: Should reject a route whose path lies outside its declared anchor namespace")
        void shouldRejectRoutePathOutsideDeclaredAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api",
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("r", "/billing", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Rule 4: Should reject an undeclared squatter route inside an anchor namespace")
        void shouldRejectUndeclaredSquatter() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", null,
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("r", "/api/secret", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "does not declare it");
        }

        // --- Rule 4b: the namespace-coverage mirror of rule 4 --------------------------------------
        // Rules 3 and 4 judge a route by where its OWN match key sits, so a route sitting outside and
        // ABOVE an anchor is unjudged by both. With AS-2 exact routes that gap is reachable: an exact
        // route covers one address, so every other address in the namespace falls through to the
        // broader route and is served with ITS auth posture. These four cases pin the rule and its
        // coverage exception, with the covered case as the negative control.

        @Test
        @DisplayName("Rule 4b: Should reject a broader route swallowing a namespace whose only member is an exact route")
        void shouldRejectBroaderRouteSwallowingExactOnlyAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // /admin/ and /admin/x match no route belonging to the anchor and would be served by the
            // unanchored catch-all with require: none — the anchor's bearer floor escaped.
            assertHasError(errors, "/endpoint/routes",
                    "route 'catch-all' path_prefix '/' contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should accept a broader route when a prefix route under the anchor covers the namespace")
        void shouldAcceptBroaderRouteWhenAnchoredPrefixRouteCoversNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // Longest-prefix selection gives every address under /admin to the anchored route, so the
            // catch-all alongside it is the ordinary topology and not a bypass.
            assertTrue(errors.isEmpty(),
                    () -> "a covered anchor namespace must not refuse a broader sibling route, got: " + errors);
        }

        /**
         * The covering route from the accepted case above, narrowed on one dimension at a time. Each
         * value is the {@code match} of a route declared at the anchor's own prefix — so it passes the
         * prefix-equality half of the coverage test and is rejected solely by the narrowing.
         */
        static Stream<Arguments> narrowedCoveringMatchers() {
            return Stream.of(
                    Arguments.of("methods", MatchConfig.builder().pathPrefix("/admin")
                            .methods(List.of(HttpMethod.GET)).build()),
                    Arguments.of("host", MatchConfig.builder().pathPrefix("/admin")
                            .host("admin.example.org").build()),
                    Arguments.of("headers", MatchConfig.builder().pathPrefix("/admin")
                            .headers(List.of(new MatchConfig.HeaderMatcher("X-Admin", null, "yes"))).build()));
        }

        @ParameterizedTest(name = "narrowed on {0}")
        @MethodSource("narrowedCoveringMatchers")
        @DisplayName("Rule 4b: Should reject when the covering route narrows beyond the path, so it serves only part of the namespace")
        void shouldRejectWhenCoveringRouteNarrowsBeyondThePath(String dimension, MatchConfig narrowed) {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    RouteConfig.builder().id("admin-app").anchor("admin").match(narrowed).build());
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // The member names the whole namespace but answers only the part its narrowing admits: a
            // POST (or a request from another host, or one without the header) matches no anchored
            // route and is served by the unanchored catch-all under require: none. Naming a namespace
            // is not covering it, so this must refuse exactly as an uncovered namespace does.
            assertHasError(errors, "/endpoint/routes",
                    "contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should reject when the anchored prefix route covers only part of the namespace")
        void shouldRejectWhenAnchoredPrefixRouteCoversOnlyASubPath() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-users", "/admin/users", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    new AuthConfig(Require.NONE, null, null),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // /admin/reports is inside the namespace but below no anchored route, so it still falls
            // through: partial coverage is not coverage.
            assertHasError(errors, "/endpoint/routes",
                    "contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should not judge an exact route, which serves one address and never a namespace")
        void shouldNotJudgeExactRouteAgainstTheCoverageRule() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig root = anchoredEndpoint("root-ep", "ROOT", null,
                    new AuthConfig(Require.NONE, null, null),
                    exactRoute("root-entry", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, root),
                    topologyWith("ADMIN", "ROOT"));

            assertTrue(errors.isEmpty(),
                    () -> "an exact route matches one address and can swallow no namespace, got: " + errors);
        }

        @Test
        @DisplayName("Rule 5: Should reject an effective 'none' auth that weakens a non-none anchor floor")
        void shouldRejectWeakenedAuthFloor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", Require.BEARER)));
            RouteConfig weakening = RouteConfig.builder().id("r").anchor("api")
                    .match(match("/api/x", HttpMethod.GET))
                    .auth(new AuthConfig(Require.NONE, null, null)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null, weakening);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "weakens the anchor 'api' floor 'bearer'");
        }

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
                    .auth(new AuthConfig(Require.NONE, null, null)).build();
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

        @Test
        @DisplayName("Should accept a well-formed anchored configuration")
        void shouldAcceptValidAnchoredConfig() {
            GatewayConfig gateway = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", Require.BEARER)))
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("api-ep", "API", "api", null,
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
                    new AuthConfig(Require.NONE, null, null),
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
    @DisplayName("gRPC anchor-namespace containment exemption — rules 3 and 4 only (ADR-0007)")
    class GrpcNamespaceExemption {

        // The exemption is SCOPED, and this class pins both sides of that scope. Rules 3 and 4 judge a
        // route by where its OWN match key sits, which a service-rooted gRPC method path can never
        // satisfy — those two are waived (the first three cases below, the negative controls for the
        // inversion). Rule 4b judges the opposite geometry — what the route's prefix CONTAINS — and
        // selection is protocol-blind (RouteTable.lookup filters on path alone), so a gRPC route
        // swallowing an uncovered anchor namespace fails the boot exactly like an http one.

        private static final String ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.Echo";
        private static final String SECURE_ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.SecureEcho";

        private static RouteConfig grpcRoute(String id, String prefix, String anchorName, @Nullable AuthConfig auth) {
            return RouteConfig.builder()
                    .id(id)
                    .protocol(Protocol.GRPC)
                    .anchor(anchorName)
                    .match(match(prefix, HttpMethod.POST))
                    .auth(auth)
                    .build();
        }

        @Test
        @DisplayName("Rule 3 exemption: a gRPC route on a bare service path outside its declared anchor is accepted")
        void shouldExemptGrpcRouteFromDeclaredAnchorContainment() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc",
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", null));

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
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", null),
                    grpcRoute("grpc-bearer", SECURE_ECHO_PATH, "grpc", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "the two gRPC routes that failed the native IT boot must now validate cleanly, got: " + errors);
        }

        @Test
        @DisplayName("Rule 4 exemption: a gRPC route that would squat inside a catch-all anchor namespace is accepted")
        void shouldExemptGrpcRouteFromUndeclaredSquatterRule() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("root", anchor("root", "/", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", null,
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", ECHO_PATH, null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "a gRPC route inside a catch-all anchor namespace must not be rejected as a squatter, got: "
                            + errors);
        }

        @Test
        @DisplayName("Rule 4b applies to gRPC: a gRPC route whose prefix swallows an uncovered anchor namespace fails the boot")
        void shouldRejectGrpcRouteContainingUncoveredAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", "/", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // Selection is protocol-blind, so a plain GET /admin/x matches no route belonging to the
            // anchor, falls through to this gRPC route, and is served with its require: none posture
            // and its security_headers block — the anchor's bearer floor escaped for every address in
            // the namespace but the one exact string.
            assertHasError(errors, "/endpoint/routes",
                    "route 'grpc-echo' path_prefix '/' contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b coverage exception applies to gRPC too: a covered anchor namespace boots beside a gRPC catch-all")
        void shouldAcceptGrpcRouteContainingCoveredAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", "/", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // Same geometry as the case above with the one thing that matters changed: the anchor now
            // carries a prefix route at its own path_prefix, so longest-prefix selection gives it every
            // address in the namespace and the gRPC route alongside it is not a bypass.
            assertTrue(errors.isEmpty(),
                    () -> "a covered anchor namespace must not refuse a broader gRPC sibling route, got: " + errors);
        }

        @Test
        @DisplayName("Rule 4b applies to gRPC only as a container: a gRPC route on a bare service path swallows no anchor")
        void shouldNotJudgeBareServicePathGrpcRouteAgainstTheCoverageRule() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    new AuthConfig(Require.NONE, null, null),
                    grpcRoute("grpc-echo", ECHO_PATH, null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // The shipped gRPC descriptors ride bare service paths, which contain no anchor prefix, so
            // extending rule 4b to gRPC leaves them booting exactly as before.
            assertTrue(errors.isEmpty(),
                    () -> "a service-rooted gRPC route contains no anchor namespace and must not be refused, got: "
                            + errors);
        }

        @Test
        @DisplayName("Containment stays enforced for a websocket route outside its declared anchor namespace")
        void shouldStillEnforceContainmentForNonGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            RouteConfig websocket = RouteConfig.builder().id("ws").anchor("api")
                    .protocol(Protocol.WEBSOCKET)
                    .match(match("/billing", HttpMethod.GET))
                    .auth(new AuthConfig(Require.NONE, null, null)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null, websocket);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Auth floor stays enforced for a gRPC route: effective 'none' still weakens a non-none anchor floor")
        void shouldStillEnforceAuthFloorForGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", Require.BEARER)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc", null,
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", new AuthConfig(Require.NONE, null, null)));

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
                    "open", matrixAnchor("open", "/open", type, AccessLevel.PUBLIC, Require.BEARER)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        @ParameterizedTest
        @EnumSource(Require.class)
        @DisplayName("Rule public+auth: Should reject an access: public anchor for every auth-floor value in the vocabulary")
        void shouldRejectPublicAnchorForEveryAuthFloorValue(Require require) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        static Stream<Arguments> authenticatedAnchorsWithoutBackedFloor() {
            return Stream.of(
                    Arguments.of("no auth floor at all", null, "declares no non-'none' auth floor"),
                    Arguments.of("an explicit 'none' floor", Require.NONE, "declares no non-'none' auth floor"),
                    Arguments.of("a bearer floor with no token_validation issuer", Require.BEARER,
                            "access: authenticated bearer floor requires token_validation with at least one issuer"),
                    Arguments.of("a session floor with no oidc block", Require.SESSION,
                            "access: authenticated session floor requires an oidc block"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("authenticatedAnchorsWithoutBackedFloor")
        @DisplayName("Rules authenticated→floor and authenticated backing: Should reject an access: authenticated anchor without a backed auth floor")
        void shouldRejectAuthenticatedAnchorWithoutBackedFloor(String label, @Nullable Require require,
                String expectedDetail) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure", expectedDetail);
        }

        @Test
        @DisplayName("Should accept a type 'bff' anchor that is access: authenticated with a backed bearer floor")
        void shouldAcceptAuthenticatedBffWithBackedBearerFloor() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.AUTHENTICATED, Require.BEARER));

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
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, Require.BEARER),
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
                    .auth(new AuthConfig(Require.NONE, null, null))
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
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
        }

        private static EndpointConfig webSocketEndpoint(String alias, RouteConfig route) {
            return EndpointConfig.builder()
                    .id("ws-ep").enabled(true).baseUrl(alias)
                    .auth(new AuthConfig(Require.NONE, null, null))
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
            return new AuthConfig(Require.BEARER, null, null);
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
            WebSocketConfig websocket = new WebSocketConfig(List.of(), null);
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
            WebSocketConfig websocket = new WebSocketConfig(List.of(wildcard), null);
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
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), timeout);
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertHasError(errors, "/endpoint/routes", "idle_timeout_seconds must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a bearer WebSocket route with exact origins and a positive idle timeout")
        void shouldAcceptBearerWebSocketRouteWithExactOrigins() {
            GatewayConfig gateway = gatewayWithIssuer();
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), 60);
            EndpointConfig endpoint = webSocketEndpoint("WS",
                    webSocketRoute("chat", websocket, bearer()));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should not require allowed_origins for a non-bearer WebSocket route")
        void shouldNotRequireAllowedOriginsForNonBearerWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = webSocketEndpoint("WS", webSocketRoute("chat", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WS"));

            assertTrue(errors.isEmpty(),
                    () -> "a non-bearer WebSocket route may omit allowed_origins; got: " + errors);
        }

        @Test
        @DisplayName("Should reject a non-websocket route that declares a websocket block")
        void shouldRejectWebSocketBlockOnNonWebSocketRoute() {
            GatewayConfig gateway = validGateway().build();
            WebSocketConfig websocket = new WebSocketConfig(List.of("https://app.example.com"), 60);
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
    @DisplayName("The BFF OIDC/session fold rules (D1)")
    class BffOidcFoldRules {

        private static GatewayConfig gatewayWithOidc(OidcConfig oidc) {
            return validGateway().oidc(oidc).build();
        }

        @Test
        @DisplayName("Should accept a user_info block whose default_view claims all lie within the allowlist")
        void shouldAcceptUserInfoWithDefaultViewWithinAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .allowedClaims(List.of("sub", "name", "roles"))
                            .defaultView(List.of("sub", "name"))
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should accept a user_info block with an empty allowlist as the secure closed default")
        void shouldAcceptUserInfoWithEmptyAllowlistSecureDefault() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "an empty allowlist is the secure closed default and must not be an error, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a default_view claim that lies outside the operator allowlist")
        void shouldRejectDefaultViewClaimOutsideAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .allowedClaims(List.of("sub", "name"))
                            .defaultView(List.of("sub", "email"))
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/default_view", "not in allowed_claims");
        }

        @ParameterizedTest(name = "user_info path \"{0}\" is rejected as non-absolute")
        @ValueSource(strings = {"session/userinfo", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject a malformed user_info path that is not an absolute gateway path")
        void shouldRejectNonAbsoluteUserInfoPath(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/path", "must be an absolute gateway path");
        }

        @ParameterizedTest(name = "off-path login value \"{0}\" is rejected")
        @ValueSource(strings = {"login", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject an off-path login value")
        void shouldRejectOffPathLoginValue(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/login/path", "must be an absolute gateway path");
        }

        @Test
        @DisplayName("Should accept an absolute login path")
        void shouldAcceptAbsoluteLoginPath() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path("/session/login").build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "max_sessions = {0} is rejected")
        @ValueSource(ints = {0, -1, -1000})
        @DisplayName("Should reject a non-positive session max_sessions bound")
        void shouldRejectNonPositiveMaxSessions(int maxSessions) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().maxSessions(maxSessions).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/session/max_sessions", "must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a positive session max_sessions bound")
        void shouldAcceptPositiveMaxSessions() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().maxSessions(10000).build())
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
                    .session(OidcConfig.Session.builder()
                            .maxCookieSize(maxCookieSize).build())
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
                    .session(OidcConfig.Session.builder()
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        /**
         * A 48-byte {@code session.cookie_name} — more than double the 22-byte default — used to
         * pin the derived threshold against a configured name. Nothing in the schema bounds the
         * length, so this is an ordinary configuration rather than an extreme one.
         */
        private static final String LONG_COOKIE_NAME = "__Host-a-considerably-longer-session-cookie-name";

        /**
         * Matched positive/negative controls straddling the browser-safe <em>value</em> budget
         * <em>under the default configuration</em>
         * ({@code SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET}, 4019 = the 4096-byte
         * RFC 6265 6.1 header guarantee less the 77 bytes of cookie name and attributes the codec
         * emits for the default name and a four-digit {@code Max-Age}), every case inside the
         * accepted {@code 40..8192} range so the boot never fails and the warning is the only
         * variable. The sibling rows below vary the name and the TTL, where the threshold moves.
         * <p>
         * <strong>Both legs are required.</strong> The fire-only cases alone would pass against a
         * validator that warned unconditionally — which is the failure this record exists to avoid,
         * since a boot warning on every cookie-mode gateway is a warning operators learn to ignore.
         * The silent cases are what pin the threshold, and 4019 / 4020 straddle it exactly, so
         * moving the comparison off {@code BROWSER_SAFE_COOKIE_VALUE_BUDGET} in either direction —
         * or flipping it from {@code >} to {@code >=} — turns at least one case red.
         * <p>
         * <strong>4096 is a firing case, and that is the regression control.</strong> 4096 was once
         * the shipped default value budget, and comparing against it left the band
         * {@code 4020..4096} silent while the gateway emitted a {@code Set-Cookie} header past the
         * guarantee — the same silent, browser-side, unobservable drop the record exists to
         * announce. The default has since moved to the browser-safe 4019, but the row stays: a
         * validator that reintroduced a 4096 comparison would turn it green again, and this is what
         * keeps that impossible. It is now an ordinary declared budget like any other in the range.
         */
        static Stream<Arguments> browserGuaranteeThresholdControls() {
            return Stream.of(
                    Arguments.of("the floor is far below the browser guarantee", 40, false),
                    Arguments.of("one byte below the browser-safe value budget", 4018, false),
                    Arguments.of("exactly the browser-safe value budget", 4019, false),
                    Arguments.of("one byte above the browser-safe value budget", 4020, true),
                    Arguments.of("the retired 4096 default derives an over-guarantee header", 4096, true),
                    Arguments.of("the validated ceiling is above the guarantee", 8192, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1})")
        @MethodSource("browserGuaranteeThresholdControls")
        @DisplayName("Should warn only when the header derived from an accepted max_cookie_size exceeds the browser guarantee")
        void shouldWarnOnlyAboveTheBrowserGuarantee(String label, int maxCookieSize, boolean expectWarning) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a budget inside 40..8192 must be warned about, never refused, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize,
                        maxCookieSize + SealedSessionCookieCodec.DEFAULT_SET_COOKIE_HEADER_OVERHEAD);
            } else {
                assertNoBudgetWarning(maxCookieSize
                        + " derives a header inside the browser guarantee and must not warn");
            }
        }

        /**
         * The five-digit-{@code Max-Age} leg of the derived threshold. {@code toSetCookieHeader}
         * writes the <em>configured</em> lifetime into {@code Max-Age}, so a TTL past 9999 seconds
         * costs one more header byte than the default-configuration figure of 77 assumes: the
         * overhead becomes 78 and the browser-safe value budget 4018, not 4019.
         * <p>
         * <strong>4019 is the discriminating case.</strong> It is exactly the default-configuration
         * threshold and stays silent there, so a validator still comparing against a fixed 4019
         * passes the default row above and fails this one — which is the whole content of the
         * finding: the constant was standing in for a configurable quantity.
         */
        static Stream<Arguments> fiveDigitTtlThresholdControls() {
            return Stream.of(
                    Arguments.of("one byte below the TTL-widened browser-safe budget", 4017, false),
                    Arguments.of("exactly the TTL-widened browser-safe budget", 4018, false),
                    Arguments.of("the default-configuration threshold is already over-budget here", 4019, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1}, ttl_seconds = 86400)")
        @MethodSource("fiveDigitTtlThresholdControls")
        @DisplayName("Should widen the derived header by the Max-Age digits a five-digit ttl_seconds emits")
        void shouldDeriveTheThresholdFromTheResolvedTtl(String label, int maxCookieSize, boolean expectWarning) {
            // Arrange — 86400 seconds is five Max-Age digits against the default's four, so the
            // overhead is 22 name bytes + '=' + "; Max-Age=" + 5 digits + 40 attribute bytes = 78.
            // Every figure here is spelled independently of the production derivation on purpose.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .ttlSeconds(86_400)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize, maxCookieSize + 78);
            } else {
                assertNoBudgetWarning(maxCookieSize + " fits the 4096-byte guarantee at a 78-byte overhead");
            }
        }

        /**
         * The long-cookie-name leg of the derived threshold, and the one with no ceiling on it:
         * {@code session.cookie_name} is an unrestricted schema string, so the deviation from the
         * default 22-byte name is unbounded. The name below is 48 bytes, putting the overhead at 103
         * and the browser-safe value budget at 3993 — well under the default-configuration 4019,
         * which therefore has to warn here.
         */
        static Stream<Arguments> longCookieNameThresholdControls() {
            return Stream.of(
                    Arguments.of("one byte below the name-widened browser-safe budget", 3992, false),
                    Arguments.of("exactly the name-widened browser-safe budget", 3993, false),
                    Arguments.of("one byte above the name-widened browser-safe budget", 3994, true),
                    Arguments.of("the default-configuration threshold is already over-budget here", 4019, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1})")
        @MethodSource("longCookieNameThresholdControls")
        @DisplayName("Should widen the derived header by the configured cookie name's length")
        void shouldDeriveTheThresholdFromTheResolvedCookieName(String label, int maxCookieSize,
                boolean expectWarning) {
            // Arrange — 48 name bytes + '=' + "; Max-Age=" + 4 digits + 40 attribute bytes = 103.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .cookieName(LONG_COOKIE_NAME)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertEquals(48, LONG_COOKIE_NAME.length(),
                    "the arithmetic in this row depends on the fixture name's length");
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize, maxCookieSize + 103);
            } else {
                assertNoBudgetWarning(maxCookieSize + " fits the 4096-byte guarantee at a 103-byte overhead");
            }
        }

        /**
         * The omitted-key control pair — the path this guard was blind to.
         * <p>
         * The gateway emits the same {@code Set-Cookie} whether the operator wrote
         * {@code max_cookie_size} down or left it defaulted, so the check reads the <em>effective</em>
         * budget. It used to return early on an absent key, which meant the single most common
         * cookie-mode configuration — declare nothing — was never examined at all. With the default
         * then at 4096, every one of those gateways emitted a header 77 bytes past the guarantee and
         * nothing anywhere said so.
         * <p>
         * <strong>Both legs are required, and they fail for different reasons.</strong> The silent
         * leg fails if the default is ever moved back above the browser-safe budget, or if the
         * comparison is made unconditional — a warning on every default cookie-mode gateway is the
         * outcome that teaches operators to ignore the record. The firing leg fails if the early
         * return on an omitted key is ever restored: the budget is identical in both rows and only
         * the cookie name differs, so nothing but reading the effective budget can tell them apart.
         */
        @Test
        @DisplayName("Should not warn when max_cookie_size is omitted under the default cookie name")
        void shouldNotWarnOnOmittedBudgetUnderTheDefaultName() {
            // Arrange — no maxCookieSize at all: the shipped default (4019) applies, and under the
            // default 22-byte name plus a four-digit Max-Age the emitted header is 4019 + 77 = 4096,
            // exactly ON the guarantee rather than past it.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            assertNoBudgetWarning("the shipped default lands exactly on the browser guarantee under "
                    + "the default cookie name, and warning here would fire on every cookie-mode gateway");
        }

        @Test
        @DisplayName("Should warn when max_cookie_size is omitted under a longer cookie name")
        void shouldWarnOnOmittedBudgetUnderALongerCookieName() {
            // Arrange — the SAME omitted budget as the row above. Only the 48-byte cookie name
            // differs, widening the overhead to 103 so the default budget derives a 4122-byte header.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .cookieName(LONG_COOKIE_NAME).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(),
                    () -> "an undeliverable default is warned about, never refused, got: " + errors);
            assertBudgetWarning(SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET,
                    SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET + 103);
        }

        /**
         * Outside {@code session.mode: cookie} the gateway emits no sealed session
         * {@code Set-Cookie} at all, so there is no header for the browser guarantee to govern and
         * the record would be noise about a cookie that does not exist. The budget used here (8192)
         * is the validated ceiling — the loudest firing case in cookie mode — so a validator that
         * dropped the mode guard turns every row red.
         */
        @ParameterizedTest(name = "session.mode = {0} emits no browser-guarantee warning")
        @ValueSource(strings = {OidcConfig.Session.MODE_SERVER, "", "bearer-only"})
        @DisplayName("Should not warn about the browser guarantee outside cookie mode")
        void shouldNotWarnOutsideCookieMode(String mode) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(mode)
                            .maxCookieSize(SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Scoped to this key: server mode brings its own companion rules (a store is required),
            // which are not what this row is about.
            assertTrue(errors.stream().noneMatch(error -> "/oidc/session/max_cookie_size".equals(error.pointer())),
                    () -> "an in-range budget is never refused, whatever the mode, got: " + errors);
            assertNoBudgetWarning("mode '" + mode + "' emits no session Set-Cookie to warn about");
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is still range-checked outside cookie mode")
        @ValueSource(ints = {39, 8193})
        @DisplayName("Should range-check an explicit max_cookie_size in every mode, not only cookie mode")
        void shouldRangeCheckMaxCookieSizeOutsideCookieMode(int maxCookieSize) {
            // Only the WARNING is cookie-mode scoped: an out-of-range value is a misconfiguration
            // whatever the mode, and refusing it at boot is what stops it going live the moment the
            // mode is switched to cookie.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_SERVER)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/session/max_cookie_size", "must be between");
        }

        private void assertBudgetWarning(int expectedBudget, int expectedHeaderBytes) {
            String identifier = ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE
                    .resolveIdentifierString();
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(expectedBudget));
            // The DERIVED header size is what the guarantee governs, so the message must name it
            // — a template carrying only the value budget is the drift these controls pin.
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    String.valueOf(expectedHeaderBytes));
            // The remedy figure has to be this gateway's browser-safe budget, not a fixed 4019.
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(
                    SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE
                            - (expectedHeaderBytes - expectedBudget)));
        }

        private void assertNoBudgetWarning(String because) {
            String identifier = ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE
                    .resolveIdentifierString();
            assertTrue(TestLoggerFactory.getTestHandler()
                            .resolveLogMessagesContaining(TestLogLevel.WARN, identifier).isEmpty(),
                    () -> because + ", so " + identifier + " must not be emitted");
        }
    }

    /**
     * The {@code oidc.session.cookie_name} refusal: a declared name must keep the {@code __Host-}
     * guarantee, be a non-empty RFC 9110 token, and not collide with a gateway-owned cookie.
     * <p>
     * The accepted rows are the matched controls for the refused ones — a validator that refused
     * every declared name would pass the refusal rows alone. Every row runs in both session modes,
     * because both emit the configured name. The names are literals on purpose: each one IS the
     * boundary it pins, so a generated value could not stand in for it.
     */
    @Nested
    @DisplayName("The session cookie_name __Host- refusal")
    class SessionCookieNameRefusal {

        private static final String COOKIE_NAME_POINTER = "/oidc/session/cookie_name";
        private static final String PREFIX_FAILURE = "does not start with the case-sensitive '__Host-' prefix";
        private static final String TOKEN_FAILURE = "is not a non-empty RFC 9110 token";
        private static final String COLLISION_FAILURE = "collides with the gateway-owned cookie";

        private static final List<String> SESSION_MODES =
                List.of(OidcConfig.Session.MODE_COOKIE, OidcConfig.Session.MODE_SERVER);

        private static GatewayConfig gatewayWithCookieName(String mode, @Nullable String cookieName) {
            return validGateway().oidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().mode(mode).cookieName(cookieName).build())
                    .build()).build();
        }

        private static List<ConfigError> cookieNameErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> COOKIE_NAME_POINTER.equals(error.pointer())).toList();
        }

        private static Stream<Arguments> inEveryMode(Stream<Arguments> rows) {
            List<Arguments> materialized = rows.toList();
            return SESSION_MODES.stream().flatMap(mode -> materialized.stream()
                    .map(row -> {
                        Object[] values = row.get();
                        Object[] withMode = new Object[values.length + 1];
                        withMode[0] = mode;
                        System.arraycopy(values, 0, withMode, 1, values.length);
                        return Arguments.of(withMode);
                    }));
        }

        static Stream<Arguments> acceptedCookieNames() {
            return inEveryMode(Stream.of(
                    Arguments.of("the omitted key resolves to the default", null),
                    Arguments.of("the default name", SessionCookieCodec.DEFAULT_COOKIE_NAME),
                    Arguments.of("a custom well-formed __Host- name", "__Host-orders-session")));
        }

        /**
         * Each refused name with the failure fragment the refusal must name and the rendering the
         * message must echo. The rendering is spelled out independently of the production escaping:
         * CR and LF become {@code \\u000D} / {@code \\u000A}, everything printable is echoed as typed.
         */
        static Stream<Arguments> refusedCookieNames() {
            return inEveryMode(Stream.of(
                    Arguments.of("missing prefix", "sheriff-session", PREFIX_FAILURE, "sheriff-session"),
                    Arguments.of("wrong-case prefix", "__host-x", PREFIX_FAILURE, "__host-x"),
                    Arguments.of("__Secure- prefix", "__Secure-sheriff-session", PREFIX_FAILURE,
                            "__Secure-sheriff-session"),
                    Arguments.of("prefix alone", "__Host-", "is the bare '__Host-' prefix", "__Host-"),
                    Arguments.of("empty", "", TOKEN_FAILURE, ""),
                    Arguments.of("blank", "   ", TOKEN_FAILURE, "   "),
                    Arguments.of("carrying ';'", "__Host-a;b", TOKEN_FAILURE, "__Host-a;b"),
                    Arguments.of("carrying a space", "__Host-a b", TOKEN_FAILURE, "__Host-a b"),
                    Arguments.of("carrying CR/LF", "__Host-a\r\nSet-Cookie: x", TOKEN_FAILURE,
                            "__Host-a\\u000D\\u000ASet-Cookie: x"),
                    Arguments.of("the binding cookie name", BindingCookieCodec.COOKIE_NAME, COLLISION_FAILURE,
                            BindingCookieCodec.COOKIE_NAME),
                    Arguments.of("the logout-state cookie name", RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME,
                            COLLISION_FAILURE, RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME)));
        }

        @ParameterizedTest(name = "[{0}] {1} is accepted")
        @MethodSource("acceptedCookieNames")
        @DisplayName("Should accept an omitted, default or well-formed __Host- session cookie name")
        void shouldAcceptSafeCookieName(String mode, String label, @Nullable String cookieName) {
            GatewayConfig gateway = gatewayWithCookieName(mode, cookieName);

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(cookieNameErrors(errors).isEmpty(),
                    () -> label + " must not be refused in mode '" + mode + "', got: " + errors);
        }

        @ParameterizedTest(name = "[{0}] {1} is refused")
        @MethodSource("refusedCookieNames")
        @DisplayName("Should refuse a session cookie name that drops the __Host- guarantee, is no token, or collides")
        void shouldRefuseUnsafeCookieName(String mode, String label, String cookieName, String failure,
                String expectedRendering) {
            GatewayConfig gateway = gatewayWithCookieName(mode, cookieName);

            List<ConfigError> refusals = cookieNameErrors(validator.validate(gateway, List.of(), topologyWith()));

            assertEquals(1, refusals.size(),
                    () -> label + " must yield exactly one cookie_name violation in mode '" + mode + "', got: "
                            + refusals);
            String message = refusals.getFirst().message();
            assertAll(label,
                    () -> assertEquals("gateway.yaml", refusals.getFirst().file()),
                    () -> assertTrue(message.contains(failure),
                            () -> "expected the failure '" + failure + "' in: " + message),
                    () -> assertTrue(message.contains("'" + expectedRendering + "'"),
                            () -> "expected the escaped echo '" + expectedRendering + "' in: " + message),
                    () -> assertTrue(message.chars().noneMatch(character -> character < 0x20),
                            () -> "the echoed value must carry no control character: " + message));
        }
    }

    @Nested
    @DisplayName("The fail-closed 'profile: minimal' refusal (ADR-0024)")
    class SecurityProfileMinimalRefusal {

        private static final String MINIMAL_PROFILE = "minimal";
        private static final String REFUSAL_MESSAGE = "resolves inbound-filter profile 'minimal'";

        private static RouteConfig profiledRoute(String id, String prefix, String anchorName, String profile,
                @Nullable AuthConfig auth) {
            return RouteConfig.builder()
                    .id(id)
                    .anchor(anchorName)
                    .match(match(prefix, HttpMethod.GET))
                    .auth(auth)
                    .securityFilter(profile == null ? null
                            : SecurityFilterConfig.builder().profile(profile).build())
                    .build();
        }

        private static AnchorConfig anchorWithProfile(String name, String prefix, AnchorType type,
                AccessLevel access, @Nullable Require require, String profile) {
            return AnchorConfig.builder()
                    .name(name)
                    .pathPrefix(prefix)
                    .type(type)
                    .access(access)
                    .auth(require == null ? null : new AuthConfig(require, null, null))
                    .securityFilter(
                            SecurityFilterConfig.builder().profile(profile).build())
                    .build();
        }

        /** A route declaring a {@code security_filter} block that omits {@code profile}. */
        private static RouteConfig profileLessFilterRoute(String id, String prefix, String anchorName) {
            return RouteConfig.builder()
                    .id(id)
                    .anchor(anchorName)
                    .match(match(prefix, HttpMethod.GET))
                    .auth(null)
                    .securityFilter(
                            SecurityFilterConfig.builder().maxBodyBytes(4096).build())
                    .build();
        }

        private static GatewayConfig gatewayWithGlobalProfile(AnchorConfig anchorConfig, String globalProfile) {
            return validGateway()
                    .anchors(Map.of(anchorConfig.name(), anchorConfig))
                    .securityDefaults(new SecurityDefaultsConfig(globalProfile,
                            null, null, null))
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
        }

        @Test
        @DisplayName("Should reject 'minimal' on a route under an access: authenticated anchor")
        void shouldRejectMinimalOnAuthenticatedAnchor() {
            // Arrange — the anchor's bearer floor makes every route under it effectively authenticated.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", MINIMAL_PROFILE, null));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
            assertHasError(errors, "/endpoint/routes", "effective access level is 'authenticated'");
        }

        @Test
        @DisplayName("Should reject 'minimal' on a route under a type: bff anchor, naming the anchor-type dimension")
        void shouldRejectMinimalOnBffAnchor() {
            // Arrange — a bff anchor is required to be access: authenticated (ADR-0013), so a matrix-clean
            // bff fixture necessarily trips both refusal dimensions; the anchor-type one must be named.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("shell", "/shell", AnchorType.BFF, AccessLevel.AUTHENTICATED, Require.BEARER));
            EndpointConfig endpoint = anchoredEndpoint("bff", "BFF", "shell", null,
                    profiledRoute("shell-view", "/shell/view", "shell", MINIMAL_PROFILE, null));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("BFF"));

            // Assert
            assertHasError(errors, "/endpoint/routes", "anchor 'shell' is type 'bff'");
        }

        @Test
        @DisplayName("Should reject 'minimal' on a public anchor whose route strengthens the auth floor")
        void shouldRejectMinimalOnRouteStrengtheningPublicAnchorFloor() {
            // Arrange — the under-refusal case: the anchor stays access: public, so reading the anchor's
            // static access would let this route through, but the route's own bearer floor makes it
            // effectively authenticated.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null));
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    new AuthConfig(Require.NONE, null, null),
                    profiledRoute("open-secured", "/open/secured", "open", MINIMAL_PROFILE,
                            new AuthConfig(Require.BEARER, null, null)));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
            assertHasError(errors, "/endpoint/routes", "effective access level is 'authenticated'");
        }

        @Test
        @DisplayName("Should accept 'minimal' on a genuinely public, effectively-unauthenticated route")
        void shouldAcceptMinimalOnPublicUnauthenticatedRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("open",
                    matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    new AuthConfig(Require.NONE, null, null),
                    profiledRoute("open-read", "/open/read", "open", MINIMAL_PROFILE, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a gateway-wide security_defaults 'minimal' inherited by an authenticated route")
        void shouldRejectGlobalMinimalInheritedByAuthenticatedRoute() {
            // Arrange — the route declares no security_filter at all; 'minimal' reaches it through the
            // gateway-wide fallback, which is the same violation as declaring it per route.
            GatewayConfig gateway = gatewayWithGlobalProfile(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER),
                    MINIMAL_PROFILE);
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", null, null));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
        }

        @Test
        @DisplayName("Should accept a gateway-wide 'minimal' that no authenticated or BFF route inherits")
        void shouldAcceptGlobalMinimalOnPublicRoutesOnly() {
            GatewayConfig gateway = validGateway()
                    .anchors(Map.of("open",
                            matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, null)))
                    .securityDefaults(new SecurityDefaultsConfig(MINIMAL_PROFILE,
                            null, null, null))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    new AuthConfig(Require.NONE, null, null),
                    profiledRoute("open-read", "/open/read", "open", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should reject an anchor-declared 'minimal' inherited by a block-less authenticated route")
        void shouldRejectAnchorDeclaredMinimalInheritedByAuthenticatedRoute() {
            // Arrange — the middle leg of the resolution chain: the route declares no security_filter,
            // so 'minimal' reaches it from the anchor's own block rather than per route or gateway-wide.
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchorWithProfile("secure", "/secure",
                    AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER, MINIMAL_PROFILE));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", null, null));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertHasError(errors, "/endpoint/routes", REFUSAL_MESSAGE);
            assertHasError(errors, "/endpoint/routes", "effective access level is 'authenticated'");
        }

        @Test
        @DisplayName("Should accept an anchor-declared 'minimal' on a genuinely public, unauthenticated route")
        void shouldAcceptAnchorDeclaredMinimalOnPublicUnauthenticatedRoute() {
            // Arrange — the mirror-image of the refusal above: no refusal dimension applies
            GatewayConfig gateway = gatewayWithAnchors(Map.of("open", anchorWithProfile("open", "/open",
                    AnchorType.PROXY, AccessLevel.PUBLIC, null, MINIMAL_PROFILE)));
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    new AuthConfig(Require.NONE, null, null),
                    profiledRoute("open-read", "/open/read", "open", null, null));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should replace the anchor's whole security_filter block rather than merge its profile")
        void shouldReplaceAnchorFilterBlockWholesaleRatherThanMergeProfile() {
            // Arrange — the route declares a security_filter block WITHOUT a profile under an anchor
            // declaring 'minimal'. The block is replaced wholesale, so the profile falls back to the
            // gateway-wide 'strict' and never to the anchor's 'minimal' — a merge would refuse here.
            GatewayConfig gateway = gatewayWithGlobalProfile(anchorWithProfile("secure", "/secure",
                    AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER, MINIMAL_PROFILE), "strict");
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profileLessFilterRoute("secure-read", "/secure/read", "secure"));

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "profile ''{0}'' is accepted on an authenticated route")
        @ValueSource(strings = {"strict", "lenient", "STRICT", "Lenient"})
        @DisplayName("Should accept a non-'minimal' profile on an authenticated route, case-insensitively")
        void shouldAcceptNonMinimalProfileOnAuthenticatedRoute(String profile) {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", profile, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report the refusal alongside an unrelated violation in one pass")
        void shouldAggregateRefusalWithUnrelatedViolation() {
            // Arrange — an unsupported version plus a refused 'minimal' route: the pass must report both.
            GatewayConfig gateway = validGateway()
                    .version(2)
                    .anchors(Map.of("secure",
                            matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED,
                                    Require.BEARER)))
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", MINIMAL_PROFILE, null));

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
                    matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, Require.BEARER));
            EndpointConfig endpoint = anchoredEndpoint("api", "API", "secure", null,
                    profiledRoute("secure-read", "/secure/read", "secure", MINIMAL_PROFILE, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            ConfigError refusal = errors.stream()
                    .filter(error -> error.message().contains(REFUSAL_MESSAGE))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a 'minimal' refusal error, got: " + errors));
            assertAll(
                    () -> assertTrue(refusal.message().contains("secure-read"), "names the route"),
                    () -> assertTrue(refusal.message().contains("declare profile 'strict' or 'lenient'"),
                            "names the remedy"),
                    () -> assertFalse(refusal.message().contains("https://idp.example"), "echoes no configured scalar value"));
        }
    }

    @Nested
    @DisplayName("The GET-body opt-in resolves fail-closed when omitted")
    class GetBodyOptInDefault {

        @Test
        @DisplayName("Should resolve an omitted allow_get_with_content_length_body to false")
        void shouldResolveOmittedKnobToFalse() {
            SecurityDefaultsConfig securityDefaults = new SecurityDefaultsConfig("strict", null, null, null);

            assertFalse(securityDefaults.effectiveAllowGetWithContentLengthBody(),
                    "an omitted knob must resolve fail-closed, preserving every framing rejection "
                            + "the gateway made before the key existed");
        }

        @Test
        @DisplayName("Should resolve an explicitly declared value")
        void shouldResolveDeclaredKnob() {
            assertAll(
                    () -> assertTrue(new SecurityDefaultsConfig("strict", null, true, null)
                            .effectiveAllowGetWithContentLengthBody(), "declared true resolves true"),
                    () -> assertFalse(new SecurityDefaultsConfig("strict", null, false, null)
                            .effectiveAllowGetWithContentLengthBody(), "declared false resolves false"));
        }

        @Test
        @DisplayName("Should accept a gateway declaring the opt-in without any validation error")
        void shouldAcceptDeclaredOptIn() {
            GatewayConfig gateway = validGateway()
                    .securityDefaults(new SecurityDefaultsConfig("strict", null, true, null))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains("security_defaults")),
                    () -> "the opt-in carries no cross-cutting boot rule, got: " + errors);
        }
    }

    @Nested
    @DisplayName("The add-only 'asset_defaults.content_types' boot refusal")
    class AssetContentTypesAddOnlyRefusal {

        private static final String POINTER = "/asset_defaults/content_types";
        private static final String REFUSAL_MESSAGE = "the built-in content-type mappings are immutable";

        private static GatewayConfig gatewayWithContentTypes(Map<String, String> contentTypes) {
            return validGateway()
                    .assetDefaults(new AssetDefaultsConfig(contentTypes))
                    .build();
        }

        @Test
        @DisplayName("Should accept an entry for an extension the gateway does not map")
        void shouldAcceptUnmappedExtension() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("webmanifest", "application/manifest+json"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an unmapped extension is exactly what the block exists for, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an absent asset_defaults block")
        void shouldAcceptAbsentBlock() {
            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an omitted block is never refused, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an empty content_types map")
        void shouldAcceptEmptyMap() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an empty block is the add-only no-op, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse an entry naming a built-in extension, naming that extension")
        void shouldRefuseBuiltInExtension() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("png", "image/jpeg"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE),
                    () -> assertHasError(errors, POINTER, "png"));
        }

        @Test
        @DisplayName("Should refuse the stored-XSS lever 'svg: text/html' the add-only ruling exists to remove")
        void shouldRefuseSvgRemappedToHtml() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("svg", "text/html; charset=utf-8"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE),
                    () -> assertHasError(errors, POINTER, "svg"));
        }

        @Test
        @DisplayName("Should refuse a built-in extension declared in upper case")
        void shouldRefuseBuiltInExtensionRegardlessOfCase() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("SVG", "text/html; charset=utf-8"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, POINTER, "svg");
        }

        @Test
        @DisplayName("Should collect every offending entry in one pass rather than failing on the first")
        void shouldCollectEveryOffendingEntry() {
            Map<String, String> contentTypes = new LinkedHashMap<>();
            contentTypes.put("svg", "text/html; charset=utf-8");
            contentTypes.put("png", "image/jpeg");
            contentTypes.put("webmanifest", "application/manifest+json");
            GatewayConfig gateway = gatewayWithContentTypes(contentTypes);

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertEquals(2, errors.stream().filter(e -> e.pointer().contains(POINTER)).count(),
                            "both built-in collisions are reported, the legal addition is not"),
                    () -> assertHasError(errors, POINTER, "svg"),
                    () -> assertHasError(errors, POINTER, "png"));
        }
    }

    /**
     * The value half of {@code asset_defaults.content_types}. The declared value is served verbatim
     * as the asset's {@code Content-Type} response header, so a value that is not a well-formed
     * media type — a CR/LF-bearing one above all — must be refused at boot rather than misbehaving
     * per request inside the response write. This is a well-formedness gate only: it ranks no media
     * type as safe or unsafe, because the add-only key rule already fences the built-in,
     * script-executable extensions.
     */
    @Nested
    @DisplayName("The 'asset_defaults.content_types' value well-formedness refusal")
    class AssetContentTypeValueRefusal {

        private static final String POINTER = "/asset_defaults/content_types";
        private static final String REFUSAL_MESSAGE = "is not a well-formed media type";
        private static final String EXTENSION = "webmanifest";

        private static GatewayConfig gatewayWithValue(String value) {
            return validGateway()
                    .assetDefaults(new AssetDefaultsConfig(Map.of(EXTENSION, value)))
                    .build();
        }

        private static List<ConfigError> valueErrors(List<ConfigError> errors) {
            return errors.stream()
                    .filter(error -> error.pointer().contains(POINTER) && error.message().contains(REFUSAL_MESSAGE))
                    .toList();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "application/manifest+json",
                "text/plain; charset=utf-8",
                "application/vnd.api+json",
                "font/collection",
                "application/x-7z-compressed"})
        @DisplayName("Should accept a well-formed media type, with or without parameters")
        void shouldAcceptWellFormedMediaType(String value) {
            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertTrue(valueErrors(errors).isEmpty(),
                    () -> "'" + value + "' is a well-formed media type, got: " + errors);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "notamediatype",
                "",
                "   ",
                "application/",
                "/json",
                "text/plain; charset",
                "text/plain extra"})
        @DisplayName("Should refuse a value that is not a 'type/subtype' media type")
        void shouldRefuseMalformedValue(String value) {
            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "'" + value + "' is not a media type and must be refused at boot, got: " + errors);
        }

        @Test
        @DisplayName("Should name the offending extension and value, so the operator can find the typo")
        void shouldNameExtensionAndValue() {
            List<ConfigError> errors = validator.validate(gatewayWithValue("notamediatype"), List.of(),
                    topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, EXTENSION),
                    () -> assertHasError(errors, POINTER, "notamediatype"),
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE));
        }

        @Test
        @DisplayName("Should refuse a CR/LF-bearing value — the response-header-injection shape")
        void shouldRefuseHeaderInjectionValue() {
            String injected = "text/plain\r\nX-Injected: 1";

            List<ConfigError> errors = validator.validate(gatewayWithValue(injected), List.of(), topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "a CR/LF-bearing value never reaches a response header, got: " + errors);
        }

        @Test
        @DisplayName("Should escape the echoed value, so the refusal cannot forge a line in the boot ERROR log")
        void shouldNotEchoRawControlCharacters() {
            String injected = "text/plain\r\nX-Injected: 1";

            List<ConfigError> errors = validator.validate(gatewayWithValue(injected), List.of(), topologyWith());

            ConfigError refusal = valueErrors(errors).getFirst();
            assertAll(
                    () -> assertFalse(refusal.message().contains("\r"),
                            () -> "a raw CR would forge a log line: " + refusal.message()),
                    () -> assertFalse(refusal.message().contains("\n"),
                            () -> "a raw LF would forge a log line: " + refusal.message()),
                    () -> assertTrue(refusal.message().contains("\\u000D\\u000A"),
                            () -> "the operator still sees what they typed, escaped: " + refusal.message()));
        }

        @Test
        @DisplayName("Should refuse a trailing newline an '^...$'-anchored regex would have admitted")
        void shouldRefuseTrailingNewline() {
            List<ConfigError> errors = validator.validate(gatewayWithValue("text/plain\n"), List.of(),
                    topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "the match is whole-input, so a trailing newline is refused, got: " + errors);
        }

        @Test
        @DisplayName("Should accept a media type carrying several parameters")
        void shouldAcceptSeveralParameters() {
            String value = "multipart/form-data; boundary=abc123; charset=utf-8";

            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertTrue(valueErrors(errors).isEmpty(),
                    () -> "a multi-parameter media type is well-formed, got: " + errors);
        }

        /**
         * Guards the possessive quantifiers in {@code ConfigValidator.MEDIA_TYPE}. With the greedy
         * form this pattern previously carried, the parameter-list repetition made the regex engine
         * recurse once per iteration and retain a backtracking position for each, so an input with
         * enough parameters exhausted the stack ({@code StackOverflowError}) instead of matching.
         * The possessive form scans flat, so the same input is simply accepted.
         * <p>
         * The input is deliberately <em>well-formed</em>: the assertion is that a long value is
         * decided normally, and any stack exhaustion surfaces as a thrown error failing this test
         * rather than as a boot-time crash on an operator's config.
         */
        @Test
        @DisplayName("Should decide a value carrying very many parameters without exhausting the stack")
        void shouldNotRecurseOnLongParameterList() {
            String value = "text/plain" + "; a=b".repeat(50_000);

            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertTrue(valueErrors(errors).isEmpty(),
                    () -> "a long but well-formed parameter list is accepted, got: " + errors.size() + " error(s)");
        }

        /**
         * The malformed counterpart: a long parameter list terminated by a character outside the
         * token set must be refused, again without the engine recursing per parameter.
         */
        @Test
        @DisplayName("Should refuse a long malformed value without exhausting the stack")
        void shouldRefuseLongMalformedValueWithoutRecursing() {
            String value = "text/plain" + "; a=b".repeat(50_000) + "\r\nX-Injected: 1";

            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "a long CR/LF-bearing value is refused, got: " + errors.size() + " error(s)");
        }

        @Test
        @DisplayName("Should collect every offending value in one pass rather than failing on the first")
        void shouldCollectEveryOffendingValue() {
            Map<String, String> contentTypes = new LinkedHashMap<>();
            contentTypes.put("webmanifest", "application/manifest+json");
            contentTypes.put("avifs", "notamediatype");
            contentTypes.put("jxl", "image/jxl\r\nX-Injected: 1");
            GatewayConfig gateway = validGateway()
                    .assetDefaults(new AssetDefaultsConfig(contentTypes))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertEquals(2, valueErrors(errors).size(),
                            "both malformed values are reported, the well-formed one is not"),
                    () -> assertHasError(errors, POINTER, "avifs"),
                    () -> assertHasError(errors, POINTER, "jxl"));
        }
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
            List<ConfigError> errors = validateForward(
                    new ForwardConfig(List.of("Accept"), List.of("Cookie"), null, null, Map.of()));

            assertAll("the refusal names the route and the dimension",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertHasError(errors, "/endpoint/routes", "proxied"),
                    () -> assertHasError(errors, "/endpoint/routes", "headers_allow"),
                    () -> assertHasError(errors, "/endpoint/routes", "headers_deny"));
        }

        @Test
        @DisplayName("Should refuse a route declaring both query_allow and query_deny")
        void shouldRefuseBothQueryLists() {
            List<ConfigError> errors = validateForward(
                    new ForwardConfig(null, null, List.of("page"), List.of("debug"), Map.of()));

            assertAll("the refusal names the route and the dimension",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertHasError(errors, "/endpoint/routes", "proxied"),
                    () -> assertHasError(errors, "/endpoint/routes", "query_allow"),
                    () -> assertHasError(errors, "/endpoint/routes", "query_deny"));
        }

        @Test
        @DisplayName("Should report both dimensions in one pass rather than stopping at the first")
        void shouldReportBothDimensionsInOnePass() {
            List<ConfigError> errors = validateForward(new ForwardConfig(
                    List.of("Accept"), List.of("Cookie"), List.of("page"), List.of("debug"), Map.of()));

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
            List<ConfigError> errors = validateForward(
                    new ForwardConfig(List.of(), List.of("Cookie"), null, null, Map.of()));

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
                    Arguments.of("positive-list on both dimensions",
                            new ForwardConfig(List.of("Accept"), null, List.of("page"), null, Map.of())),
                    Arguments.of("negative-list on both dimensions",
                            new ForwardConfig(null, List.of("Cookie"), null, List.of("debug"), Map.of())),
                    Arguments.of("positive-list headers, negative-list query",
                            new ForwardConfig(List.of("Accept"), null, null, List.of("debug"), Map.of())),
                    Arguments.of("negative-list headers, positive-list query",
                            new ForwardConfig(null, List.of("Cookie"), List.of("page"), null, Map.of())),
                    Arguments.of("forward-all on both dimensions",
                            new ForwardConfig(null, null, null, null, Map.of())),
                    Arguments.of("declared-empty positive-lists, no deny lists",
                            new ForwardConfig(List.of(), null, List.of(), null, Map.of())));
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
            List<ConfigError> errors = validateForward(
                    new ForwardConfig(List.of("Accept"), List.of("Cookie"), null, null, Map.of()));

            assertTrue(errors.getFirst().message().contains("declares both"),
                    () -> "the message must state that both lists were declared, got: "
                            + errors.getFirst().message());
        }
    }

    @Nested
    @DisplayName("asset.index and asset.fallback (AS-12)")
    class AssetIndexAndFallback {

        private List<ConfigError> validateAsset(AssetConfig asset) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    new AuthConfig(Require.NONE, null, null),
                    assetRoute("spa", "/assets", "assets", asset, HttpMethod.GET));
            return validator.validate(gateway, List.of(endpoint), topologyWith("WEB", "SECONDARY"));
        }

        private static AssetConfig directoryWith(@Nullable String index, @Nullable String fallback) {
            return AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).directory("/srv/spa")
                    .index(index).fallback(fallback).build();
        }

        @Test
        @DisplayName("Should accept a directory asset declaring a single-segment index and fallback")
        void shouldAcceptDirectoryIndexAndFallback() {
            List<ConfigError> errors = validateAsset(directoryWith("index.html", "index.html"));

            assertTrue(errors.isEmpty(), () -> "a directory source may declare index and fallback, got: " + errors);
        }

        @ParameterizedTest(name = "asset.{0} on source: upstream")
        @ValueSource(strings = {"index", "fallback"})
        @DisplayName("Should refuse index or fallback on an upstream asset source")
        void shouldRefuseOnUpstreamSource(String key) {
            AssetConfig.AssetConfigBuilder asset = AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                    .upstream("SECONDARY");
            if ("index".equals(key)) {
                asset.index("index.html");
            } else {
                asset.fallback("index.html");
            }

            List<ConfigError> errors = validateAsset(asset.build());

            assertHasError(errors, "/endpoint/routes",
                    "asset route 'spa' declares asset." + key + " but its source is 'upstream'");
        }

        @ParameterizedTest(name = "invalid file name ''{0}''")
        @ValueSource(strings = {"", ".", "..", "../index.html", "sub/index.html", "sub\\index.html", "index\0.html",
                "index\r.html"})
        @DisplayName("Should refuse an index or fallback that is not a single file-name segment")
        void shouldRefuseInvalidFileName(String name) {
            List<ConfigError> indexErrors = validateAsset(directoryWith(name, null));
            List<ConfigError> fallbackErrors = validateAsset(directoryWith(null, name));

            assertAll("both keys are held to the single-segment rule",
                    () -> assertHasError(indexErrors, "/endpoint/routes",
                            "asset route 'spa' asset.index must be a single file-name segment"),
                    () -> assertHasError(fallbackErrors, "/endpoint/routes",
                            "asset route 'spa' asset.fallback must be a single file-name segment"));
        }

        @Test
        @DisplayName("Should never echo the offending file-name value into the refusal message")
        void shouldNotEchoOffendingValue() {
            List<ConfigError> errors = validateAsset(directoryWith("../../etc/passwd", null));

            assertTrue(errors.stream().noneMatch(error -> error.message().contains("etc/passwd")),
                    () -> "the refused value must not reach the operator log, got: " + errors);
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

    @Nested
    @DisplayName("token_relay: false must not re-admit Authorization through headers_allow")
    class TokenRelayForwardConflict {

        private static final String CONFLICT_MESSAGE = "resolves auth.token_relay: false";

        private List<ConfigError> validateSessionRoute(@Nullable Boolean tokenRelay, List<String> headersAllow) {
            RouteConfig relayRoute = RouteConfig.builder().id("relay").match(match("/relay", HttpMethod.GET))
                    .forward(ForwardConfig.builder().headersAllow(headersAllow).build()).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("app").enabled(true).baseUrl("APP")
                    .auth(new AuthConfig(Require.SESSION, tokenRelay, null)).routes(List.of(relayRoute)).build();
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

    @Nested
    @DisplayName("endpoint.scopes must act on at least one authenticated route")
    class EndpointScopesNeedAuthentication {

        private static final String SCOPES_MESSAGE = "declares scopes but every one of its routes resolves require: none";

        private EndpointConfig scopedEndpoint(List<String> scopes, RouteConfig... routes) {
            return EndpointConfig.builder().id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig(Require.NONE, null, null)).scopes(scopes).routes(List.of(routes)).build();
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
                    .auth(new AuthConfig(Require.BEARER, null, null)).build();

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

    @Nested
    @DisplayName("oidc.login.default_return_url must be same-origin with redirect_uri")
    class DefaultReturnUrl {

        private static final String REDIRECT_URI = "https://gateway.example.com/callback";
        private static final String POINTER = "/oidc/login/default_return_url";

        private List<ConfigError> validateDefault(@Nullable String redirectUri, String defaultReturnUrl) {
            OidcConfig oidc = OidcConfig.builder().redirectUri(redirectUri)
                    .login(new OidcConfig.Login(null, defaultReturnUrl)).build();
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith());
        }

        private boolean refused(List<ConfigError> errors) {
            return errors.stream().anyMatch(error -> POINTER.equals(error.pointer()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "/app/home?tab=a&b", "https://gateway.example.com/app",
                "https://GATEWAY.example.com:443/app"})
        @DisplayName("Should accept a same-origin relative path or absolute URL")
        void shouldAcceptSameOrigin(String value) {
            List<ConfigError> errors = validateDefault(REDIRECT_URI, value);

            assertFalse(refused(errors), () -> value + " is same-origin, got: " + errors);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://evil.example/app", "//evil.example/app", "/\\evil.example",
                "http://gateway.example.com/app", "https://gateway.example.com:8443/app", ""})
        @DisplayName("Should refuse a cross-origin, scheme-relative, backslash or blank value")
        void shouldRefuseCrossOrigin(String value) {
            List<ConfigError> errors = validateDefault(REDIRECT_URI, value);

            assertHasError(errors, POINTER, "is not same-origin with redirect_uri");
        }

        @Test
        @DisplayName("Should accept a relative path but refuse an absolute URL when no redirect_uri is configured")
        void shouldJudgeAgainstAbsentRedirectUri() {
            assertAll(
                    () -> assertFalse(refused(validateDefault(null, "/home"))),
                    () -> assertTrue(refused(validateDefault(null, "https://gateway.example.com/home"))));
        }

        @Test
        @DisplayName("Should not refuse an omitted default_return_url")
        void shouldAcceptOmittedValue() {
            OidcConfig oidc = OidcConfig.builder().redirectUri(REDIRECT_URI)
                    .login(new OidcConfig.Login("/auth/login", null)).build();

            List<ConfigError> errors = validator.validate(validGateway().oidc(oidc).build(), List.of(),
                    topologyWith());

            assertFalse(refused(errors));
        }
    }
}
