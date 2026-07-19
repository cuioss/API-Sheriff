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
package de.cuioss.sheriff.api.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Value-object contract test suite for the immutable configuration model records
 * under {@code de.cuioss.sheriff.api.config.model}.
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
 *   <li>the canonical constructors normalize absent optionals to
 *       {@link Optional#empty()} and absent collections to empty collections,</li>
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
                .auth(Optional.of(auth()))
                .securityFilter(Optional.of(securityFilterConfig()))
                .securityHeaders(Optional.of(securityHeadersConfig()))
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .build();
    }

    private static GatewayConfig gatewayConfig() {
        return GatewayConfig.builder()
                .version(1)
                .metadata(Optional.of(new Metadata(Optional.of("2024-01"))))
                .tls(Optional.of(tlsConfig()))
                .securityHeaders(Optional.of(securityHeadersConfig()))
                .securityDefaults(Optional.of(new SecurityDefaultsConfig(Optional.of("strict"))))
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .anchors(Map.of("api", anchorConfig()))
                .upstreamDefaults(Optional.of(UpstreamDefaultsConfig.defaults()))
                .forwarded(Optional.of(new ForwardedConfig(List.of("10.0.0.0/8"), Optional.of(true), Optional.of("both"))))
                .tokenValidation(Optional.of(tokenValidationConfig()))
                .oidc(Optional.of(oidcConfig()))
                .build();
    }

    private static TlsConfig tlsConfig() {
        return TlsConfig.builder()
                .minVersion(Optional.of("TLSv1.3"))
                .alpn(List.of("h2", "http/1.1"))
                .passthroughSni(Map.of("internal.example.com", "internal-alias"))
                .mtls(Optional.of(new TlsConfig.Mtls(true, Optional.of("/etc/ca.pem"))))
                .build();
    }

    private static SecurityHeadersConfig securityHeadersConfig() {
        return SecurityHeadersConfig.builder()
                .hsts(Optional.of(new SecurityHeadersConfig.Hsts(Optional.of(31536000), Optional.of(true))))
                .contentTypeNosniff(Optional.of(true))
                .frameDeny(Optional.of(true))
                .cors(Optional.of(new SecurityHeadersConfig.Cors(Optional.of(true), List.of("https://app.example.com"),
                        List.of("GET"), List.of("Authorization"), Optional.of(false))))
                .build();
    }

    private static TokenValidationConfig tokenValidationConfig() {
        return new TokenValidationConfig(List.of(issuerConfig()));
    }

    private static IssuerConfig issuerConfig() {
        return IssuerConfig.builder()
                .name("primary")
                .issuer("https://issuer.example.com")
                .audience(Optional.of("api-sheriff"))
                .jwks(Optional.of(new IssuerConfig.Jwks("http", Optional.of("https://issuer.example.com/jwks"),
                        Optional.empty(), List.of())))
                .build();
    }

    private static OidcConfig oidcConfig() {
        return OidcConfig.builder()
                .issuer(Optional.of("https://issuer.example.com"))
                .clientId(Optional.of("sheriff"))
                .clientSecret(Optional.of("${OIDC_SECRET}"))
                .scopes(List.of("openid", "profile"))
                .redirectUri(Optional.of("https://gw.example.com/callback"))
                .logout(Optional.of(new OidcConfig.Logout(Optional.of("/logout"), Optional.of("/post-logout"),
                        Optional.of("/home"), Optional.of("/backchannel"))))
                .session(Optional.of(OidcConfig.Session.builder()
                        .mode(Optional.of("cookie"))
                        .cookieName(Optional.of("sid"))
                        .encryptionKey(Optional.of("${SESSION_KEY}"))
                        .ttlSeconds(Optional.of(3600))
                        .csrf(Optional.of(new OidcConfig.Csrf(List.of("https://app.example.com"))))
                        .refresh(Optional.of(new OidcConfig.Refresh(Optional.of(true), Optional.of(60),
                                Optional.of("reauthenticate"))))
                        .build()))
                .stepUp(Optional.of(new OidcConfig.StepUp(Optional.of(true), Optional.of(false))))
                .build();
    }

    private static EndpointConfig endpointConfig() {
        return EndpointConfig.builder()
                .id("orders")
                .enabled(true)
                .baseUrl("orders-service")
                .anchor(Optional.of("api"))
                .auth(Optional.of(auth()))
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .upstreamDefaults(Optional.of(UpstreamDefaultsConfig.defaults()))
                .routes(List.of(routeConfig()))
                .build();
    }

    private static RouteConfig routeConfig() {
        return RouteConfig.builder()
                .id("orders-read")
                .protocol(Optional.of(Protocol.HTTP))
                .anchor(Optional.of("api"))
                .match(matchConfig())
                .auth(Optional.of(auth()))
                .securityFilter(Optional.of(securityFilterConfig()))
                .forward(Optional.of(new ForwardConfig(List.of("Accept"), List.of("page"),
                        Map.of("X-Gateway", "api-sheriff"))))
                .upstream(Optional.of(upstreamConfig()))
                .rateLimit(Optional.of(new RateLimitConfig(Optional.of(100), Optional.of(200))))
                .build();
    }

    private static ResolvedRoute resolvedRoute() {
        return ResolvedRoute.builder()
                .id("orders-read")
                .protocol(Protocol.HTTP)
                .anchor(Optional.of("api"))
                .match(matchConfig())
                .effectiveAuth(auth())
                .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .effectiveSecurityFilter(Optional.of(securityFilterConfig()))
                .effectiveSecurityHeaders(Optional.of(securityHeadersConfig()))
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
                .host(Optional.of("api.example.com"))
                .headers(List.of(new MatchConfig.HeaderMatcher("X-Tenant", Optional.of(true), Optional.of("acme"))))
                .build();
    }

    private static SecurityFilterConfig securityFilterConfig() {
        return SecurityFilterConfig.builder()
                .profile(Optional.of("strict"))
                .allowedPaths(List.of("/orders"))
                .maxHeaderCount(Optional.of(50))
                .maxHeaderValueLength(Optional.of(4096))
                .maxQueryParams(Optional.of(32))
                .maxParamValueLength(Optional.of(1024))
                .maxBodyBytes(Optional.of(1048576))
                .allowedHeaderNames(List.of("Accept"))
                .blockedHeaderNames(List.of("X-Debug"))
                .allowedContentTypes(List.of("application/json"))
                .build();
    }

    private static UpstreamConfig upstreamConfig() {
        return UpstreamConfig.builder()
                .path(Optional.of("/v1/orders"))
                .connectTimeoutMs(Optional.of(2000))
                .readTimeoutMs(Optional.of(5000))
                .retry(Optional.of(new UpstreamConfig.Retry(Optional.of(true), Optional.of(3), Optional.of(true))))
                .notModified(Optional.of(new UpstreamConfig.NotModified(Optional.of(true))))
                .circuitBreaker(Optional.of(new UpstreamConfig.CircuitBreaker(Optional.of(5), Optional.of(30000))))
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
                    voCase("Metadata", new Metadata(Optional.of("v1")), new Metadata(Optional.of("v1")),
                            new Metadata(Optional.of("v2"))),
                    voCase("TlsConfig", tlsConfig(), tlsConfig(), TlsConfig.builder().build()),
                    voCase("TlsConfig.Mtls", new TlsConfig.Mtls(true, Optional.of("/ca")),
                            new TlsConfig.Mtls(true, Optional.of("/ca")), new TlsConfig.Mtls(false, Optional.empty())),
                    voCase("SecurityHeadersConfig", securityHeadersConfig(), securityHeadersConfig(),
                            SecurityHeadersConfig.builder().build()),
                    voCase("SecurityHeadersConfig.Hsts",
                            new SecurityHeadersConfig.Hsts(Optional.of(1), Optional.of(true)),
                            new SecurityHeadersConfig.Hsts(Optional.of(1), Optional.of(true)),
                            new SecurityHeadersConfig.Hsts(Optional.of(2), Optional.of(false))),
                    voCase("SecurityHeadersConfig.Cors",
                            new SecurityHeadersConfig.Cors(Optional.of(true), List.of("a"), List.of("GET"),
                                    List.of("Accept"), Optional.of(false)),
                            new SecurityHeadersConfig.Cors(Optional.of(true), List.of("a"), List.of("GET"),
                                    List.of("Accept"), Optional.of(false)),
                            new SecurityHeadersConfig.Cors(Optional.of(false), List.of("b"), List.of("POST"),
                                    List.of("Authorization"), Optional.of(true))),
                    voCase("SecurityDefaultsConfig", new SecurityDefaultsConfig(Optional.of("strict")),
                            new SecurityDefaultsConfig(Optional.of("strict")),
                            new SecurityDefaultsConfig(Optional.of("lenient"))),
                    voCase("AnchorConfig", anchorConfig(), anchorConfig(),
                            AnchorConfig.builder().name("bff").pathPrefix("/bff").build()),
                    voCase("ForwardedConfig",
                            new ForwardedConfig(List.of("10.0.0.0/8"), Optional.of(true), Optional.of("both")),
                            new ForwardedConfig(List.of("10.0.0.0/8"), Optional.of(true), Optional.of("both")),
                            new ForwardedConfig(List.of("192.168.0.0/16"), Optional.of(false),
                                    Optional.of("x-forwarded"))),
                    voCase("TokenValidationConfig", tokenValidationConfig(), tokenValidationConfig(),
                            new TokenValidationConfig(List.of())),
                    voCase("IssuerConfig", issuerConfig(), issuerConfig(),
                            new IssuerConfig("other", "https://other", Optional.empty(), Optional.empty())),
                    voCase("IssuerConfig.Jwks",
                            new IssuerConfig.Jwks("http", Optional.of("https://j"), Optional.empty(), List.of()),
                            new IssuerConfig.Jwks("http", Optional.of("https://j"), Optional.empty(), List.of()),
                            new IssuerConfig.Jwks("file", Optional.empty(), Optional.of("/jwks.json"), List.of())),
                    // The egress allowlist participates in identity: two otherwise-identical
                    // jwks blocks that differ only in allowed_egress_hosts are NOT equal, so a
                    // widened block can never be mistaken for a secure-default one.
                    voCase("IssuerConfig.Jwks.allowedEgressHosts",
                            new IssuerConfig.Jwks("http", Optional.of("https://j"), Optional.empty(),
                                    List.of("idp.internal")),
                            new IssuerConfig.Jwks("http", Optional.of("https://j"), Optional.empty(),
                                    List.of("idp.internal")),
                            new IssuerConfig.Jwks("http", Optional.of("https://j"), Optional.empty(), List.of())),
                    voCase("OidcConfig", oidcConfig(), oidcConfig(), OidcConfig.builder().build()),
                    voCase("OidcConfig.Logout",
                            new OidcConfig.Logout(Optional.of("/l"), Optional.empty(), Optional.empty(),
                                    Optional.empty()),
                            new OidcConfig.Logout(Optional.of("/l"), Optional.empty(), Optional.empty(),
                                    Optional.empty()),
                            new OidcConfig.Logout(Optional.of("/other"), Optional.empty(), Optional.empty(),
                                    Optional.empty())),
                    voCase("OidcConfig.Csrf", new OidcConfig.Csrf(List.of("https://a")),
                            new OidcConfig.Csrf(List.of("https://a")), new OidcConfig.Csrf(List.of("https://b"))),
                    voCase("OidcConfig.Refresh",
                            new OidcConfig.Refresh(Optional.of(true), Optional.of(60), Optional.of("reject")),
                            new OidcConfig.Refresh(Optional.of(true), Optional.of(60), Optional.of("reject")),
                            new OidcConfig.Refresh(Optional.of(false), Optional.empty(), Optional.empty())),
                    voCase("OidcConfig.StepUp", new OidcConfig.StepUp(Optional.of(true), Optional.of(false)),
                            new OidcConfig.StepUp(Optional.of(true), Optional.of(false)),
                            new OidcConfig.StepUp(Optional.of(false), Optional.of(true))),
                    voCase("UpstreamDefaultsConfig", new UpstreamDefaultsConfig(true, true),
                            new UpstreamDefaultsConfig(true, true), new UpstreamDefaultsConfig(false, true)),
                    voCase("EndpointConfig", endpointConfig(), endpointConfig(), EndpointConfig.builder()
                            .id("other").baseUrl("svc").auth(Optional.of(auth())).build()),
                    voCase("AuthConfig", auth(), auth(), new AuthConfig("none", List.of())),
                    voCase("RouteConfig", routeConfig(), routeConfig(),
                            RouteConfig.builder().id("other").match(matchConfig()).build()),
                    voCase("ResolvedRoute", resolvedRoute(), resolvedRoute(),
                            ResolvedRoute.builder().id("other").match(matchConfig()).effectiveAuth(auth())
                                    .upstream(resolvedUpstream()).build()),
                    voCase("MatchConfig", matchConfig(), matchConfig(),
                            MatchConfig.builder().pathPrefix("/other").build()),
                    voCase("MatchConfig.HeaderMatcher",
                            new MatchConfig.HeaderMatcher("X-Key", Optional.of(true), Optional.of("v")),
                            new MatchConfig.HeaderMatcher("X-Key", Optional.of(true), Optional.of("v")),
                            new MatchConfig.HeaderMatcher("X-Other", Optional.empty(), Optional.empty())),
                    voCase("SecurityFilterConfig", securityFilterConfig(), securityFilterConfig(),
                            SecurityFilterConfig.builder().build()),
                    voCase("ForwardConfig",
                            new ForwardConfig(List.of("Accept"), List.of("page"), Map.of("X-Gateway", "sheriff")),
                            new ForwardConfig(List.of("Accept"), List.of("page"), Map.of("X-Gateway", "sheriff")),
                            new ForwardConfig(List.of(), List.of(), Map.of())),
                    voCase("UpstreamConfig", upstreamConfig(), upstreamConfig(), UpstreamConfig.builder().build()),
                    voCase("UpstreamConfig.Retry",
                            new UpstreamConfig.Retry(Optional.of(true), Optional.of(3), Optional.of(true)),
                            new UpstreamConfig.Retry(Optional.of(true), Optional.of(3), Optional.of(true)),
                            new UpstreamConfig.Retry(Optional.of(false), Optional.empty(), Optional.empty())),
                    voCase("UpstreamConfig.NotModified", new UpstreamConfig.NotModified(Optional.of(true)),
                            new UpstreamConfig.NotModified(Optional.of(true)),
                            new UpstreamConfig.NotModified(Optional.of(false))),
                    voCase("UpstreamConfig.CircuitBreaker",
                            new UpstreamConfig.CircuitBreaker(Optional.of(5), Optional.of(30000)),
                            new UpstreamConfig.CircuitBreaker(Optional.of(5), Optional.of(30000)),
                            new UpstreamConfig.CircuitBreaker(Optional.of(1), Optional.of(1))),
                    voCase("RateLimitConfig", new RateLimitConfig(Optional.of(100), Optional.of(200)),
                            new RateLimitConfig(Optional.of(100), Optional.of(200)),
                            new RateLimitConfig(Optional.of(1), Optional.of(1))));
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
            AnchorConfig viaCtor = new AnchorConfig("api", "/api", Optional.of(auth()), Optional.empty(),
                    Optional.empty(), List.of(HttpMethod.GET));
            AnchorConfig viaBuilder = AnchorConfig.builder().name("api").pathPrefix("/api").auth(Optional.of(auth()))
                    .allowedMethods(List.of(HttpMethod.GET)).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void gatewayConfigBuilderMatchesConstructor() {
            GatewayConfig viaCtor = new GatewayConfig(2, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(HttpMethod.GET), Map.of("api", anchorConfig()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            GatewayConfig viaBuilder = GatewayConfig.builder().version(2).allowedMethods(List.of(HttpMethod.GET))
                    .anchors(Map.of("api", anchorConfig())).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void endpointConfigBuilderMatchesConstructor() {
            EndpointConfig viaCtor = new EndpointConfig("orders", true, "orders-service", Optional.of("api"),
                    Optional.of(auth()), List.of(HttpMethod.GET), Optional.empty(), List.of());
            EndpointConfig viaBuilder = EndpointConfig.builder().id("orders").enabled(true).baseUrl("orders-service")
                    .anchor(Optional.of("api")).auth(Optional.of(auth())).allowedMethods(List.of(HttpMethod.GET))
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
            GatewayConfig cfg = new GatewayConfig(1, null, null, null, null, null, null, null, null, null, null);
            assertTrue(cfg.metadata().isEmpty());
            assertTrue(cfg.tls().isEmpty());
            assertTrue(cfg.securityHeaders().isEmpty());
            assertTrue(cfg.securityDefaults().isEmpty());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertTrue(cfg.anchors().isEmpty());
            assertTrue(cfg.upstreamDefaults().isEmpty());
            assertTrue(cfg.forwarded().isEmpty());
            assertTrue(cfg.tokenValidation().isEmpty());
            assertTrue(cfg.oidc().isEmpty());
        }

        @Test
        void endpointConfigNormalizesAbsentCollectionsAndOptionals() {
            EndpointConfig cfg = new EndpointConfig("id", true, "url", null, null, null, null, null);
            assertTrue(cfg.anchor().isEmpty());
            assertTrue(cfg.auth().isEmpty());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertTrue(cfg.upstreamDefaults().isEmpty());
            assertTrue(cfg.routes().isEmpty());
        }

        @Test
        void routeConfigNormalizesAbsentAnchor() {
            RouteConfig cfg = new RouteConfig("id", null, null, matchConfig(), null, null, null, null, null);
            assertTrue(cfg.anchor().isEmpty());
            assertTrue(cfg.auth().isEmpty());
            assertTrue(cfg.securityFilter().isEmpty());
        }

        @Test
        void anchorConfigNormalizesAbsentComponents() {
            AnchorConfig cfg = new AnchorConfig("api", "/api", null, null, null, null);
            assertTrue(cfg.auth().isEmpty());
            assertTrue(cfg.securityFilter().isEmpty());
            assertTrue(cfg.securityHeaders().isEmpty());
            assertTrue(cfg.allowedMethods().isEmpty());
        }

        @Test
        void resolvedRouteNormalizesAbsentOptionals() {
            ResolvedRoute cfg = new ResolvedRoute("id", null, null, matchConfig(), auth(), null, null, null, true,
                    true, resolvedUpstream(), null);
            assertTrue(cfg.anchor().isEmpty());
            assertTrue(cfg.effectiveSecurityFilter().isEmpty());
            assertTrue(cfg.effectiveSecurityHeaders().isEmpty());
            assertTrue(cfg.effectiveAllowedMethods().isEmpty());
            assertEquals(Protocol.HTTP, cfg.protocol());
            assertTrue(cfg.effectiveForward().headersAllow().isEmpty(),
                    "an absent forward block normalizes to a deny-by-default empty allowlist");
            assertTrue(cfg.effectiveForward().queryAllow().isEmpty());
            assertTrue(cfg.effectiveForward().setHeaders().isEmpty());
        }

        @Test
        void collectionBearingRecordsNormalizeNullToEmpty() {
            assertTrue(new AuthConfig("none", null).requiredScopes().isEmpty());
            assertTrue(new TokenValidationConfig(null).issuers().isEmpty());
            assertTrue(new ForwardedConfig(null, null, null).trustedProxies().isEmpty());
            assertTrue(new ForwardConfig(null, null, null).setHeaders().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null).alpn().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null).passthroughSni().isEmpty());
            assertTrue(new MatchConfig("/p", null, null, null).methods().isEmpty());
            assertTrue(new SecurityFilterConfig(null, null, null, null, null, null, null, null, null, null)
                    .allowedPaths().isEmpty());
            assertTrue(new OidcConfig.Csrf(null).trustedOrigins().isEmpty());
            assertTrue(new AnchorConfig("api", "/api", null, null, null, null).allowedMethods().isEmpty());
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
            assertThrows(UnsupportedOperationException.class, () -> cfg.allowedMethods().add(HttpMethod.PUT));
        }

        @Test
        void anchorsMapIsDecoupledAndUnmodifiable() {
            Map<String, AnchorConfig> source = new HashMap<>();
            source.put("api", anchorConfig());
            GatewayConfig cfg = GatewayConfig.builder().version(1).anchors(source).build();
            source.put("bff", anchorConfig());
            assertEquals(Map.of("api", anchorConfig()), cfg.anchors(),
                    "mutating the source map after construction must not affect the record");
            assertThrows(UnsupportedOperationException.class, () -> cfg.anchors().put("extra", anchorConfig()));
        }

        @Test
        void mapComponentIsDecoupledAndUnmodifiable() {
            Map<String, String> source = new HashMap<>();
            source.put("X-Gateway", "sheriff");
            ForwardConfig cfg = ForwardConfig.builder().setHeaders(source).build();
            source.put("X-Extra", "leak");
            assertEquals(Map.of("X-Gateway", "sheriff"), cfg.setHeaders());
            assertThrows(UnsupportedOperationException.class, () -> cfg.setHeaders().put("X-New", "v"));
        }
    }

    // --- Mandatory fields --------------------------------------------------

    @Nested
    @DisplayName("Mandatory fields are rejected with NullPointerException")
    class MandatoryFields {

        @Test
        void endpointConfigRequiresIdAndBaseUrl() {
            assertThrows(NullPointerException.class,
                    () -> new EndpointConfig(null, true, "url", Optional.empty(), Optional.of(auth()), List.of(),
                            Optional.empty(), List.of()));
            assertThrows(NullPointerException.class,
                    () -> new EndpointConfig("id", true, null, Optional.empty(), Optional.of(auth()), List.of(),
                            Optional.empty(), List.of()));
        }

        @Test
        void endpointConfigAcceptsAnAbsentAuthBlock() {
            EndpointConfig cfg = new EndpointConfig("id", true, "url", Optional.of("api"), Optional.empty(), List.of(),
                    Optional.empty(), List.of());
            assertTrue(cfg.auth().isEmpty(), "an anchored endpoint may omit its auth block");
        }

        @Test
        void anchorConfigRequiresNameAndPathPrefix() {
            NullPointerException noName = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig(null, "/api", Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of()));
            assertEquals("name", noName.getMessage());
            NullPointerException noPrefix = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", null, Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of()));
            assertEquals("pathPrefix", noPrefix.getMessage());
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
                    () -> new IssuerConfig(null, "iss", Optional.empty(), Optional.empty()));
            assertThrows(NullPointerException.class,
                    () -> new IssuerConfig("name", null, Optional.empty(), Optional.empty()));
        }

        @Test
        void jwksRequiresSource() {
            assertThrows(NullPointerException.class,
                    () -> new IssuerConfig.Jwks(null, Optional.empty(), Optional.empty(), List.of()));
        }

        @Test
        void matchConfigRequiresPathPrefix() {
            assertThrows(NullPointerException.class, () -> new MatchConfig(null, List.of(), Optional.empty(), List.of()));
        }

        @Test
        void headerMatcherRequiresName() {
            assertThrows(NullPointerException.class,
                    () -> new MatchConfig.HeaderMatcher(null, Optional.empty(), Optional.empty()));
        }

        @Test
        void routeConfigRequiresIdAndMatch() {
            MatchConfig match = matchConfig();
            assertThrows(NullPointerException.class, () -> new RouteConfig(null, Optional.empty(), Optional.empty(),
                    match, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            assertThrows(NullPointerException.class, () -> new RouteConfig("id", Optional.empty(), Optional.empty(),
                    null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Test
        void resolvedRouteRequiresIdMatchAuthAndUpstream() {
            assertThrows(NullPointerException.class, () -> new ResolvedRoute(null, Protocol.HTTP, Optional.empty(),
                    matchConfig(), auth(), List.of(), Optional.empty(), Optional.empty(), true, true,
                    resolvedUpstream(), null));
            assertThrows(NullPointerException.class, () -> new ResolvedRoute("id", Protocol.HTTP, Optional.empty(), null,
                    auth(), List.of(), Optional.empty(), Optional.empty(), true, true, resolvedUpstream(), null));
            assertThrows(NullPointerException.class, () -> new ResolvedRoute("id", Protocol.HTTP, Optional.empty(),
                    matchConfig(), null, List.of(), Optional.empty(), Optional.empty(), true, true, resolvedUpstream(),
                    null));
            assertThrows(NullPointerException.class, () -> new ResolvedRoute("id", Protocol.HTTP, Optional.empty(),
                    matchConfig(), auth(), List.of(), Optional.empty(), Optional.empty(), true, true, null, null));
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
                    .anchor(Optional.of("api"))
                    .auth(Optional.of(auth()))
                    .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                    .build();
            assertEquals("orders", cfg.id());
            assertTrue(cfg.enabled());
            assertEquals(Optional.of("api"), cfg.anchor());
            assertEquals(Optional.of(auth()), cfg.auth());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.allowedMethods());
        }

        @Test
        void endpointConfigEnabledDefaultsToFalseWhenUnsetOnTheRecord() {
            EndpointConfig cfg = EndpointConfig.builder().id("orders").baseUrl("orders-service")
                    .auth(Optional.of(auth())).build();
            assertFalse(cfg.enabled(), "the record itself applies no default; the YAML loader owns the true default");
        }

        @Test
        void routeConfigExposesAnchorOverride() {
            RouteConfig cfg = RouteConfig.builder().id("r").anchor(Optional.of("bff")).match(matchConfig()).build();
            assertEquals(Optional.of("bff"), cfg.anchor());
        }

        @Test
        void anchorConfigExposesEveryPolicyBlock() {
            AnchorConfig cfg = anchorConfig();
            assertEquals("api", cfg.name());
            assertEquals("/api", cfg.pathPrefix());
            assertEquals(Optional.of(auth()), cfg.auth());
            assertTrue(cfg.securityFilter().isPresent());
            assertTrue(cfg.securityHeaders().isPresent());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.allowedMethods());
        }

        @Test
        void resolvedRouteCarriesTheMaterializedEffectivePosture() {
            ResolvedRoute cfg = resolvedRoute();
            assertEquals(Optional.of("api"), cfg.anchor());
            assertEquals("bearer", cfg.effectiveAuth().require());
            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), cfg.effectiveAllowedMethods());
            assertTrue(cfg.effectiveSecurityFilter().isPresent());
            assertTrue(cfg.effectiveSecurityHeaders().isPresent());
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
}
