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
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the terminal-action rules the {@link ConfigValidator} runs through
 * {@link TerminalActionRules}: the terminal-action / anchor-type consistency matrix and the
 * asset-source fields (ADR-0014), and the {@code asset.index} / {@code asset.fallback} file-name
 * confinement (AS-12), and the {@code keep_identity_headers} target rule. The redirect half of the terminal-action rule is covered by
 * {@link TerminalActionRulesRedirectTest}.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — terminal action")
class TerminalActionRulesTest {

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

    private static RouteConfig anchoredRoute(String id, String prefix, String anchorName, HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
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
    @DisplayName("Terminal-action / anchor-type consistency (ADR-0014)")
    class TerminalActionConsistency {

        @Test
        @DisplayName("Should accept a directory asset route under an asset anchor")
        void shouldAcceptDirectoryAssetUnderAssetAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    auth(Require.NONE),
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
                    auth(Require.NONE),
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
                    auth(Require.NONE),
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
                    auth(Require.NONE),
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
                    .auth(auth(Require.NONE))
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
                    auth(Require.NONE),
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
                    auth(Require.NONE),
                    assetRoute("bundle", "/assets", "assets", directoryAsset(null), HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("WEB"));

            assertHasError(errors, "/endpoint/routes", "no directory root");
        }
    }

    @Nested
    @DisplayName("asset.index and asset.fallback (AS-12)")
    class AssetIndexAndFallback {

        private List<ConfigError> validateAsset(AssetConfig asset) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = anchoredEndpoint("web", "WEB", "assets",
                    auth(Require.NONE),
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
    @DisplayName("keep_identity_headers target")
    class KeepIdentityHeadersTarget {

        private static final String ROUTE_ID = "subject";

        private static final String REFUSAL = "route '" + ROUTE_ID + "' declares keep_identity_headers but ";

        /**
         * Every route kind the key is judged on: the four that relay upstream response headers and
         * accept it, and the three that relay none and refuse it.
         */
        enum RouteKind {
            HTTP_PROXY(true),
            GRAPHQL_PROXY(true),
            GRPC_PROXY(true),
            UPSTREAM_ASSET(true),
            REDIRECT(false),
            DIRECTORY_ASSET(false),
            WEBSOCKET(false);

            private final boolean relaysUpstreamHeaders;

            RouteKind(boolean relaysUpstreamHeaders) {
                this.relaysUpstreamHeaders = relaysUpstreamHeaders;
            }
        }

        private List<ConfigError> validateRoute(RouteKind kind, @Nullable Boolean keepIdentityHeaders) {
            RouteConfig.RouteConfigBuilder route = RouteConfig.builder()
                    .id(ROUTE_ID)
                    .keepIdentityHeaders(keepIdentityHeaders);
            boolean assetRoute = kind == RouteKind.UPSTREAM_ASSET || kind == RouteKind.DIRECTORY_ASSET;
            switch (kind) {
                case HTTP_PROXY -> route.match(match("/api", HttpMethod.GET));
                case GRAPHQL_PROXY -> route.match(match("/graphql", HttpMethod.POST)).protocol(Protocol.GRAPHQL);
                case GRPC_PROXY -> route.match(match("/grpc", HttpMethod.POST)).protocol(Protocol.GRPC);
                case WEBSOCKET -> route.match(match("/ws", HttpMethod.GET)).protocol(Protocol.WEBSOCKET);
                case REDIRECT -> route.match(match("/old", HttpMethod.GET))
                        .redirect(RedirectConfig.builder().location("/new").status(302).build());
                case UPSTREAM_ASSET -> route.match(match("/assets", HttpMethod.GET)).anchor("assets")
                        .asset(upstreamAsset("SECONDARY"));
                case DIRECTORY_ASSET -> route.match(match("/assets", HttpMethod.GET)).anchor("assets")
                        .asset(directoryAsset("/srv/assets"));
            }
            GatewayConfig gateway = gatewayWithAnchors(Map.of("assets",
                    matrixAnchor("assets", "/assets", AnchorType.ASSET, AccessLevel.PUBLIC, null)));
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("web").enabled(true).baseUrl("WEB")
                    .anchor(assetRoute ? "assets" : null)
                    .auth(auth(Require.NONE))
                    .routes(List.of(route.build()))
                    .build();
            return validator.validate(gateway, List.of(endpoint), topologyWith("WEB", "SECONDARY"));
        }

        @ParameterizedTest(name = "keep_identity_headers: true on {0}")
        @EnumSource(value = RouteKind.class, names = {"HTTP_PROXY", "GRAPHQL_PROXY", "GRPC_PROXY", "UPSTREAM_ASSET"})
        @DisplayName("Should accept the key on every route that relays upstream response headers")
        void shouldAcceptOnRelayingRoute(RouteKind kind) {
            List<ConfigError> errors = validateRoute(kind, true);

            assertTrue(errors.isEmpty(), () -> kind + " relays upstream headers, so the key is accepted, got: "
                    + errors);
        }

        @ParameterizedTest(name = "keep_identity_headers: {1} on {0}")
        @CsvSource({"REDIRECT, true", "REDIRECT, false", "DIRECTORY_ASSET, true", "DIRECTORY_ASSET, false",
                "WEBSOCKET, true", "WEBSOCKET, false"})
        @DisplayName("Should refuse any declared value on a route that relays no upstream response header")
        void shouldRefuseOnNonRelayingRoute(RouteKind kind, boolean declared) {
            List<ConfigError> errors = validateRoute(kind, declared);

            String reason = switch (kind) {
                case REDIRECT -> "it is a redirect route";
                case DIRECTORY_ASSET -> "it is an asset route with source: directory";
                default -> "it is a protocol: websocket route";
            };
            assertAll("exactly one refusal naming the route and why the key would be ignored",
                    () -> assertHasError(errors, "/endpoint/routes", REFUSAL + reason),
                    () -> assertEquals(1, errors.stream()
                            .filter(error -> error.message().contains("keep_identity_headers")).count(),
                            () -> "expected one keep_identity_headers refusal, got: " + errors));
        }

        @ParameterizedTest(name = "no key on {0}")
        @EnumSource(RouteKind.class)
        @DisplayName("Should raise no error on any route kind when the key is undeclared")
        void shouldAcceptUndeclaredKeyOnEveryRouteKind(RouteKind kind) {
            List<ConfigError> errors = validateRoute(kind, null);

            assertTrue(errors.isEmpty(), () -> "the control: " + kind + " (relays upstream headers: "
                    + kind.relaysUpstreamHeaders + ") is valid without the key, got: " + errors);
        }
    }
}
