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
package de.cuioss.sheriff.gateway.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Value-object contract test suite for the immutable configuration model records
 * under {@code de.cuioss.sheriff.gateway.config.model}.
 * <p>
 * The records are the binding target for the YAML loader (D4); their behavioural
 * contract is entirely value-based. This suite verifies:
 * <ul>
 *   <li>the record {@code equals}/{@code hashCode}/{@code toString} contract for
 *       every model record (top-level and nested), including the D1/D2 anchor
 *       additions ({@link AnchorConfig}, and the anchor / effective-policy fields on
 *       {@link EndpointConfig}, {@link RouteConfig}, {@link ResolvedRoute}),</li>
 *   <li>the Lombok {@code @Builder} produces instances equal to the canonical
 *       constructor,</li>
 *   <li>a {@link org.jspecify.annotations.Nullable} component round-trips
 *       {@code null} and the canonical constructors normalize absent collections to
 *       empty collections,</li>
 *   <li>collection components are defensively copied into unmodifiable copies,</li>
 *   <li>mandatory fields are rejected with a {@link NullPointerException},</li>
 *   <li>the {@link HttpMethod} and {@link Protocol} enum contracts, including the
 *       deliberate absence of {@code TRACE}/{@code CONNECT}.</li>
 * </ul>
 */
class ConfigModelContractTest {

    // --- Shared fixtures ---------------------------------------------------

    private static AuthConfig auth() {
        return new AuthConfig("bearer", List.of("read"));
    }

    private static AnchorConfig anchorConfig() {
        return AnchorConfig.builder()
                .name("api")
                .pathPrefix("/api")
                .type(AnchorType.PROXY)
                .access(AccessLevel.AUTHENTICATED)
                .auth(auth())
                .securityFilter(securityFilterConfig())
                .securityHeaders(securityHeadersConfig())
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .build();
    }

    private static GatewayConfig gatewayConfig() {
        return GatewayConfig.builder()
                .version(1)
                .metadata(new Metadata("2024-01"))
                .tls(tlsConfig())
                .management(managementConfig())
                .securityHeaders(securityHeadersConfig())
                .securityDefaults(new SecurityDefaultsConfig("strict", 8192))
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .anchors(Map.of("api", anchorConfig()))
                .upstreamDefaults(UpstreamDefaultsConfig.defaults())
                .forwarded(new ForwardedConfig(List.of("10.0.0.0/8"), true, "both"))
                .tokenValidation(tokenValidationConfig())
                .oidc(oidcConfig())
                .build();
    }

    private static ManagementConfig managementConfig() {
        return ManagementConfig.builder()
                .tls(new ManagementConfig.ManagementTls(true))
                .build();
    }

    private static TlsConfig tlsConfig() {
        return TlsConfig.builder()
                .minVersion("TLSv1.3")
                .cipherSuites(List.of("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"))
                .alpn(List.of("h2", "http/1.1"))
                .passthroughSni(Map.of("internal.example.com", "internal-alias"))
                .mtls(new TlsConfig.Mtls(true, "/etc/ca.pem"))
                .build();
    }

    private static SecurityHeadersConfig securityHeadersConfig() {
        return SecurityHeadersConfig.builder()
                .hsts(new SecurityHeadersConfig.Hsts(31536000, true))
                .contentTypeNosniff(true)
                .frameDeny(true)
                .cors(new SecurityHeadersConfig.Cors(true, List.of("https://app.example.com"),
                        List.of("GET"), List.of("Authorization"), false))
                .build();
    }

    private static TokenValidationConfig tokenValidationConfig() {
        return new TokenValidationConfig(List.of(issuerConfig()));
    }

    private static IssuerConfig issuerConfig() {
        return IssuerConfig.builder()
                .name("primary")
                .issuer("https://issuer.example.com")
                .audience("api-sheriff")
                .jwks(new IssuerConfig.Jwks("http", "https://issuer.example.com/jwks", null, List.of(), null))
                .build();
    }

    private static OidcConfig oidcConfig() {
        return OidcConfig.builder()
                .issuer("https://issuer.example.com")
                .clientId("sheriff")
                .clientSecret("${OIDC_SECRET}")
                .scopes(List.of("openid", "profile"))
                .redirectUri("https://gw.example.com/callback")
                .logout(new OidcConfig.Logout("/logout", "/post-logout", "/home", "/backchannel"))
                .session(OidcConfig.Session.builder()
                        .mode("cookie")
                        .cookieName("sid")
                        .encryptionKey("${SESSION_KEY}")
                        .ttlSeconds(3600)
                        .csrf(new OidcConfig.Csrf(List.of("https://app.example.com")))
                        .refresh(new OidcConfig.Refresh(true, 60, "reauthenticate"))
                        .maxSessions(10000)
                        .build())
                .stepUp(new OidcConfig.StepUp(true, false))
                .userInfo(userInfo())
                .login(new OidcConfig.Login("/session/login"))
                .build();
    }

    private static OidcConfig.UserInfo userInfo() {
        return OidcConfig.UserInfo.builder()
                .path("/session/userinfo")
                .allowedClaims(List.of("sub", "name", "roles"))
                .defaultView(List.of("sub", "name"))
                .build();
    }

    private static EndpointConfig endpointConfig() {
        return EndpointConfig.builder()
                .id("orders")
                .enabled(true)
                .baseUrl("orders-service")
                .anchor("api")
                .auth(auth())
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .upstreamDefaults(UpstreamDefaultsConfig.defaults())
                .routes(List.of(routeConfig()))
                .build();
    }

    private static RouteConfig routeConfig() {
        return RouteConfig.builder()
                .id("orders-read")
                .protocol(Protocol.HTTP)
                .anchor("api")
                .match(matchConfig())
                .auth(auth())
                .securityFilter(securityFilterConfig())
                .forward(new ForwardConfig(List.of("Accept"), List.of("page"),
                        Map.of("X-Gateway", "api-sheriff")))
                .upstream(upstreamConfig())
                .rateLimit(new RateLimitConfig(100, 200))
                .websocket(webSocketConfig())
                .build();
    }

    private static WebSocketConfig webSocketConfig() {
        return new WebSocketConfig(List.of("https://app.example.com"), 300);
    }

    private static ResolvedRoute resolvedRoute() {
        return ResolvedRoute.builder()
                .id("orders-read")
                .protocol(Protocol.HTTP)
                .anchor("api")
                .match(matchConfig())
                .effectiveAuth(auth())
                .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .effectiveSecurityFilter(securityFilterConfig())
                .effectiveSecurityHeaders(securityHeadersConfig())
                .retryEnabled(true)
                .notModifiedEnabled(true)
                .upstream(resolvedUpstream())
                .effectiveForward(new ForwardConfig(List.of("Accept"), List.of("page"), Map.of("X-Gateway", "api-sheriff")))
                .build();
    }

    private static ResolvedUpstream resolvedUpstream() {
        return new ResolvedUpstream("https", "orders.internal", 443, "");
    }

    private static MatchConfig matchConfig() {
        return MatchConfig.builder()
                .pathPrefix("/orders")
                .methods(List.of(HttpMethod.GET))
                .host("api.example.com")
                .headers(List.of(new MatchConfig.HeaderMatcher("X-Tenant", true, "acme")))
                .build();
    }

    private static SecurityFilterConfig securityFilterConfig() {
        return SecurityFilterConfig.builder()
                .profile("strict")
                .allowedPaths(List.of("/orders"))
                .maxHeaderCount(50)
                .maxHeaderValueLength(4096)
                .maxQueryParams(32)
                .maxParamValueLength(1024)
                .maxBodyBytes(1048576)
                .allowedHeaderNames(List.of("Accept"))
                .blockedHeaderNames(List.of("X-Debug"))
                .allowedContentTypes(List.of("application/json"))
                .build();
    }

    private static UpstreamConfig upstreamConfig() {
        return UpstreamConfig.builder()
                .path("/v1/orders")
                .connectTimeoutMs(2000)
                .readTimeoutMs(5000)
                .retry(new UpstreamConfig.Retry(true, 3, true))
                .notModified(new UpstreamConfig.NotModified(true))
                .circuitBreaker(new UpstreamConfig.CircuitBreaker(5, 30000))
                .build();
    }

    // --- equals / hashCode / toString contract ----------------------------

    @Nested
    @DisplayName("equals / hashCode / toString contract")
    class ValueObjectContract {

        private static Arguments voCase(String name, Object subject, Object equalCopy, Object different) {
            return Arguments.of(Named.named(name, subject), equalCopy, different);
        }

        static Stream<Arguments> valueObjects() {
            return Stream.of(
                    voCase("GatewayConfig", gatewayConfig(), gatewayConfig(),
                            GatewayConfig.builder().version(99).build()),
                    voCase("Metadata", new Metadata("v1"), new Metadata("v1"),
                            new Metadata("v2")),
                    voCase("TlsConfig", tlsConfig(), tlsConfig(), TlsConfig.builder().build()),
                    voCase("ManagementConfig", managementConfig(), managementConfig(),
                            ManagementConfig.builder().build()),
                    voCase("ManagementConfig.ManagementTls", new ManagementConfig.ManagementTls(true),
                            new ManagementConfig.ManagementTls(true), new ManagementConfig.ManagementTls(false)),
                    voCase("TlsConfig.Mtls", new TlsConfig.Mtls(true, "/ca"),
                            new TlsConfig.Mtls(true, "/ca"), new TlsConfig.Mtls(false, null)),
                    voCase("SecurityHeadersConfig", securityHeadersConfig(), securityHeadersConfig(),
                            SecurityHeadersConfig.builder().build()),
                    voCase("SecurityHeadersConfig.Hsts",
                            new SecurityHeadersConfig.Hsts(1, true),
                            new SecurityHeadersConfig.Hsts(1, true),
                            new SecurityHeadersConfig.Hsts(2, false)),
                    voCase("SecurityHeadersConfig.Cors",
                            new SecurityHeadersConfig.Cors(true, List.of("a"), List.of("GET"),
                                    List.of("Accept"), false),
                            new SecurityHeadersConfig.Cors(true, List.of("a"), List.of("GET"),
                                    List.of("Accept"), false),
                            new SecurityHeadersConfig.Cors(false, List.of("b"), List.of("POST"),
                                    List.of("Authorization"), true)),
                    // Two cases so the record contract is proven to discriminate on BOTH components:
                    // an unequal instance differing only in 'profile' would leave the newer
                    // max_authorization_header_value_length component silently out of equals().
                    voCase("SecurityDefaultsConfig (profile)",
                            new SecurityDefaultsConfig("strict", 8192),
                            new SecurityDefaultsConfig("strict", 8192),
                            new SecurityDefaultsConfig("lenient", 8192)),
                    voCase("SecurityDefaultsConfig (max_authorization_header_value_length)",
                            new SecurityDefaultsConfig("strict", 8192),
                            new SecurityDefaultsConfig("strict", 8192),
                            new SecurityDefaultsConfig("strict", 4096)),
                    voCase("AnchorConfig", anchorConfig(), anchorConfig(),
                            AnchorConfig.builder().name("bff").pathPrefix("/bff").type(AnchorType.BFF)
                                    .access(AccessLevel.AUTHENTICATED).build()),
                    voCase("ForwardedConfig",
                            new ForwardedConfig(List.of("10.0.0.0/8"), true, "both"),
                            new ForwardedConfig(List.of("10.0.0.0/8"), true, "both"),
                            new ForwardedConfig(List.of("192.168.0.0/16"), false, "x-forwarded")),
                    voCase("TokenValidationConfig", tokenValidationConfig(), tokenValidationConfig(),
                            new TokenValidationConfig(List.of())),
                    voCase("IssuerConfig", issuerConfig(), issuerConfig(),
                            new IssuerConfig("other", "https://other", null, null)),
                    voCase("IssuerConfig.Jwks",
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null),
                            new IssuerConfig.Jwks("file", null, "/jwks.json", List.of(), null)),
                    // The egress allowlist participates in identity: two otherwise-identical
                    // jwks blocks that differ only in allowed_egress_hosts are NOT equal, so a
                    // widened block can never be mistaken for a secure-default one.
                    voCase("IssuerConfig.Jwks.allowedEgressHosts",
                            new IssuerConfig.Jwks("http", "https://j", null, List.of("idp.internal"), null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of("idp.internal"), null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null)),
                    voCase("OidcConfig", oidcConfig(), oidcConfig(), OidcConfig.builder().build()),
                    voCase("OidcConfig.Logout",
                            new OidcConfig.Logout("/l", null, null, null),
                            new OidcConfig.Logout("/l", null, null, null),
                            new OidcConfig.Logout("/other", null, null, null)),
                    voCase("OidcConfig.Csrf", new OidcConfig.Csrf(List.of("https://a")),
                            new OidcConfig.Csrf(List.of("https://a")), new OidcConfig.Csrf(List.of("https://b"))),
                    voCase("OidcConfig.Refresh",
                            new OidcConfig.Refresh(true, 60, "reject"),
                            new OidcConfig.Refresh(true, 60, "reject"),
                            new OidcConfig.Refresh(false, null, null)),
                    voCase("OidcConfig.StepUp", new OidcConfig.StepUp(true, false),
                            new OidcConfig.StepUp(true, false),
                            new OidcConfig.StepUp(false, true)),
                    voCase("OidcConfig.UserInfo", userInfo(), userInfo(),
                            new OidcConfig.UserInfo("/other", List.of("sub"), List.of())),
                    voCase("OidcConfig.Login", new OidcConfig.Login("/session/login"),
                            new OidcConfig.Login("/session/login"),
                            new OidcConfig.Login("/other-login")),
                    voCase("UpstreamDefaultsConfig", new UpstreamDefaultsConfig(true, true),
                            new UpstreamDefaultsConfig(true, true), new UpstreamDefaultsConfig(false, true)),
                    voCase("EndpointConfig", endpointConfig(), endpointConfig(), EndpointConfig.builder()
                            .id("other").baseUrl("svc").auth(auth()).build()),
                    voCase("AuthConfig", auth(), auth(), new AuthConfig("none", List.of())),
                    voCase("RouteConfig", routeConfig(), routeConfig(),
                            RouteConfig.builder().id("other").match(matchConfig()).build()),
                    voCase("ResolvedRoute", resolvedRoute(), resolvedRoute(),
                            ResolvedRoute.builder().id("other").match(matchConfig()).effectiveAuth(auth())
                                    .upstream(resolvedUpstream()).build()),
                    voCase("MatchConfig", matchConfig(), matchConfig(),
                            MatchConfig.builder().pathPrefix("/other").build()),
                    voCase("MatchConfig.HeaderMatcher",
                            new MatchConfig.HeaderMatcher("X-Key", true, "v"),
                            new MatchConfig.HeaderMatcher("X-Key", true, "v"),
                            new MatchConfig.HeaderMatcher("X-Other", null, null)),
                    voCase("SecurityFilterConfig", securityFilterConfig(), securityFilterConfig(),
                            SecurityFilterConfig.builder().build()),
                    voCase("ForwardConfig",
                            new ForwardConfig(List.of("Accept"), List.of("page"), Map.of("X-Gateway", "sheriff")),
                            new ForwardConfig(List.of("Accept"), List.of("page"), Map.of("X-Gateway", "sheriff")),
                            new ForwardConfig(List.of(), List.of(), Map.of())),
                    voCase("WebSocketConfig",
                            new WebSocketConfig(List.of("https://app.example.com"), 300),
                            new WebSocketConfig(List.of("https://app.example.com"), 300),
                            new WebSocketConfig(List.of("https://other.example.com"), 60)),
                    voCase("UpstreamConfig", upstreamConfig(), upstreamConfig(), UpstreamConfig.builder().build()),
                    voCase("UpstreamConfig.Retry",
                            new UpstreamConfig.Retry(true, 3, true),
                            new UpstreamConfig.Retry(true, 3, true),
                            new UpstreamConfig.Retry(false, null, null)),
                    voCase("UpstreamConfig.NotModified", new UpstreamConfig.NotModified(true),
                            new UpstreamConfig.NotModified(true),
                            new UpstreamConfig.NotModified(false)),
                    voCase("UpstreamConfig.CircuitBreaker",
                            new UpstreamConfig.CircuitBreaker(5, 30000),
                            new UpstreamConfig.CircuitBreaker(5, 30000),
                            new UpstreamConfig.CircuitBreaker(1, 1)),
                    voCase("RateLimitConfig", new RateLimitConfig(100, 200),
                            new RateLimitConfig(100, 200),
                            new RateLimitConfig(1, 1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("valueObjects")
        void shouldHonorValueObjectContract(Object subject, Object equalCopy, Object different) {
            assertEquals(subject, equalCopy, "instances built from equal components must be equal");
            assertEquals(subject.hashCode(), equalCopy.hashCode(), "equal instances must share a hashCode");
            assertNotEquals(subject, different, "instances with differing components must not be equal");
            assertNotEquals(null, subject, "a value object is never equal to null");
            assertNotEquals("not-a-config", subject, "a value object is never equal to a foreign type");
            assertNotNull(subject.toString(), "toString must be present");
        }
    }

    // --- Builder equivalence ----------------------------------------------

    @Nested
    @DisplayName("Lombok @Builder equivalence with the canonical constructor")
    class BuilderEquivalence {

        @Test
        void authConfigBuilderMatchesConstructor() {
            AuthConfig viaCtor = new AuthConfig("bearer", List.of("read"));
            AuthConfig viaBuilder = AuthConfig.builder().require("bearer").requiredScopes(List.of("read")).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void anchorConfigBuilderMatchesConstructor() {
            AnchorConfig viaCtor = new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED,
                    auth(), null, null, List.of(HttpMethod.GET));
            AnchorConfig viaBuilder = AnchorConfig.builder().name("api").pathPrefix("/api").type(AnchorType.PROXY)
                    .access(AccessLevel.AUTHENTICATED).auth(auth())
                    .allowedMethods(List.of(HttpMethod.GET)).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void gatewayConfigBuilderMatchesConstructor() {
            GatewayConfig viaCtor = new GatewayConfig(2, null, null, null, null, null,
                    List.of(HttpMethod.GET), Map.of("api", anchorConfig()), null, null, null, null, null, null);
            GatewayConfig viaBuilder = GatewayConfig.builder().version(2).allowedMethods(List.of(HttpMethod.GET))
                    .anchors(Map.of("api", anchorConfig())).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void endpointConfigBuilderMatchesConstructor() {
            EndpointConfig viaCtor = new EndpointConfig("orders", true, "orders-service", "api",
                    auth(), List.of(HttpMethod.GET), null, List.of());
            EndpointConfig viaBuilder = EndpointConfig.builder().id("orders").enabled(true).baseUrl("orders-service")
                    .anchor("api").auth(auth()).allowedMethods(List.of(HttpMethod.GET))
                    .build();
            assertEquals(viaCtor, viaBuilder);
        }
    }

    // --- Null-normalization ------------------------------------------------

    @Nested
    @DisplayName("Canonical constructors normalize absent components")
    class NullNormalization {

        @Test
        void gatewayConfigNormalizesAllAbsentComponents() {
            GatewayConfig cfg = new GatewayConfig(1, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
            assertNull(cfg.metadata());
            assertNull(cfg.tls());
            assertNull(cfg.management());
            assertNull(cfg.securityHeaders());
            assertNull(cfg.securityDefaults());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertTrue(cfg.anchors().isEmpty());
            assertNull(cfg.upstreamDefaults());
            assertNull(cfg.assetDefaults());
            assertNull(cfg.forwarded());
            assertNull(cfg.tokenValidation());
            assertNull(cfg.oidc());
            assertNull(cfg.edgeHardening());
        }

        @Test
        void edgeHardeningConfigNormalizesAbsentCaps() {
            EdgeHardeningConfig cfg = new EdgeHardeningConfig(null, null);
            assertNull(cfg.admissionCap());
            assertNull(cfg.websocketRelayCap());
            assertEquals(EdgeHardeningConfig.DEFAULT_ADMISSION_CAP, cfg.effectiveAdmissionCap());
            assertEquals(EdgeHardeningConfig.DEFAULT_WEBSOCKET_RELAY_CAP, cfg.effectiveWebsocketRelayCap());
        }

        @Test
        void edgeHardeningConfigDerivesAbsentRelayCapFromDeclaredAdmissionCap() {
            EdgeHardeningConfig cfg = new EdgeHardeningConfig(64, null);
            assertEquals(64, cfg.effectiveAdmissionCap());
            assertEquals(16, cfg.effectiveWebsocketRelayCap());
        }

        @Test
        void edgeHardeningConfigFloorsDerivedRelayCapAtOne() {
            EdgeHardeningConfig cfg = new EdgeHardeningConfig(1, null);
            assertEquals(1, cfg.effectiveWebsocketRelayCap());
        }

        @Test
        void edgeHardeningConfigKeepsExplicitRelayCapAboveAdmissionCap() {
            EdgeHardeningConfig cfg = new EdgeHardeningConfig(4, 16);
            assertEquals(16, cfg.effectiveWebsocketRelayCap());
        }

        @Test
        void edgeHardeningConfigDefaultsCarryBothCaps() {
            EdgeHardeningConfig defaults = EdgeHardeningConfig.defaults();
            assertEquals(EdgeHardeningConfig.DEFAULT_ADMISSION_CAP, defaults.admissionCap());
            assertEquals(EdgeHardeningConfig.DEFAULT_WEBSOCKET_RELAY_CAP, defaults.websocketRelayCap());
        }

        @Test
        void endpointConfigNormalizesAbsentCollectionsAndNullables() {
            EndpointConfig cfg = new EndpointConfig("id", true, "url", null, null, null, null, null);
            assertNull(cfg.anchor());
            assertNull(cfg.auth());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertNull(cfg.upstreamDefaults());
            assertTrue(cfg.routes().isEmpty());
        }

        @Test
        void routeConfigNormalizesAbsentAnchor() {
            RouteConfig cfg = new RouteConfig("id", null, null, matchConfig(), null, null, null, null, null, null, null);
            assertNull(cfg.anchor());
            assertNull(cfg.auth());
            assertNull(cfg.securityFilter());
            assertNull(cfg.websocket());
        }

        @Test
        void anchorConfigNormalizesAbsentComponents() {
            AnchorConfig cfg = new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null, null, null, null);
            assertNull(cfg.auth());
            assertNull(cfg.securityFilter());
            assertNull(cfg.securityHeaders());
            assertTrue(cfg.allowedMethods().isEmpty());
        }

        @Test
        void resolvedRouteNormalizesAbsentComponents() {
            ResolvedRoute cfg = new ResolvedRoute("id", null, null, matchConfig(), auth(), null, null, null, true,
                    true, resolvedUpstream(), null, null, null, null);
            assertNull(cfg.anchor());
            assertNull(cfg.effectiveSecurityFilter());
            assertNull(cfg.effectiveSecurityHeaders());
            assertTrue(cfg.effectiveAllowedMethods().isEmpty());
            assertEquals(Protocol.HTTP, cfg.protocol());
            assertTrue(cfg.effectiveForward().headersAllow().isEmpty(),
                    "an absent forward block normalizes to a deny-by-default empty allowlist");
            assertTrue(cfg.effectiveForward().queryAllow().isEmpty());
            assertTrue(cfg.effectiveForward().setHeaders().isEmpty());
            assertTrue(cfg.effectiveAllowedOrigins().isEmpty(),
                    "an absent WebSocket origin allowlist normalizes to an empty set");
            assertNull(cfg.effectiveWebSocketIdleTimeoutSeconds(),
                    "an absent WebSocket idle timeout stays null");
        }

        @Test
        void collectionBearingRecordsNormalizeNullToEmpty() {
            assertTrue(new AuthConfig("none", null).requiredScopes().isEmpty());
            assertTrue(new TokenValidationConfig(null).issuers().isEmpty());
            assertTrue(new ForwardedConfig(null, null, null).trustedProxies().isEmpty());
            assertTrue(new ForwardConfig(null, null, null).setHeaders().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).cipherSuites().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).alpn().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).passthroughSni().isEmpty());
            assertTrue(new MatchConfig("/p", null, null, null).methods().isEmpty());
            assertTrue(new SecurityFilterConfig(null, null, null, null, null, null, null, null, null, null)
                    .allowedPaths().isEmpty());
            assertTrue(new OidcConfig.Csrf(null).trustedOrigins().isEmpty());
            assertTrue(new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null, null, null, null).allowedMethods().isEmpty());
            assertTrue(new WebSocketConfig(null, null).allowedOrigins().isEmpty());
        }

        @Test
        void webSocketConfigNormalizesAbsentComponents() {
            WebSocketConfig cfg = new WebSocketConfig(null, null);
            assertTrue(cfg.allowedOrigins().isEmpty());
            assertNull(cfg.idleTimeoutSeconds());
        }
    }

    // --- Defensive copy ----------------------------------------------------

    @Nested
    @DisplayName("Collection components are defensively copied and unmodifiable")
    class DefensiveCopy {

        @Test
        void listComponentIsDecoupledFromTheSource() {
            List<HttpMethod> source = new ArrayList<>(List.of(HttpMethod.GET));
            GatewayConfig cfg = GatewayConfig.builder().version(1).allowedMethods(source).build();
            source.add(HttpMethod.POST);
            assertEquals(List.of(HttpMethod.GET), cfg.allowedMethods(),
                    "mutating the source list after construction must not affect the record");
        }

        @Test
        void listComponentIsUnmodifiable() {
            GatewayConfig cfg = GatewayConfig.builder().version(1).allowedMethods(List.of(HttpMethod.GET)).build();
            List<HttpMethod> methods = cfg.allowedMethods();
            assertThrows(UnsupportedOperationException.class, () -> methods.add(HttpMethod.PUT));
        }

        @Test
        void anchorsMapIsDecoupledAndUnmodifiable() {
            Map<String, AnchorConfig> source = new HashMap<>();
            source.put("api", anchorConfig());
            GatewayConfig cfg = GatewayConfig.builder().version(1).anchors(source).build();
            source.put("bff", anchorConfig());
            assertEquals(Map.of("api", anchorConfig()), cfg.anchors(),
                    "mutating the source map after construction must not affect the record");
            Map<String, AnchorConfig> anchors = cfg.anchors();
            AnchorConfig extra = anchorConfig();
            assertThrows(UnsupportedOperationException.class, () -> anchors.put("extra", extra));
        }

        @Test
        void mapComponentIsDecoupledAndUnmodifiable() {
            Map<String, String> source = new HashMap<>();
            source.put("X-Gateway", "sheriff");
            ForwardConfig cfg = ForwardConfig.builder().setHeaders(source).build();
            source.put("X-Extra", "leak");
            assertEquals(Map.of("X-Gateway", "sheriff"), cfg.setHeaders());
            Map<String, String> headers = cfg.setHeaders();
            assertThrows(UnsupportedOperationException.class, () -> headers.put("X-New", "v"));
        }

        @Test
        void webSocketAllowedOriginsIsDecoupledAndUnmodifiable() {
            List<String> source = new ArrayList<>(List.of("https://app.example.com"));
            WebSocketConfig cfg = WebSocketConfig.builder().allowedOrigins(source).build();
            source.add("https://leak.example.com");
            List<String> origins = cfg.allowedOrigins();
            assertEquals(List.of("https://app.example.com"), origins,
                    "mutating the source list after construction must not affect the record");
            assertThrows(UnsupportedOperationException.class, () -> origins.add("https://new.example.com"));
        }
    }

    // --- Mandatory fields --------------------------------------------------

    @Nested
    @DisplayName("Mandatory fields are rejected with NullPointerException")
    class MandatoryFields {

        @Test
        void endpointConfigRequiresIdAndBaseUrl() {
            assertThrows(NullPointerException.class, () -> endpointConfigWith(null, "url"));
            assertThrows(NullPointerException.class, () -> endpointConfigWith("id", null));
        }

        /**
         * Builds an otherwise-valid {@link EndpointConfig} so a mandatory-field test can vary
         * exactly one component, keeping each {@code assertThrows} lambda to a single invocation.
         */
        private EndpointConfig endpointConfigWith(String id, String baseUrl) {
            return new EndpointConfig(id, true, baseUrl, null, auth(), List.of(), null, List.of());
        }

        @Test
        void endpointConfigAcceptsAnAbsentAuthBlock() {
            EndpointConfig cfg = new EndpointConfig("id", true, "url", "api", null, List.of(), null, List.of());
            assertNull(cfg.auth(), "an anchored endpoint may omit its auth block");
        }

        @Test
        void anchorConfigRequiresNamePathPrefixTypeAndAccess() {
            NullPointerException noName = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig(null, "/api", AnchorType.PROXY, AccessLevel.PUBLIC, null,
                            null, null, List.of()));
            assertEquals("name", noName.getMessage());
            NullPointerException noPrefix = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", null, AnchorType.PROXY, AccessLevel.PUBLIC, null,
                            null, null, List.of()));
            assertEquals("pathPrefix", noPrefix.getMessage());
            NullPointerException noType = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", "/api", null, AccessLevel.PUBLIC, null,
                            null, null, List.of()));
            assertEquals("type", noType.getMessage());
            NullPointerException noAccess = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", "/api", AnchorType.PROXY, null, null,
                            null, null, List.of()));
            assertEquals("access", noAccess.getMessage());
        }

        @Test
        void authConfigRequiresRequire() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new AuthConfig(null, List.of()));
            assertEquals("require", ex.getMessage());
        }

        @Test
        void issuerConfigRequiresNameAndIssuer() {
            assertThrows(NullPointerException.class,
                    () -> new IssuerConfig(null, "iss", null, null));
            assertThrows(NullPointerException.class,
                    () -> new IssuerConfig("name", null, null, null));
        }

        @Test
        void jwksRequiresSource() {
            assertThrows(NullPointerException.class,
                    () -> new IssuerConfig.Jwks(null, null, null, List.of(), null));
        }

        @Test
        void matchConfigRequiresPathPrefix() {
            assertThrows(NullPointerException.class, () -> new MatchConfig(null, List.of(), null, List.of()));
        }

        @Test
        void headerMatcherRequiresName() {
            assertThrows(NullPointerException.class,
                    () -> new MatchConfig.HeaderMatcher(null, null, null));
        }

        @Test
        void routeConfigRequiresIdAndMatch() {
            MatchConfig match = matchConfig();
            assertThrows(NullPointerException.class, () -> routeConfigWith(null, match));
            assertThrows(NullPointerException.class, () -> routeConfigWith("id", null));
        }

        /**
         * Builds an otherwise-valid {@link RouteConfig} so a mandatory-field test can vary
         * exactly one component, keeping each {@code assertThrows} lambda to a single invocation.
         */
        private RouteConfig routeConfigWith(String id, MatchConfig match) {
            return new RouteConfig(id, null, null, match, null, null, null, null, null, null, null);
        }

        @Test
        void resolvedRouteRequiresIdMatchAuthAndExactlyOneTerminalAction() {
            MatchConfig match = matchConfig();
            AuthConfig auth = auth();
            ResolvedUpstream upstream = resolvedUpstream();
            ResolvedUpstream noUpstream = null;
            ResolvedAsset noAsset = null;
            ResolvedAsset asset = ResolvedAsset.directory("/srv", AccessLevel.PUBLIC);

            assertThrows(NullPointerException.class, () -> resolvedRouteWith(null, match, auth, upstream, noAsset));
            assertThrows(NullPointerException.class, () -> resolvedRouteWith("id", null, auth, upstream, noAsset));
            assertThrows(NullPointerException.class, () -> resolvedRouteWith("id", match, null, upstream, noAsset));
            assertThrows(IllegalArgumentException.class,
                    () -> resolvedRouteWith("id", match, auth, noUpstream, noAsset),
                    "a route with neither an upstream nor an asset terminal action is rejected (XOR)");
            assertThrows(IllegalArgumentException.class,
                    () -> resolvedRouteWith("id", match, auth, upstream, asset),
                    "a route with both an upstream and an asset terminal action is rejected (XOR)");
        }

        /**
         * Builds an otherwise-valid {@link ResolvedRoute} so a mandatory-field or XOR test can vary
         * exactly one component, keeping each {@code assertThrows} lambda to a single invocation.
         */
        private ResolvedRoute resolvedRouteWith(String id, MatchConfig match, AuthConfig auth,
                ResolvedUpstream upstream, ResolvedAsset asset) {
            return new ResolvedRoute(id, Protocol.HTTP, null, match, auth, List.of(), null,
                    null, true, true, upstream, asset, null, null, null);
        }
    }

    // --- New fields introduced for this deliverable ------------------------

    @Nested
    @DisplayName("Anchor, auth-optionality, and effective-policy fields (D1/D2)")
    class NewFields {

        @Test
        void endpointConfigExposesAnchorAndOptionalAuth() {
            EndpointConfig cfg = EndpointConfig.builder()
                    .id("orders")
                    .enabled(true)
                    .baseUrl("orders-service")
                    .anchor("api")
                    .auth(auth())
                    .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                    .build();
            assertEquals("orders", cfg.id());
            assertTrue(cfg.enabled());
            assertEquals("api", cfg.anchor());
            assertEquals(auth(), cfg.auth());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.allowedMethods());
        }

        @Test
        void endpointConfigEnabledDefaultsToFalseWhenUnsetOnTheRecord() {
            EndpointConfig cfg = EndpointConfig.builder().id("orders").baseUrl("orders-service")
                    .auth(auth()).build();
            assertFalse(cfg.enabled(), "the record itself applies no default; the YAML loader owns the true default");
        }

        @Test
        void routeConfigExposesAnchorOverride() {
            RouteConfig cfg = RouteConfig.builder().id("r").anchor("bff").match(matchConfig()).build();
            assertEquals("bff", cfg.anchor());
        }

        @Test
        void routeConfigExposesWebSocketBlock() {
            RouteConfig cfg = RouteConfig.builder().id("ws").protocol(Protocol.WEBSOCKET)
                    .match(matchConfig()).websocket(webSocketConfig()).build();
            assertEquals(webSocketConfig(), cfg.websocket());
            assertEquals(List.of("https://app.example.com"),
                    cfg.websocket().allowedOrigins());
            assertEquals(300, cfg.websocket().idleTimeoutSeconds());
        }

        @Test
        void routeConfigWebSocketDefaultsToAbsent() {
            RouteConfig cfg = RouteConfig.builder().id("http").match(matchConfig()).build();
            assertNull(cfg.websocket(), "a non-WebSocket route carries no websocket block");
        }

        @Test
        void resolvedRouteCarriesMaterializedWebSocketSettings() {
            ResolvedRoute cfg = ResolvedRoute.builder()
                    .id("ws")
                    .protocol(Protocol.WEBSOCKET)
                    .match(matchConfig())
                    .effectiveAuth(auth())
                    .upstream(resolvedUpstream())
                    .effectiveAllowedOrigins(Set.of("https://app.example.com"))
                    .effectiveWebSocketIdleTimeoutSeconds(300)
                    .build();
            assertEquals(Set.of("https://app.example.com"), cfg.effectiveAllowedOrigins());
            assertEquals(300, cfg.effectiveWebSocketIdleTimeoutSeconds());
        }

        @Test
        void anchorConfigExposesEveryPolicyBlock() {
            AnchorConfig cfg = anchorConfig();
            assertEquals("api", cfg.name());
            assertEquals("/api", cfg.pathPrefix());
            assertEquals(AnchorType.PROXY, cfg.type());
            assertEquals(AccessLevel.AUTHENTICATED, cfg.access());
            assertEquals(auth(), cfg.auth());
            assertNotNull(cfg.securityFilter());
            assertNotNull(cfg.securityHeaders());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.allowedMethods());
        }

        @Test
        void resolvedRouteCarriesTheMaterializedEffectivePosture() {
            ResolvedRoute cfg = resolvedRoute();
            assertEquals("api", cfg.anchor());
            assertEquals("bearer", cfg.effectiveAuth().require());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.effectiveAllowedMethods());
            assertNotNull(cfg.effectiveSecurityFilter());
            assertNotNull(cfg.effectiveSecurityHeaders());
            assertEquals(List.of("Accept"), cfg.effectiveForward().headersAllow(),
                    "the materialized forward allowlist is carried on the resolved route");
        }

        @Test
        void gatewayConfigExposesAnchors() {
            GatewayConfig cfg = GatewayConfig.builder().version(1).anchors(Map.of("api", anchorConfig())).build();
            assertEquals(Map.of("api", anchorConfig()), cfg.anchors());
        }
    }

    // --- Enum contracts ----------------------------------------------------

    @Nested
    @DisplayName("HttpMethod enum contract")
    class HttpMethodEnum {

        @Test
        void exposesExactlyTheProxyableVerbsInOrder() {
            assertEquals(List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                    HttpMethod.DELETE, HttpMethod.OPTIONS), List.of(HttpMethod.values()));
        }

        @Test
        void doesNotRepresentTraceOrConnect() {
            assertThrows(IllegalArgumentException.class, () -> HttpMethod.valueOf("TRACE"));
            assertThrows(IllegalArgumentException.class, () -> HttpMethod.valueOf("CONNECT"));
        }
    }

    @Nested
    @DisplayName("Protocol enum contract")
    class ProtocolEnum {

        @Test
        void exposesTheSupportedProtocols() {
            assertEquals(List.of(Protocol.HTTP, Protocol.GRPC, Protocol.GRAPHQL, Protocol.WEBSOCKET),
                    List.of(Protocol.values()));
        }
    }

    // --- Static factory ----------------------------------------------------

    @Nested
    @DisplayName("UpstreamDefaultsConfig static factory")
    class UpstreamDefaultsFactory {

        @Test
        void defaultsEnableRetryAndNotModified() {
            UpstreamDefaultsConfig defaults = UpstreamDefaultsConfig.defaults();
            assertTrue(defaults.retryEnabled());
            assertTrue(defaults.notModifiedEnabled());
            assertEquals(new UpstreamDefaultsConfig(true, true), defaults);
        }
    }

    // --- BFF OIDC/session fold records (D1) --------------------------------

    @Nested
    @DisplayName("Session mode canonicalization — one spelling, one predicate")
    class SessionModeCanonicalization {

        private static OidcConfig.Session sessionWithMode(String mode) {
            return OidcConfig.Session.builder().mode(mode).build();
        }

        @ParameterizedTest(name = "mode ''{0}'' canonicalizes to cookie mode")
        @ValueSource(strings = {"cookie", "Cookie", "COOKIE", "  CoOkIe  "})
        @DisplayName("Should read every spelling of the cookie mode as cookie mode")
        void shouldCanonicalizeCookieMode(String declared) {
            OidcConfig.Session session = sessionWithMode(declared);

            assertEquals(OidcConfig.Session.MODE_COOKIE, session.mode(),
                    "the mode is canonicalized once, at construction");
            assertTrue(session.isCookieMode());
            assertFalse(session.isServerMode());
            assertTrue(session.isRecognisedMode());
        }

        @ParameterizedTest(name = "mode ''{0}'' canonicalizes to server mode")
        @ValueSource(strings = {"server", "Server", "SERVER", " sErVeR "})
        @DisplayName("Should read every spelling of the server mode as server mode")
        void shouldCanonicalizeServerMode(String declared) {
            OidcConfig.Session session = sessionWithMode(declared);

            assertEquals(OidcConfig.Session.MODE_SERVER, session.mode());
            assertTrue(session.isServerMode());
            assertFalse(session.isCookieMode());
            assertTrue(session.isRecognisedMode());
        }

        @Test
        @DisplayName("Should treat an absent, blank or unrecognised mode as no recognised mode")
        void shouldRejectUnrecognisedModes() {
            assertNull(sessionWithMode("   ").mode(), "a blank mode is an absent mode");
            assertFalse(sessionWithMode("   ").isRecognisedMode());
            assertFalse(sessionWithMode("stateless").isRecognisedMode());
            assertFalse(OidcConfig.Session.builder().build().isRecognisedMode());
        }
    }

    @Nested
    @DisplayName("BFF OIDC/session fold records (D1)")
    class BffFoldRecords {

        @Test
        void userInfoExposesPathAllowlistAndDefaultView() {
            OidcConfig.UserInfo cfg = userInfo();
            assertEquals("/session/userinfo", cfg.path());
            assertEquals(List.of("sub", "name", "roles"), cfg.allowedClaims());
            assertEquals(List.of("sub", "name"), cfg.defaultView());
        }

        @Test
        void userInfoBuilderMatchesConstructor() {
            OidcConfig.UserInfo viaCtor = new OidcConfig.UserInfo("/u", List.of("sub"), List.of("sub"));
            OidcConfig.UserInfo viaBuilder = OidcConfig.UserInfo.builder()
                    .path("/u").allowedClaims(List.of("sub")).defaultView(List.of("sub")).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void userInfoNormalizesAbsentComponents() {
            OidcConfig.UserInfo cfg = new OidcConfig.UserInfo(null, null, null);
            assertNull(cfg.path());
            assertTrue(cfg.allowedClaims().isEmpty(), "an absent allowlist is the secure closed default (empty)");
            assertTrue(cfg.defaultView().isEmpty());
        }

        @Test
        void userInfoClaimListsAreDefensivelyCopiedAndUnmodifiable() {
            List<String> allowedSource = new ArrayList<>(List.of("sub"));
            List<String> viewSource = new ArrayList<>(List.of("sub"));
            OidcConfig.UserInfo cfg = OidcConfig.UserInfo.builder()
                    .path("/u").allowedClaims(allowedSource).defaultView(viewSource).build();
            allowedSource.add("email");
            viewSource.add("email");
            assertEquals(List.of("sub"), cfg.allowedClaims(),
                    "mutating the source list after construction must not affect the record");
            assertEquals(List.of("sub"), cfg.defaultView());
            List<String> allowed = cfg.allowedClaims();
            List<String> view = cfg.defaultView();
            assertThrows(UnsupportedOperationException.class, () -> allowed.add("roles"));
            assertThrows(UnsupportedOperationException.class, () -> view.add("roles"));
        }

        @Test
        void loginExposesPathAndNormalizesAbsent() {
            assertEquals("/session/login", new OidcConfig.Login("/session/login").path());
            assertNull(new OidcConfig.Login(null).path());
        }

        @Test
        void loginBuilderMatchesConstructor() {
            OidcConfig.Login viaCtor = new OidcConfig.Login("/login");
            OidcConfig.Login viaBuilder = OidcConfig.Login.builder().path("/login").build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void sessionExposesAndNormalizesMaxSessions() {
            OidcConfig.Session withBound = OidcConfig.Session.builder().maxSessions(500).build();
            assertEquals(500, withBound.maxSessions());
            OidcConfig.Session withoutBound = OidcConfig.Session.builder().build();
            assertNull(withoutBound.maxSessions(), "an absent max_sessions stays null");
        }

        @Test
        void oidcToStringCarriesTheFoldFieldsAndStillRedactsTheSecret() {
            String rendered = oidcConfig().toString();
            assertTrue(rendered.contains("userInfo="), "toString must surface the userInfo block");
            assertTrue(rendered.contains("login="), "toString must surface the login block");
            assertTrue(rendered.contains("***REDACTED***"), "the client secret must stay redacted");
            assertFalse(rendered.contains("${OIDC_SECRET}"), "the raw client-secret reference must never appear");
        }

        @Test
        void sessionToStringCarriesMaxSessionsAndStillRedactsKeys() {
            String rendered = oidcConfig().session().toString();
            assertTrue(rendered.contains("maxSessions="), "Session toString must surface the max_sessions bound");
            assertTrue(rendered.contains("***REDACTED***"), "the encryption key must stay redacted");
            assertFalse(rendered.contains("${SESSION_KEY}"), "the raw encryption-key reference must never appear");
        }
    }
}
