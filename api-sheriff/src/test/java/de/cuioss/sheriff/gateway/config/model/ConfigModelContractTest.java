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
package de.cuioss.sheriff.gateway.config.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
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
 *       additions ({@link AnchorConfig}, and the anchor fields on {@link EndpointConfig} and
 *       {@link RouteConfig}); the resolved per-route record lives in the routing package and is
 *       covered by its own contract test there,</li>
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

    /** The back-channel origin of the {@link #oidcConfig()} fixture: an internal address, not a secret. */
    private static final String BACK_CHANNEL_ORIGIN = "https://keycloak:8443";
    private static final String SESSION_KEY_FILE = "/etc/sheriff/keys/session.key";

    private static AuthConfig auth() {
        return AuthConfig.builder().require(Require.BEARER).build();
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
                .cookies(cookiesConfig())
                .build();
    }

    private static GatewayConfig gatewayConfig() {
        return GatewayConfig.builder()
                .version(1)
                .metadata(new Metadata("2024-01"))
                .tls(tlsConfig())
                .management(managementConfig())
                .securityHeaders(securityHeadersConfig())
                .securityDefaults(SecurityDefaultsConfig.builder().profile("strict")
                        .maxAuthorizationHeaderValueLength(8192).build())
                .allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .anchors(Map.of("api", anchorConfig()))
                .upstreamDefaults(UpstreamDefaultsConfig.defaults())
                .forwarded(new ForwardedConfig(List.of("10.0.0.0/8"), true, "both"))
                .tokenValidation(tokenValidationConfig())
                .oidc(oidcConfig())
                .portal(portalConfig())
                .build();
    }

    private static PortalConfig portalConfig() {
        return PortalConfig.builder()
                .path("/")
                .title("Applications")
                .templateDir("/app/portal")
                .cacheSeconds(60)
                .errorPages(true)
                .build();
    }

    private static CatalogConfig catalogConfig() {
        return CatalogConfig.builder()
                .title("Orders")
                .description("Order management")
                .entry("/orders/")
                .order(10)
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
                .contentSecurityPolicy("default-src 'self'")
                .cors(new SecurityHeadersConfig.Cors(true, List.of("https://app.example.com"),
                        List.of("GET"), List.of("Authorization"), false))
                .build();
    }

    private static SecurityHeadersConfig.HeaderModes frameDenyDefault() {
        return SecurityHeadersConfig.HeaderModes.builder().frameDeny(SecurityHeadersConfig.HeaderMode.DEFAULT).build();
    }

    private static TokenValidationConfig tokenValidationConfig() {
        return new TokenValidationConfig(List.of(issuerConfig()));
    }

    private static IssuerConfig issuerConfig() {
        return IssuerConfig.builder()
                .name("primary")
                .issuer("https://issuer.example.com")
                .audience("api-sheriff")
                .jwks(new IssuerConfig.Jwks("http", "https://issuer.example.com/jwks", null, List.of(), null, 900,
                        120, 15))
                .build();
    }

    private static IssuerConfig.Jwks jwksWithRefresh(@Nullable Integer refreshIntervalSeconds,
            @Nullable Integer keyRotationGracePeriodSeconds, @Nullable Integer unknownKidRefreshWindowSeconds) {
        return new IssuerConfig.Jwks("http", "https://j", null, List.of(), null, refreshIntervalSeconds,
                keyRotationGracePeriodSeconds, unknownKidRefreshWindowSeconds);
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
                        .keyFile(SESSION_KEY_FILE)
                        .ttlSeconds(3600)
                        .csrf(new OidcConfig.Csrf(List.of("https://app.example.com")))
                        .refresh(new OidcConfig.Refresh(true, 60, "reauthenticate"))
                        .maxSessions(10000)
                        .build())
                .stepUp(new OidcConfig.StepUp(true, false, "/session/step-up"))
                .userInfo(userInfo())
                .login(new OidcConfig.Login("/session/login", null))
                .backChannelOrigin(BACK_CHANNEL_ORIGIN)
                .backChannelAllowInsecureHttp(false)
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
                .catalog(catalogConfig())
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
                .forward(forwardConfig())
                .upstream(upstreamConfig())
                .rateLimit(new RateLimitConfig(100, 200))
                .websocket(webSocketConfig())
                .build();
    }

    private static ForwardConfig forwardConfig() {
        return ForwardConfig.builder()
                .headersAllow(List.of("Accept"))
                .queryAllow(List.of("page"))
                .setHeaders(Map.of("X-Gateway", "api-sheriff"))
                .build();
    }

    private static CookiesConfig cookiesConfig() {
        return new CookiesConfig(new CookieAllow.Named(List.of("app_session", "theme")), true,
                List.of("https://app.example.com"));
    }

    private static WebSocketConfig webSocketConfig() {
        return WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com")).idleTimeoutSeconds(300)
                .build();
    }

    private static MatchConfig matchConfig() {
        return MatchConfig.builder()
                .pathPrefix("/orders")
                .methods(List.of(HttpMethod.GET))
                .host("api.example.com")
                .headers(List.of(new MatchConfig.HeaderMatcher("X-Tenant", true, "acme")))
                .build();
    }

    private static RedirectConfig redirectConfig() {
        return RedirectConfig.builder()
                .location("/new-home")
                .status(308)
                .keepQuery(true)
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
                .rewriteLocation(true)
                .connectTimeoutMs(2000)
                .readTimeoutMs(5000)
                .retry(UpstreamConfig.Retry.builder().enabled(true).maxAttempts(3).idempotentOnly(true).build())
                .notModified(UpstreamConfig.NotModified.builder().enabled(true).build())
                .build();
    }

    // --- equals / hashCode / toString contract ----------------------------

    @Nested
    @DisplayName("equals / hashCode / toString contract")
    class ValueObjectContract {

        private static Arguments voCase(String name, Object subject, Object equalCopy, Object different) {
            return Arguments.of(Named.named(name, subject), equalCopy, different);
        }

        private static SecurityHeadersConfig headersWithPolicy(String contentSecurityPolicy) {
            return SecurityHeadersConfig.builder().contentTypeNosniff(true).frameDeny(true)
                    .contentSecurityPolicy(contentSecurityPolicy).build();
        }

        private static SecurityDefaultsConfig securityDefaults(@Nullable String profile,
                @Nullable Integer maxAuthorizationHeaderValueLength, @Nullable Boolean allowGetWithContentLengthBody,
                @Nullable Boolean allowExtendedAscii) {
            return securityDefaults(profile, maxAuthorizationHeaderValueLength, allowGetWithContentLengthBody,
                    allowExtendedAscii, null);
        }

        private static SecurityDefaultsConfig securityDefaults(@Nullable String profile,
                @Nullable Integer maxAuthorizationHeaderValueLength, @Nullable Boolean allowGetWithContentLengthBody,
                @Nullable Boolean allowExtendedAscii, @Nullable Boolean uniformNotFound) {
            return SecurityDefaultsConfig.builder()
                    .profile(profile)
                    .maxAuthorizationHeaderValueLength(maxAuthorizationHeaderValueLength)
                    .allowGetWithContentLengthBody(allowGetWithContentLengthBody)
                    .allowExtendedAscii(allowExtendedAscii)
                    .uniformNotFound(uniformNotFound)
                    .build();
        }

        static Stream<Arguments> valueObjects() {
            return Stream.of(
                    voCase("GatewayConfig", gatewayConfig(), gatewayConfig(),
                            GatewayConfig.builder().version(99).build()),
                    voCase("Metadata", new Metadata("v1"), new Metadata("v1"),
                            new Metadata("v2")),
                    voCase("PortalConfig", portalConfig(), portalConfig(),
                            new PortalConfig("/portal", "Applications", null, null, null)),
                    voCase("CatalogConfig", catalogConfig(), catalogConfig(),
                            new CatalogConfig("Billing", null, "/billing/", null)),
                    voCase("TlsConfig", tlsConfig(), tlsConfig(), TlsConfig.builder().build()),
                    voCase("ManagementConfig", managementConfig(), managementConfig(),
                            ManagementConfig.builder().build()),
                    voCase("ManagementConfig.ManagementTls", new ManagementConfig.ManagementTls(true),
                            new ManagementConfig.ManagementTls(true), new ManagementConfig.ManagementTls(false)),
                    voCase("TlsConfig.Mtls", new TlsConfig.Mtls(true, "/ca"),
                            new TlsConfig.Mtls(true, "/ca"), new TlsConfig.Mtls(false, null)),
                    voCase("SecurityHeadersConfig", securityHeadersConfig(), securityHeadersConfig(),
                            SecurityHeadersConfig.builder().build()),
                    // content_security_policy participates in identity: the unequal instance varies only
                    // that component, so dropping it from equals() fails this case alone.
                    voCase("SecurityHeadersConfig (content_security_policy)",
                            headersWithPolicy("default-src 'self'"),
                            headersWithPolicy("default-src 'self'"),
                            headersWithPolicy("default-src 'none'")),
                    voCase("SecurityHeadersConfig (header_modes)",
                            SecurityHeadersConfig.builder().frameDeny(true).headerModes(frameDenyDefault()).build(),
                            SecurityHeadersConfig.builder().frameDeny(true).headerModes(frameDenyDefault()).build(),
                            SecurityHeadersConfig.builder().frameDeny(true).build()),
                    voCase("SecurityHeadersConfig.HeaderModes", frameDenyDefault(), frameDenyDefault(),
                            SecurityHeadersConfig.HeaderModes.builder()
                                    .frameDeny(SecurityHeadersConfig.HeaderMode.SET).build()),
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
                    // One case per component, so the record contract is proven to discriminate on ALL
                    // FIVE: a single unequal instance differing only in 'profile' would leave
                    // max_authorization_header_value_length, allow_get_with_content_length_body,
                    // allow_extended_ascii and uniform_not_found silently out of equals(). Each case varies
                    // exactly one component and holds the other four fixed, so a component dropped from
                    // equals() fails its own case alone.
                    voCase("SecurityDefaultsConfig (profile)",
                            securityDefaults("strict", 8192, null, null),
                            securityDefaults("strict", 8192, null, null),
                            securityDefaults("lenient", 8192, null, null)),
                    voCase("SecurityDefaultsConfig (max_authorization_header_value_length)",
                            securityDefaults("strict", 8192, null, null),
                            securityDefaults("strict", 8192, null, null),
                            securityDefaults("strict", 4096, null, null)),
                    voCase("SecurityDefaultsConfig (allow_get_with_content_length_body)",
                            securityDefaults("strict", 8192, false, null),
                            securityDefaults("strict", 8192, false, null),
                            securityDefaults("strict", 8192, true, null)),
                    voCase("SecurityDefaultsConfig (allow_extended_ascii)",
                            securityDefaults("strict", 8192, null, false),
                            securityDefaults("strict", 8192, null, false),
                            securityDefaults("strict", 8192, null, true)),
                    voCase("SecurityDefaultsConfig (uniform_not_found)",
                            securityDefaults("strict", 8192, null, null, true),
                            securityDefaults("strict", 8192, null, null, true),
                            securityDefaults("strict", 8192, null, null, false)),
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
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null, null, null, null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null, null, null, null),
                            new IssuerConfig.Jwks("file", null, "/jwks.json", List.of(), null, null, null, null)),
                    // One case per refresh component: each unequal instance varies exactly one of the
                    // three and holds the other two fixed, so a component dropped from equals() fails
                    // its own case alone.
                    voCase("IssuerConfig.Jwks.refreshIntervalSeconds", jwksWithRefresh(900, 120, 15),
                            jwksWithRefresh(900, 120, 15), jwksWithRefresh(901, 120, 15)),
                    voCase("IssuerConfig.Jwks.keyRotationGracePeriodSeconds", jwksWithRefresh(900, 120, 15),
                            jwksWithRefresh(900, 120, 15), jwksWithRefresh(900, 121, 15)),
                    voCase("IssuerConfig.Jwks.unknownKidRefreshWindowSeconds", jwksWithRefresh(900, 120, 15),
                            jwksWithRefresh(900, 120, 15), jwksWithRefresh(900, 120, 16)),
                    // The egress allowlist participates in identity: two otherwise-identical
                    // jwks blocks that differ only in allowed_egress_hosts are NOT equal, so an
                    // explicit, authoritative list can never be mistaken for one that derives its
                    // allowance from jwks.url.
                    voCase("IssuerConfig.Jwks.allowedEgressHosts",
                            new IssuerConfig.Jwks("http", "https://j", null, List.of("idp.internal"), null, null, null, null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of("idp.internal"), null, null, null, null),
                            new IssuerConfig.Jwks("http", "https://j", null, List.of(), null, null, null, null)),
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
                    voCase("OidcConfig.StepUp", new OidcConfig.StepUp(true, false, null),
                            new OidcConfig.StepUp(true, false, null),
                            new OidcConfig.StepUp(false, true, null)),
                    // The step-up path participates in identity: the unequal instance varies only that
                    // component, so dropping it from equals() fails this case alone.
                    voCase("OidcConfig.StepUp (path)", new OidcConfig.StepUp(null, null, "/auth/step-up"),
                            new OidcConfig.StepUp(null, null, "/auth/step-up"),
                            new OidcConfig.StepUp(null, null, "/auth/other")),
                    voCase("OidcConfig.UserInfo", userInfo(), userInfo(),
                            new OidcConfig.UserInfo("/other", List.of("sub"), List.of())),
                    voCase("OidcConfig.Login", new OidcConfig.Login("/session/login", "/home"),
                            new OidcConfig.Login("/session/login", "/home"),
                            new OidcConfig.Login("/session/login", "/elsewhere")),
                    // One case per component: each unequal instance varies exactly one of the two, so a
                    // component dropped from equals() fails its own case alone.
                    voCase("OidcConfig.ClientAuthenticationSettings (key_file)",
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/client-auth.pem",
                                    "/auth/client-keys"),
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/client-auth.pem",
                                    "/auth/client-keys"),
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/other.pem",
                                    "/auth/client-keys")),
                    voCase("OidcConfig.ClientAuthenticationSettings (jwks_path)",
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/client-auth.pem",
                                    "/auth/client-keys"),
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/client-auth.pem",
                                    "/auth/client-keys"),
                            new OidcConfig.ClientAuthenticationSettings("/etc/sheriff/keys/client-auth.pem",
                                    "/auth/other-keys")),
                    // client_authentication participates in identity: the unequal instance varies only
                    // that component, so dropping it from equals() fails this case alone.
                    voCase("OidcConfig (client_authentication)",
                            OidcConfig.builder().clientAuthentication(
                                    new OidcConfig.ClientAuthenticationSettings("/keys/a.pem", null)).build(),
                            OidcConfig.builder().clientAuthentication(
                                    new OidcConfig.ClientAuthenticationSettings("/keys/a.pem", null)).build(),
                            OidcConfig.builder().clientAuthentication(
                                    new OidcConfig.ClientAuthenticationSettings("/keys/b.pem", null)).build()),
                    voCase("OidcConfig.SenderConstraintSettings",
                            new OidcConfig.SenderConstraintSettings("/etc/sheriff/keys/dpop.pem"),
                            new OidcConfig.SenderConstraintSettings("/etc/sheriff/keys/dpop.pem"),
                            new OidcConfig.SenderConstraintSettings("/etc/sheriff/keys/other.pem")),
                    // sender_constraint participates in identity: the unequal instance varies only that
                    // component, so dropping it from equals() fails this case alone.
                    voCase("OidcConfig (sender_constraint)",
                            OidcConfig.builder().senderConstraint(
                                    new OidcConfig.SenderConstraintSettings("/keys/a.pem")).build(),
                            OidcConfig.builder().senderConstraint(
                                    new OidcConfig.SenderConstraintSettings("/keys/a.pem")).build(),
                            OidcConfig.builder().senderConstraint(
                                    new OidcConfig.SenderConstraintSettings("/keys/b.pem")).build()),
                    // One case per back-channel component: each unequal instance varies exactly one of the
                    // two and holds the other fixed, so a component dropped from equals() fails its own
                    // case alone.
                    voCase("OidcConfig (back_channel_origin)",
                            OidcConfig.builder().backChannelOrigin(BACK_CHANNEL_ORIGIN).build(),
                            OidcConfig.builder().backChannelOrigin(BACK_CHANNEL_ORIGIN).build(),
                            OidcConfig.builder().backChannelOrigin("https://idp.internal:9443").build()),
                    voCase("OidcConfig (back_channel_allow_insecure_http)",
                            OidcConfig.builder().backChannelOrigin("http://keycloak:8080")
                                    .backChannelAllowInsecureHttp(true).build(),
                            OidcConfig.builder().backChannelOrigin("http://keycloak:8080")
                                    .backChannelAllowInsecureHttp(true).build(),
                            OidcConfig.builder().backChannelOrigin("http://keycloak:8080").build()),
                    voCase("UpstreamDefaultsConfig",
                            UpstreamDefaultsConfig.builder().retryEnabled(true).notModifiedEnabled(true).build(),
                            UpstreamDefaultsConfig.builder().retryEnabled(true).notModifiedEnabled(true).build(),
                            UpstreamDefaultsConfig.builder().retryEnabled(false).notModifiedEnabled(true).build()),
                    voCase("EndpointConfig", endpointConfig(), endpointConfig(), EndpointConfig.builder()
                            .id("other").baseUrl("svc").auth(auth()).build()),
                    voCase("EndpointConfig.scopes", endpointConfig(), endpointConfig(), EndpointConfig.builder()
                            .id(endpointConfig().id()).enabled(endpointConfig().enabled())
                            .baseUrl(endpointConfig().baseUrl()).anchor(endpointConfig().anchor())
                            .auth(endpointConfig().auth()).scopes(List.of("orders.write"))
                            .allowedMethods(endpointConfig().allowedMethods())
                            .upstreamDefaults(endpointConfig().upstreamDefaults())
                            .routes(endpointConfig().routes()).build()),
                    voCase("AuthConfig", auth(), auth(), AuthConfig.builder().require(Require.NONE).build()),
                    voCase("AuthConfig.tokenRelay",
                            AuthConfig.builder().require(Require.SESSION).tokenRelay(false).build(),
                            AuthConfig.builder().require(Require.SESSION).tokenRelay(false).build(),
                            AuthConfig.builder().require(Require.SESSION).tokenRelay(true).build()),
                    // session_fallback participates in identity: the unequal instance varies only that
                    // component, so dropping it from equals() fails this case alone.
                    voCase("AuthConfig.sessionFallback",
                            AuthConfig.builder().require(Require.BEARER).sessionFallback(true).build(),
                            AuthConfig.builder().require(Require.BEARER).sessionFallback(true).build(),
                            AuthConfig.builder().require(Require.BEARER).sessionFallback(false).build()),
                    voCase("RouteConfig", routeConfig(), routeConfig(),
                            RouteConfig.builder().id("other").match(matchConfig()).build()),
                    voCase("MatchConfig", matchConfig(), matchConfig(),
                            MatchConfig.builder().pathPrefix("/other").build()),
                    // The matcher form participates in identity: an exact path and a prefix
                    // spelling the same string are different addresses, so they must not be equal.
                    voCase("MatchConfig (exact path vs prefix of the same string)",
                            MatchConfig.builder().path("/orders").build(),
                            MatchConfig.builder().path("/orders").build(),
                            MatchConfig.builder().pathPrefix("/orders").build()),
                    voCase("RedirectConfig (location)", redirectConfig(), redirectConfig(),
                            new RedirectConfig("/other", 308, true, false)),
                    voCase("RedirectConfig (status)", redirectConfig(), redirectConfig(),
                            new RedirectConfig("/new-home", 301, true, false)),
                    voCase("RedirectConfig (keep_query)", redirectConfig(), redirectConfig(),
                            new RedirectConfig("/new-home", 308, false, false)),
                    voCase("RedirectConfig (allow_external)", redirectConfig(), redirectConfig(),
                            new RedirectConfig("/new-home", 308, true, true)),
                    voCase("MatchConfig.HeaderMatcher",
                            new MatchConfig.HeaderMatcher("X-Key", true, "v"),
                            new MatchConfig.HeaderMatcher("X-Key", true, "v"),
                            new MatchConfig.HeaderMatcher("X-Other", null, null)),
                    voCase("SecurityFilterConfig", securityFilterConfig(), securityFilterConfig(),
                            SecurityFilterConfig.builder().build()),
                    voCase("ForwardConfig",
                            ForwardConfig.builder().headersAllow(List.of("Accept")).queryAllow(List.of("page"))
                                    .setHeaders(Map.of("X-Gateway", "sheriff")).build(),
                            ForwardConfig.builder().headersAllow(List.of("Accept")).queryAllow(List.of("page"))
                                    .setHeaders(Map.of("X-Gateway", "sheriff")).build(),
                            ForwardConfig.builder().headersAllow(List.of()).queryAllow(List.of())
                                    .setHeaders(Map.of()).build()),
                    voCase("ForwardConfig (negative-list mode)",
                            ForwardConfig.builder().headersDeny(List.of("X-Internal")).queryDeny(List.of("debug"))
                                    .setHeaders(Map.of()).build(),
                            ForwardConfig.builder().headersDeny(List.of("X-Internal")).queryDeny(List.of("debug"))
                                    .setHeaders(Map.of()).build(),
                            ForwardConfig.builder().headersDeny(List.of("X-Internal")).setHeaders(Map.of()).build()),
                    // One case per cookies component: each unequal instance varies exactly one of the
                    // three, so a component dropped from equals() fails its own case alone.
                    voCase("CookiesConfig (allow)", cookiesConfig(), cookiesConfig(),
                            new CookiesConfig(CookieAllow.ALL, true, List.of("https://app.example.com"))),
                    voCase("CookiesConfig (same_origin_check)", cookiesConfig(), cookiesConfig(),
                            new CookiesConfig(cookiesConfig().allow(), false, List.of("https://app.example.com"))),
                    voCase("CookiesConfig (additional_origins)", cookiesConfig(), cookiesConfig(),
                            new CookiesConfig(cookiesConfig().allow(), true, List.of("https://other.example.com"))),
                    voCase("CookieAllow.Named", new CookieAllow.Named(List.of("theme")),
                            new CookieAllow.Named(List.of("theme")), new CookieAllow.Named(List.of("Theme"))),
                    voCase("WebSocketConfig",
                            WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com"))
                                    .idleTimeoutSeconds(300).build(),
                            WebSocketConfig.builder().allowedOrigins(List.of("https://app.example.com"))
                                    .idleTimeoutSeconds(300).build(),
                            WebSocketConfig.builder().allowedOrigins(List.of("https://other.example.com"))
                                    .idleTimeoutSeconds(60).build()),
                    voCase("UpstreamConfig", upstreamConfig(), upstreamConfig(), UpstreamConfig.builder().build()),
                    // rewrite_location participates in identity: the case varies only that component, so
                    // dropping it from equals() fails this case alone.
                    voCase("UpstreamConfig (rewrite_location)",
                            UpstreamConfig.builder().path("/v1").rewriteLocation(true).build(),
                            UpstreamConfig.builder().path("/v1").rewriteLocation(true).build(),
                            UpstreamConfig.builder().path("/v1").build()),
                    voCase("UpstreamConfig.Retry",
                            UpstreamConfig.Retry.builder().enabled(true).maxAttempts(3).idempotentOnly(true).build(),
                            UpstreamConfig.Retry.builder().enabled(true).maxAttempts(3).idempotentOnly(true).build(),
                            UpstreamConfig.Retry.builder().enabled(false).build()),
                    voCase("UpstreamConfig.NotModified", UpstreamConfig.NotModified.builder().enabled(true).build(),
                            UpstreamConfig.NotModified.builder().enabled(true).build(),
                            UpstreamConfig.NotModified.builder().enabled(false).build()),
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
            // The toString leg used to be assertNotNull(subject.toString()), which no implementation
            // of these records could ever have failed. What the class actually claims to verify is a
            // usable diagnostic rendering: it names its own type, it agrees with equals, and it can
            // tell two unequal configurations apart — none of which an inherited Object.toString does.
            String rendered = subject.toString();
            assertTrue(rendered.startsWith(subject.getClass().getSimpleName()),
                    () -> "a diagnostic rendering names its own type rather than a package-qualified "
                            + "identity hash: " + rendered);
            assertEquals(equalCopy.toString(), rendered,
                    "equal instances render equally — an identity-based rendering would not");
            assertNotEquals(different.toString(), rendered,
                    "a rendering that cannot tell two unequal configurations apart is useless in the "
                            + "diagnostic this contract exists for");
        }
    }

    // --- Builder equivalence ----------------------------------------------

    @Nested
    @DisplayName("Lombok @Builder equivalence with the canonical constructor")
    class BuilderEquivalence {

        @Test
        void authConfigBuilderMatchesConstructor() {
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            AuthConfig viaCtor = new AuthConfig(Require.BEARER, false, true);
            AuthConfig viaBuilder = AuthConfig.builder().require(Require.BEARER).tokenRelay(false)
                    .sessionFallback(true).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void anchorConfigBuilderMatchesConstructor() {
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            AnchorConfig viaCtor = new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED,
                    auth(), null, null, List.of(HttpMethod.GET), CookiesConfig.NONE);
            AnchorConfig viaBuilder = AnchorConfig.builder().name("api").pathPrefix("/api").type(AnchorType.PROXY)
                    .access(AccessLevel.AUTHENTICATED).auth(auth())
                    .allowedMethods(List.of(HttpMethod.GET)).cookies(CookiesConfig.NONE).build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void gatewayConfigBuilderMatchesConstructor() {
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            GatewayConfig viaCtor = new GatewayConfig(2, null, null, null, null, null,
                    List.of(HttpMethod.GET), CookiesConfig.NONE, Map.of("api", anchorConfig()), null, null, null,
                    null, null, null, null, portalConfig());
            GatewayConfig viaBuilder = GatewayConfig.builder().version(2).allowedMethods(List.of(HttpMethod.GET))
                    .cookies(CookiesConfig.NONE).anchors(Map.of("api", anchorConfig())).portal(portalConfig())
                    .build();
            assertEquals(viaCtor, viaBuilder);
            assertEquals(portalConfig(), viaCtor.portal());
        }

        @Test
        void endpointConfigBuilderMatchesConstructor() {
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            EndpointConfig viaCtor = new EndpointConfig("orders", true, "orders-service", "api",
                    auth(), List.of("orders.read"), List.of(HttpMethod.GET), null, null, null, CookiesConfig.NONE,
                    List.of(), catalogConfig());
            EndpointConfig viaBuilder = EndpointConfig.builder().id("orders").enabled(true).baseUrl("orders-service")
                    .anchor("api").auth(auth()).scopes(List.of("orders.read")).allowedMethods(List.of(HttpMethod.GET))
                    .cookies(CookiesConfig.NONE).catalog(catalogConfig()).build();
            assertEquals(viaCtor, viaBuilder);
            assertEquals(catalogConfig(), viaCtor.catalog());
        }

        @Test
        void portalConfigBuilderMatchesConstructor() {
            PortalConfig viaCtor = new PortalConfig("/", "Applications", "/app/portal", 60, true);
            assertEquals(viaCtor, portalConfig());
        }

        @Test
        void catalogConfigBuilderMatchesConstructor() {
            CatalogConfig viaCtor = new CatalogConfig("Orders", "Order management", "/orders/", 10);
            assertEquals(viaCtor, catalogConfig());
        }

        @Test
        void securityHeadersConfigBuilderMatchesConstructor() {
            SecurityHeadersConfig.Hsts hsts = new SecurityHeadersConfig.Hsts(600, false);
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            SecurityHeadersConfig viaCtor = new SecurityHeadersConfig(hsts, null, true, "default-src 'self'",
                    frameDenyDefault(), null);
            SecurityHeadersConfig viaBuilder = SecurityHeadersConfig.builder().hsts(hsts).frameDeny(true)
                    .contentSecurityPolicy("default-src 'self'").headerModes(frameDenyDefault()).build();
            assertAll("content_security_policy and header_modes sit, in that order, between frame_deny and cors",
                    () -> assertEquals(viaCtor, viaBuilder),
                    () -> assertEquals("default-src 'self'", viaCtor.contentSecurityPolicy()),
                    () -> assertEquals(frameDenyDefault(), viaCtor.headerModes()),
                    () -> assertNull(viaCtor.cors()));
        }

        @Test
        void stepUpBuilderMatchesConstructorAndCarriesThePath() {
            OidcConfig.StepUp viaCtor = new OidcConfig.StepUp(null, null, "/auth/step-up");
            OidcConfig.StepUp viaBuilder = OidcConfig.StepUp.builder().path("/auth/step-up").build();
            assertAll("the step-up path is the third component and is independent of the RFC 9470 keys",
                    () -> assertEquals(viaCtor, viaBuilder),
                    () -> assertEquals("/auth/step-up", viaCtor.path()),
                    () -> assertNull(viaCtor.enabled(), "the path does not imply enabled"),
                    () -> assertNull(viaCtor.honorUpstreamChallenge()),
                    () -> assertNull(new OidcConfig.StepUp(true, true, null).path(),
                            "an omitted path stays absent"));
        }

        @Test
        void jwksBuilderMatchesConstructorAndCarriesTheRefreshKeys() {
            IssuerConfig.Jwks viaCtor = jwksWithRefresh(900, 120, 15);
            IssuerConfig.Jwks viaBuilder = IssuerConfig.Jwks.builder().source("http").url("https://j")
                    .refreshIntervalSeconds(900).keyRotationGracePeriodSeconds(120)
                    .unknownKidRefreshWindowSeconds(15).build();
            IssuerConfig.Jwks omitted = jwksWithRefresh(null, null, null);
            assertAll("the three refresh keys are the last three components, in declaration order",
                    () -> assertEquals(viaCtor, viaBuilder),
                    () -> assertEquals(900, viaCtor.refreshIntervalSeconds()),
                    () -> assertEquals(120, viaCtor.keyRotationGracePeriodSeconds()),
                    () -> assertEquals(15, viaCtor.unknownKidRefreshWindowSeconds()),
                    () -> assertNull(omitted.refreshIntervalSeconds(), "an omitted interval stays absent"),
                    () -> assertNull(omitted.keyRotationGracePeriodSeconds(), "an omitted grace period stays absent"),
                    () -> assertNull(omitted.unknownKidRefreshWindowSeconds(), "an omitted window stays absent"));
        }

        @Test
        void oidcConfigBuilderMatchesConstructorAndCarriesTheBackChannelKeys() {
            // Positional on purpose: the canonical constructor is the subject the builder is compared to.
            OidcConfig viaCtor = new OidcConfig("https://idp.example.com/realms/demo", null, null, List.of("openid"),
                    null, null, null, null, null, null, null, null, "http://keycloak:8080", true);
            OidcConfig viaBuilder = OidcConfig.builder().issuer("https://idp.example.com/realms/demo")
                    .scopes(List.of("openid")).backChannelOrigin("http://keycloak:8080")
                    .backChannelAllowInsecureHttp(true).build();
            OidcConfig omitted = OidcConfig.builder().issuer("https://idp.example.com/realms/demo").build();
            assertAll("the back-channel origin and its opt-in are the last two components, in that order",
                    () -> assertEquals(viaCtor, viaBuilder),
                    () -> assertEquals("http://keycloak:8080", viaCtor.backChannelOrigin()),
                    () -> assertEquals(Boolean.TRUE, viaCtor.backChannelAllowInsecureHttp()),
                    () -> assertEquals("https://idp.example.com/realms/demo", viaCtor.issuer(),
                            "the origin is transport; the issuer stays the identity"),
                    () -> assertNull(omitted.backChannelOrigin(), "an omitted back_channel_origin stays absent"),
                    () -> assertNull(omitted.backChannelAllowInsecureHttp(),
                            "an omitted opt-in stays absent; it reads as false where it is consumed"));
        }

        @Test
        void redirectConfigBuilderMatchesConstructor() {
            RedirectConfig viaCtor = new RedirectConfig("/new-home", 308, true, false);
            RedirectConfig viaBuilder = redirectConfig();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void matchConfigBuilderMatchesConstructorForAnExactPath() {
            MatchConfig viaCtor = new MatchConfig(null, "/orders", List.of(HttpMethod.GET), null, List.of());
            MatchConfig viaBuilder = MatchConfig.builder().path("/orders").methods(List.of(HttpMethod.GET)).build();
            assertEquals(viaCtor, viaBuilder);
        }
    }

    // --- Null-normalization ------------------------------------------------

    @Nested
    @DisplayName("Canonical constructors normalize absent components")
    class NullNormalization {

        @Test
        void gatewayConfigNormalizesAllAbsentComponents() {
            // Positional on purpose: the canonical constructor's null normalization is the subject.
            GatewayConfig cfg = new GatewayConfig(1, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            assertNull(cfg.metadata());
            assertNull(cfg.tls());
            assertNull(cfg.management());
            assertNull(cfg.securityHeaders());
            assertNull(cfg.securityDefaults());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertNull(cfg.cookies(), "an omitted gateway cookies block declares none");
            assertTrue(cfg.anchors().isEmpty());
            assertNull(cfg.upstreamDefaults());
            assertNull(cfg.assetDefaults());
            assertNull(cfg.forwarded());
            assertNull(cfg.tokenValidation());
            assertNull(cfg.oidc());
            assertNull(cfg.edgeHardening());
            assertNull(cfg.egressTls());
            assertNull(cfg.portal(), "an omitted portal block keeps the portal off");
        }

        @Test
        void portalConfigResolvesAbsentOptionalMembersToTheirDefaults() {
            PortalConfig cfg = new PortalConfig("/", "Applications", null, null, null);
            assertAll("an omitted template_dir, cache_seconds and error_pages resolve to the documented defaults",
                    () -> assertNull(cfg.templateDir()),
                    () -> assertNull(cfg.cacheSeconds()),
                    () -> assertNull(cfg.errorPages()),
                    () -> assertEquals(PortalConfig.DEFAULT_CACHE_SECONDS, cfg.effectiveCacheSeconds()),
                    () -> assertFalse(cfg.effectiveErrorPages()));
        }

        @Test
        void portalConfigReturnsDeclaredOptionalMembers() {
            PortalConfig cfg = portalConfig();
            assertEquals(60, cfg.effectiveCacheSeconds());
            assertTrue(cfg.effectiveErrorPages());
            assertFalse(new PortalConfig("/", "Applications", null, 0, false).effectiveErrorPages(),
                    "a declared error_pages: false stays false");
        }

        @Test
        @DisplayName("security_defaults.uniform_not_found resolves an omitted key and an omitted block to on")
        void uniformNotFoundDefaultsToOn() {
            assertAll("the trust-boundary branch is the secure default",
                    () -> assertNull(SecurityDefaultsConfig.builder().build().uniformNotFound(),
                            "an omitted key stays absent on the model"),
                    () -> assertTrue(SecurityDefaultsConfig.builder().build().effectiveUniformNotFound(),
                            "an omitted key resolves to true"),
                    () -> assertTrue(SecurityDefaultsConfig.builder().uniformNotFound(true).build()
                            .effectiveUniformNotFound(), "a declared true stays true"),
                    () -> assertFalse(SecurityDefaultsConfig.builder().uniformNotFound(false).build()
                            .effectiveUniformNotFound(), "a declared false restores the honest codes"));
        }

        @Test
        @DisplayName("the schema declares security_defaults.uniform_not_found as a boolean defaulting to true")
        void schemaDeclaresUniformNotFound() throws Exception {
            JsonNode schema;
            try (InputStream in = ConfigModelContractTest.class.getResourceAsStream("/schema/gateway.schema.json")) {
                assertNotNull(in, "the bundled gateway schema is on the classpath");
                schema = new ObjectMapper().readTree(in);
            }
            JsonNode property = schema.at("/properties/security_defaults/properties/uniform_not_found");

            assertAll("the model key and the schema key agree",
                    () -> assertFalse(property.isMissingNode(), "the schema declares the key"),
                    () -> assertEquals("boolean", property.path("type").asText()),
                    () -> assertTrue(property.path("default").asBoolean(false), "the documented default is true"),
                    () -> assertFalse(schema.at("/properties/security_defaults/additionalProperties").asBoolean(true),
                            "the block still refuses an unknown key"));
        }

        @Test
        void catalogConfigKeepsAbsentOptionalMembersAbsent() {
            CatalogConfig cfg = new CatalogConfig("Orders", null, "/orders/", null);
            assertNull(cfg.description());
            assertNull(cfg.order());
        }

        @Test
        void securityHeadersConfigKeepsAnAbsentContentSecurityPolicyAbsent() {
            // Positional on purpose: the canonical constructor's null round-trip is the subject.
            SecurityHeadersConfig cfg = new SecurityHeadersConfig(null, null, null, null, null, null);
            assertAll("every security_headers component round-trips null — an absent policy emits no header",
                    () -> assertNull(cfg.hsts()),
                    () -> assertNull(cfg.contentTypeNosniff()),
                    () -> assertNull(cfg.frameDeny()),
                    () -> assertNull(cfg.contentSecurityPolicy()),
                    () -> assertNull(cfg.headerModes()),
                    () -> assertNull(cfg.cors()));
        }

        @Test
        void securityHeadersConfigResolvesEveryAbsentModeToSet() {
            SecurityHeadersConfig noBlock = SecurityHeadersConfig.builder().build();
            SecurityHeadersConfig partialBlock = SecurityHeadersConfig.builder().headerModes(frameDenyDefault()).build();
            assertAll("an absent header_modes block, and an absent key inside a declared one, both mean set",
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, noBlock.hstsMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, noBlock.contentTypeNosniffMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, noBlock.frameDenyMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, noBlock.contentSecurityPolicyMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.DEFAULT, partialBlock.frameDenyMode(),
                            "a declared key resolves to its declared mode"),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, partialBlock.hstsMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET, partialBlock.contentTypeNosniffMode()),
                    () -> assertEquals(SecurityHeadersConfig.HeaderMode.SET,
                            partialBlock.contentSecurityPolicyMode()));
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
            // Positional on purpose: the canonical constructor's null normalization is the subject.
            EndpointConfig cfg = new EndpointConfig("id", true, "url", null, null, null, null, null, null, null, null,
                    null, null);
            assertNull(cfg.anchor());
            assertNull(cfg.auth());
            assertNull(cfg.securityFilter(), "an endpoint without a security_filter block declares none");
            assertNull(cfg.forward(), "an endpoint without a forward block declares none");
            assertNull(cfg.cookies(), "an endpoint without a cookies block declares none");
            assertNull(cfg.catalog(), "an endpoint without a catalog block is not listed on the portal");
            assertTrue(cfg.scopes().isEmpty(), "an absent endpoint scopes list normalizes to empty");
            assertTrue(cfg.allowedMethods().isEmpty());
            assertNull(cfg.upstreamDefaults());
            assertTrue(cfg.routes().isEmpty());
        }

        @Test
        void routeConfigNormalizesAbsentAnchor() {
            // Positional on purpose: the canonical constructor's null round-trip is the subject.
            RouteConfig cfg = new RouteConfig("id", null, null, matchConfig(), null, null, null, null, null, null,
                    null, null, null, null);
            assertNull(cfg.anchor());
            assertNull(cfg.auth());
            assertNull(cfg.securityFilter());
            assertNull(cfg.cookies(), "a route without a cookies block declares none");
            assertNull(cfg.asset());
            assertNull(cfg.redirect());
            assertNull(cfg.websocket());
            assertNull(cfg.keepIdentityHeaders(),
                    "an absent keep_identity_headers stays absent in the config model, not normalized to false");
        }

        @Test
        void anchorConfigNormalizesAbsentComponents() {
            // Positional on purpose: the canonical constructor's null normalization is the subject.
            AnchorConfig cfg = new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null, null,
                    null, null, null);
            assertNull(cfg.auth());
            assertNull(cfg.securityFilter());
            assertNull(cfg.securityHeaders());
            assertTrue(cfg.allowedMethods().isEmpty());
            assertNull(cfg.cookies(), "an anchor without a cookies block declares none");
        }

        @Test
        void collectionBearingRecordsNormalizeNullToEmpty() {
            // Positional on purpose: every canonical constructor's null-to-empty normalization is the subject.
            assertTrue(new TokenValidationConfig(null).issuers().isEmpty());
            assertTrue(new ForwardedConfig(null, null, null).trustedProxies().isEmpty());
            assertTrue(new ForwardConfig(null, null, null, null, null).setHeaders().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).cipherSuites().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).alpn().isEmpty());
            assertTrue(new TlsConfig(null, null, null, null, null).passthroughSni().isEmpty());
            assertTrue(new MatchConfig("/p", null, null, null, null).methods().isEmpty());
            assertTrue(new MatchConfig("/p", null, null, null, null).headers().isEmpty());
            assertTrue(new SecurityFilterConfig(null, null, null, null, null, null, null, null, null, null)
                    .allowedPaths().isEmpty());
            assertTrue(new OidcConfig.Csrf(null).trustedOrigins().isEmpty());
            assertTrue(new AnchorConfig("api", "/api", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null, null, null,
                    null, null).allowedMethods().isEmpty());
            assertTrue(new WebSocketConfig(null, null).allowedOrigins().isEmpty());
        }

        @Test
        void webSocketConfigNormalizesAbsentComponents() {
            // Positional on purpose: the canonical constructor's null normalization is the subject.
            WebSocketConfig cfg = new WebSocketConfig(null, null);
            assertTrue(cfg.allowedOrigins().isEmpty());
            assertNull(cfg.idleTimeoutSeconds());
        }

        /**
         * ForwardConfig is the deliberate exception to this package's absent-collection-means-empty
         * rule: its four list components stay {@code null} when absent, because absent and declared
         * empty select different forward modes (forward-all versus a list that names nothing).
         * Normalizing here would erase the distinction the three-mode model is built on.
         */
        @Test
        void forwardConfigKeepsAbsentListsNullRatherThanNormalizingThem() {
            // Positional on purpose: the canonical constructor's refusal to normalize null is the subject.
            ForwardConfig absent = new ForwardConfig(null, null, null, null, null);

            assertAll("every absent forward list stays null",
                    () -> assertNull(absent.headersAllow(), "an absent headers_allow is not an empty allowlist"),
                    () -> assertNull(absent.headersDeny(), "an absent headers_deny is not an empty denylist"),
                    () -> assertNull(absent.queryAllow(), "an absent query_allow is not an empty allowlist"),
                    () -> assertNull(absent.queryDeny(), "an absent query_deny is not an empty denylist"));
        }

        @Test
        void forwardConfigDistinguishesAbsentFromDeclaredEmpty() {
            // Positional on purpose: the canonical constructor keeping absent and empty apart is the subject.
            ForwardConfig absent = new ForwardConfig(null, null, null, null, Map.of());
            ForwardConfig declaredEmpty = new ForwardConfig(List.of(), null, List.of(), null, Map.of());

            assertAll("absent and declared-empty are different postures, so they are different values",
                    () -> assertNull(absent.headersAllow()),
                    () -> assertNotNull(declaredEmpty.headersAllow(),
                            "a declared empty list is present and empty, never null"),
                    () -> assertTrue(declaredEmpty.headersAllow().isEmpty()),
                    () -> assertTrue(declaredEmpty.queryAllow().isEmpty()),
                    () -> assertNotEquals(absent, declaredEmpty,
                            "forward-all and a positive-list naming nothing must not compare equal — the"
                                    + " whole three-mode model depends on the two being distinguishable"));
        }

        @Test
        void forwardConfigDefensivelyCopiesDeclaredListsAndKeepsThemUnmodifiable() {
            List<String> source = new ArrayList<>(List.of("Accept"));
            // Positional on purpose: the canonical constructor's defensive copy is the subject.
            ForwardConfig cfg = new ForwardConfig(source, null, null, null, Map.of());
            source.add("X-Late");

            assertAll("a declared list is copied at construction and is unmodifiable afterwards",
                    () -> assertEquals(List.of("Accept"), cfg.headersAllow(),
                            "mutating the source after construction must not reach the record"),
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> cfg.headersAllow().add("X-Other")));
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
        void endpointConfigRequiresId() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> endpointConfigWith(null, "url"));
            assertEquals("id", ex.getMessage());
        }

        @Test
        void endpointConfigAcceptsAnAbsentBaseUrl() {
            EndpointConfig cfg = endpointConfigWith("id", null);
            assertNull(cfg.baseUrl(),
                    "base_url is conditionally mandatory; the record admits its absence and the validator"
                            + " enforces it for endpoints carrying a proxy route");
        }

        /**
         * Builds an otherwise-valid {@link EndpointConfig} so a mandatory-field test can vary
         * exactly one component, keeping each {@code assertThrows} lambda to a single invocation.
         */
        private EndpointConfig endpointConfigWith(String id, String baseUrl) {
            // Positional on purpose: the canonical constructor's mandatory-field check is the subject.
            return new EndpointConfig(id, true, baseUrl, null, auth(), List.of(), List.of(), null, null, null,
                    null, List.of(), null);
        }

        @Test
        void endpointConfigAcceptsAnAbsentAuthBlock() {
            // Positional on purpose: the canonical constructor admitting an absent auth is the subject.
            EndpointConfig cfg = new EndpointConfig("id", true, "url", "api", null, List.of(), List.of(), null, null,
                    null, null, List.of(), null);
            assertNull(cfg.auth(), "an anchored endpoint may omit its auth block");
        }

        @Test
        void portalConfigRequiresPathAndTitle() {
            NullPointerException noPath = assertThrows(NullPointerException.class, () -> portalWith(null, "Apps"));
            assertEquals("path", noPath.getMessage());
            NullPointerException noTitle = assertThrows(NullPointerException.class, () -> portalWith("/", null));
            assertEquals("title", noTitle.getMessage());
        }

        @Test
        void catalogConfigRequiresTitleAndEntry() {
            NullPointerException noTitle = assertThrows(NullPointerException.class,
                    () -> catalogWith(null, "/orders/"));
            assertEquals("title", noTitle.getMessage());
            NullPointerException noEntry = assertThrows(NullPointerException.class,
                    () -> catalogWith("Orders", null));
            assertEquals("entry", noEntry.getMessage());
        }

        private PortalConfig portalWith(String path, String title) {
            return new PortalConfig(path, title, null, null, null);
        }

        private CatalogConfig catalogWith(String title, String entry) {
            return new CatalogConfig(title, null, entry, null);
        }

        @Test
        void anchorConfigRequiresNamePathPrefixTypeAndAccess() {
            // Positional on purpose: the canonical constructor's mandatory-field checks are the subject.
            NullPointerException noName = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig(null, "/api", AnchorType.PROXY, AccessLevel.PUBLIC, null,
                            null, null, List.of(), null));
            assertEquals("name", noName.getMessage());
            NullPointerException noPrefix = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", null, AnchorType.PROXY, AccessLevel.PUBLIC, null,
                            null, null, List.of(), null));
            assertEquals("pathPrefix", noPrefix.getMessage());
            NullPointerException noType = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", "/api", null, AccessLevel.PUBLIC, null,
                            null, null, List.of(), null));
            assertEquals("type", noType.getMessage());
            NullPointerException noAccess = assertThrows(NullPointerException.class,
                    () -> new AnchorConfig("api", "/api", AnchorType.PROXY, null, null,
                            null, null, List.of(), null));
            assertEquals("access", noAccess.getMessage());
        }

        @Test
        void authConfigRequiresRequire() {
            // Positional on purpose: the canonical constructor's mandatory-field check is the subject.
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new AuthConfig(null, null, null));
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
                    () -> new IssuerConfig.Jwks(null, null, null, List.of(), null, null, null, null));
        }

        @Test
        void matchConfigRequiresExactlyOneOfPathPrefixAndPath() {
            IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
                    () -> matchConfigWith(null, null));
            IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                    () -> matchConfigWith("/orders", "/orders"));

            assertAll("a matcher declares exactly one path form",
                    () -> assertEquals("exactly one of pathPrefix or path must be declared", neither.getMessage(),
                            "a matcher with no path form is rejected"),
                    () -> assertEquals("exactly one of pathPrefix or path must be declared", both.getMessage(),
                            "a matcher declaring both path forms is rejected"),
                    () -> assertEquals("/orders", matchConfigWith("/orders", null).pathPrefix()),
                    () -> assertEquals("/orders", matchConfigWith(null, "/orders").path()));
        }

        /**
         * Builds an otherwise-valid {@link MatchConfig} so a mandatory-field test can vary
         * exactly the two path components, keeping each {@code assertThrows} lambda to a single
         * invocation.
         */
        private MatchConfig matchConfigWith(String pathPrefix, String path) {
            return new MatchConfig(pathPrefix, path, List.of(), null, List.of());
        }

        @Test
        void redirectConfigRequiresLocation() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new RedirectConfig(null, 302, false, false));
            assertEquals("location", ex.getMessage());
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
            // Positional on purpose: the canonical constructor's mandatory-field check is the subject.
            return new RouteConfig(id, null, null, match, null, null, null, null, null, null, null, null, null,
                    null);
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
            assertEquals(cookiesConfig(), cfg.cookies());
        }

        @Test
        void cookiesConfigLowerCasesAdditionalOriginsAndKeepsAnAbsentListAbsent() {
            CookiesConfig declared = new CookiesConfig(CookieAllow.ALL, null, List.of("HTTPS://App.Example.COM"));
            CookiesConfig omitted = new CookiesConfig(CookieAllow.ALL, null, null);

            assertAll("additional_origins is lower-cased once and an omitted list stays distinguishable",
                    () -> assertEquals(List.of("https://app.example.com"), declared.additionalOrigins()),
                    () -> assertNull(omitted.additionalOrigins(), "an omitted list is not an empty one"),
                    () -> assertEquals(List.of(), new CookiesConfig(CookieAllow.ALL, null, List.of())
                            .additionalOrigins(), "a declared empty list stays declared"));
        }

        @Test
        void cookiesConfigResolvesTheSameOriginCheckAndWhetherAnyCookieCrosses() {
            assertAll("same_origin_check defaults to true; only none forwards nothing",
                    () -> assertTrue(new CookiesConfig(CookieAllow.ALL, null, null).effectiveSameOriginCheck()),
                    () -> assertTrue(new CookiesConfig(CookieAllow.ALL, true, null).effectiveSameOriginCheck()),
                    () -> assertFalse(new CookiesConfig(CookieAllow.ALL, false, null).effectiveSameOriginCheck()),
                    () -> assertFalse(CookiesConfig.NONE.forwardsAny()),
                    () -> assertTrue(new CookiesConfig(CookieAllow.ALL, null, null).forwardsAny()),
                    () -> assertTrue(cookiesConfig().forwardsAny()),
                    () -> assertEquals(CookieAllow.NONE, CookiesConfig.NONE.allow()),
                    () -> assertNull(CookiesConfig.NONE.sameOriginCheck()),
                    () -> assertNull(CookiesConfig.NONE.additionalOrigins()));
        }

        @Test
        void cookiesConfigRequiresAllow() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new CookiesConfig(null, null, null));
            assertEquals("allow", ex.getMessage());
        }

        @Test
        void upstreamConfigRewriteLocationRoundTripsAndDefaultsToAbsent() {
            assertAll("rewrite_location is a nullable toggle whose absence means off",
                    () -> assertEquals(Boolean.TRUE, upstreamConfig().rewriteLocation()),
                    () -> assertNull(UpstreamConfig.builder().build().rewriteLocation(),
                            "the record applies no default; absent stays null and resolves to off at assembly"));
        }

        @Test
        void gatewayConfigExposesAnchors() {
            GatewayConfig cfg = GatewayConfig.builder().version(1).anchors(Map.of("api", anchorConfig())).build();
            assertEquals(Map.of("api", anchorConfig()), cfg.anchors());
        }
    }

    // --- Exact routes, redirect action and optional base_url (AS-3/AS-4) ----

    @Nested
    @DisplayName("Exact routes, redirect terminal action and the proxy-route predicate (AS-3/AS-4)")
    class ExactRoutesAndRedirect {

        @Test
        void prefixMatcherExposesItsPrefixAsMatchKey() {
            MatchConfig prefix = MatchConfig.builder().pathPrefix("/orders").build();

            assertAll("a prefix matcher",
                    () -> assertEquals("/orders", prefix.matchKey()),
                    () -> assertFalse(prefix.isExact()),
                    () -> assertNull(prefix.path()));
        }

        @Test
        void exactMatcherExposesItsPathAsMatchKey() {
            MatchConfig exact = MatchConfig.builder().path("/orders/").build();

            assertAll("an exact matcher",
                    () -> assertEquals("/orders/", exact.matchKey(),
                            "the exact path is carried un-normalized, trailing slash included"),
                    () -> assertTrue(exact.isExact()),
                    () -> assertNull(exact.pathPrefix()));
        }

        @Test
        void routeWithoutAssetOrRedirectIsAProxyRoute() {
            RouteConfig route = RouteConfig.builder().id("proxy").match(matchConfig()).upstream(upstreamConfig())
                    .build();

            assertTrue(route.isProxyRoute(), "a route declaring neither asset nor redirect forwards upstream");
        }

        @Test
        void routeWithoutAnyTerminalBlockIsAProxyRoute() {
            RouteConfig route = RouteConfig.builder().id("proxy").match(matchConfig()).build();

            assertTrue(route.isProxyRoute(), "an absent upstream block still proxies to the endpoint base_url");
        }

        @Test
        void assetRouteIsNotAProxyRoute() {
            RouteConfig route = RouteConfig.builder().id("assets").match(matchConfig())
                    .asset(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv", null, null, null)).build();

            assertFalse(route.isProxyRoute(), "an asset route needs no base_url");
        }

        @Test
        void redirectRouteIsNotAProxyRoute() {
            RouteConfig route = RouteConfig.builder().id("moved").match(matchConfig()).redirect(redirectConfig())
                    .build();

            assertAll("a redirect route",
                    () -> assertFalse(route.isProxyRoute(), "a redirect route needs no base_url"),
                    () -> assertEquals(redirectConfig(), route.redirect()));
        }

        @Test
        void routeDeclaringAssetAndRedirectIsNotAProxyRoute() {
            RouteConfig route = RouteConfig.builder().id("both").match(matchConfig())
                    .asset(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv", null, null, null))
                    .redirect(redirectConfig()).build();

            assertFalse(route.isProxyRoute(),
                    "the predicate stays false; the conflicting terminal actions are refused by the validator");
        }

        @Test
        void redirectConfigExposesEveryComponent() {
            RedirectConfig redirect = new RedirectConfig("https://other.example.com/", 302, false, true);

            assertAll("redirect components",
                    () -> assertEquals("https://other.example.com/", redirect.location()),
                    () -> assertEquals(302, redirect.status()),
                    () -> assertFalse(redirect.keepQuery()),
                    () -> assertTrue(redirect.allowExternal()));
        }

        @Test
        void redirectConfigFlagsDefaultToFalseOnTheBuilder() {
            RedirectConfig redirect = RedirectConfig.builder().location("/home").status(307).build();

            assertAll("unset flags are the secure default",
                    () -> assertFalse(redirect.keepQuery(), "the query is not carried unless opted in"),
                    () -> assertFalse(redirect.allowExternal(), "an external target is not allowed unless opted in"));
        }
    }

    // --- The narrowing predicate the anchor-coverage rule depends on ---------

    @Nested
    @DisplayName("MatchConfig.narrowsBeyondPath and the dimension set it must cover")
    class NarrowsBeyondPath {

        /** The non-path components of {@link MatchConfig} — the dimensions the predicate must read. */
        private static final Set<String> NARROWING_COMPONENTS = Set.of("methods", "host", "headers");

        /** The path components, which the predicate deliberately ignores. */
        private static final Set<String> PATH_COMPONENTS = Set.of("pathPrefix", "path");

        @Test
        @DisplayName("A matcher constraining nothing but the path does not narrow")
        void pathOnlyMatcherDoesNotNarrow() {
            assertAll("neither matcher form narrows on its own",
                    () -> assertFalse(MatchConfig.builder().pathPrefix("/orders").build().narrowsBeyondPath()),
                    () -> assertFalse(MatchConfig.builder().path("/orders").build().narrowsBeyondPath()));
        }

        @Test
        @DisplayName("Each non-path dimension narrows on its own")
        void eachDimensionNarrows() {
            assertAll("every dimension RouteMatcher ANDs with the path",
                    () -> assertTrue(MatchConfig.builder().pathPrefix("/o")
                            .methods(List.of(HttpMethod.GET)).build().narrowsBeyondPath(), "methods"),
                    () -> assertTrue(MatchConfig.builder().pathPrefix("/o")
                            .host("api.example.org").build().narrowsBeyondPath(), "host"),
                    () -> assertTrue(MatchConfig.builder().pathPrefix("/o")
                            .headers(List.of(new MatchConfig.HeaderMatcher("X-K", Boolean.TRUE, null)))
                            .build().narrowsBeyondPath(), "headers"));
        }

        /**
         * THE DRIFT GUARD. {@code narrowsBeyondPath} enumerates the non-path dimensions by hand, and a
         * dimension added to the record but forgotten there would silently re-open the anchor-coverage
         * bypass: a route narrowed on the new dimension would again count as covering a namespace it
         * only partly serves. Deriving the set from the record itself turns that omission into a
         * failing test at the moment the component is added, which is the only moment anyone is
         * looking. If this fails because you added a component, decide whether it narrows a match:
         * extend {@code narrowsBeyondPath} and {@code NARROWING_COMPONENTS}, or add it to
         * {@code PATH_COMPONENTS} if it is part of the path matcher.
         */
        @Test
        @DisplayName("Every MatchConfig component is classified as narrowing or path")
        void everyComponentIsClassified() {
            Set<String> declared = Stream.of(MatchConfig.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toSet());
            Set<String> classified = new HashSet<>(NARROWING_COMPONENTS);
            classified.addAll(PATH_COMPONENTS);

            assertEquals(classified, declared,
                    "a MatchConfig component is neither classified as narrowing nor as part of the path "
                            + "matcher — narrowsBeyondPath cannot be trusted until it is");
        }
    }

    // --- Asset index and fallback (AS-12) ------------------------------------

    @Nested
    @DisplayName("Asset index and fallback components (AS-12)")
    class AssetIndexAndFallback {

        private static ResolvedUpstream secondary() {
            return new ResolvedUpstream("https", "cdn.internal", 443, "");
        }

        @Test
        void assetConfigCarriesIndexAndFallbackAndParticipatesInIdentity() {
            AssetConfig spa = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).directory("/srv")
                    .index("index.html").fallback("shell.html").build();

            assertAll("index and fallback are record components",
                    () -> assertEquals("index.html", spa.index()),
                    () -> assertEquals("shell.html", spa.fallback()),
                    () -> assertEquals(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv", null, "index.html",
                            "shell.html"), spa, "the builder matches the canonical constructor"),
                    () -> assertNotEquals(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv", null, null,
                            "shell.html"), spa, "the index participates in equals"),
                    () -> assertNotEquals(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv", null, "index.html",
                            null), spa, "the fallback participates in equals"),
                    () -> assertNull(AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).build().index(),
                            "an omitted index stays absent"));
        }

        @Test
        void directoryFactoryCarriesIndexAndFallback() {
            ResolvedAsset asset = ResolvedAsset.directory("/srv", AccessLevel.PUBLIC, "index.html", "shell.html");

            assertAll("the four-argument directory factory",
                    () -> assertEquals(AssetConfig.Source.DIRECTORY, asset.source()),
                    () -> assertEquals("/srv", asset.directory()),
                    () -> assertEquals("index.html", asset.index()),
                    () -> assertEquals("shell.html", asset.fallback()),
                    () -> assertNotEquals(ResolvedAsset.directory("/srv", AccessLevel.PUBLIC, "index.html", null),
                            asset, "the fallback participates in equals"),
                    () -> assertNotEquals(ResolvedAsset.directory("/srv", AccessLevel.PUBLIC, null, "shell.html"),
                            asset, "the index participates in equals"));
        }

        @Test
        void upstreamAssetRefusesIndexOrFallback() {
            ResolvedUpstream upstream = secondary();

            IllegalArgumentException withIndex = assertThrows(IllegalArgumentException.class,
                    () -> upstreamAssetWith(upstream, "index.html", null));
            assertThrows(IllegalArgumentException.class, () -> upstreamAssetWith(upstream, null, "shell.html"));

            assertAll("index and fallback belong to a directory source only",
                    () -> assertEquals(
                            "UPSTREAM asset carries no index or fallback; both apply to a DIRECTORY source only",
                            withIndex.getMessage()),
                    () -> assertNull(ResolvedAsset.upstream(upstream, AccessLevel.PUBLIC).index(),
                            "the upstream factory carries no index"),
                    () -> assertNull(ResolvedAsset.upstream(upstream, AccessLevel.PUBLIC).fallback(),
                            "the upstream factory carries no fallback"));
        }

        /**
         * Builds an UPSTREAM {@link ResolvedAsset} varying only index and fallback, keeping each
         * {@code assertThrows} lambda to a single invocation.
         */
        private static ResolvedAsset upstreamAssetWith(ResolvedUpstream upstream, String index, String fallback) {
            return new ResolvedAsset(AssetConfig.Source.UPSTREAM, AccessLevel.PUBLIC, null, upstream, index, fallback);
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
            assertEquals(UpstreamDefaultsConfig.builder().retryEnabled(true).notModifiedEnabled(true).build(), defaults);
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
            assertEquals("/session/login", new OidcConfig.Login("/session/login", null).path());
            assertNull(new OidcConfig.Login(null, null).path());
        }

        @Test
        void loginExposesDefaultReturnUrlAndKeepsAbsentAbsent() {
            assertEquals("/app?tab=a", new OidcConfig.Login(null, "/app?tab=a").defaultReturnUrl());
            assertNull(new OidcConfig.Login("/login", null).defaultReturnUrl(),
                    "an omitted default_return_url stays absent; the runtime resolves it to /");
        }

        @Test
        void loginBuilderMatchesConstructor() {
            OidcConfig.Login viaCtor = new OidcConfig.Login("/login", "/home");
            OidcConfig.Login viaBuilder = OidcConfig.Login.builder().path("/login").defaultReturnUrl("/home").build();
            assertEquals(viaCtor, viaBuilder);
        }

        @Test
        void authConfigResolvesAbsentTokenRelayToRelaying() {
            assertAll("token_relay: absent and true relay, only an explicit false withholds the token",
                    () -> assertNull(AuthConfig.builder().require(Require.SESSION).build().tokenRelay()),
                    () -> assertTrue(AuthConfig.builder().require(Require.SESSION).build().effectiveTokenRelay()),
                    () -> assertTrue(AuthConfig.builder().require(Require.SESSION).tokenRelay(true).build()
                            .effectiveTokenRelay()),
                    () -> assertFalse(AuthConfig.builder().require(Require.SESSION).tokenRelay(false).build()
                            .effectiveTokenRelay()));
        }

        @Test
        void authConfigResolvesOnlyADeclaredTrueSessionFallbackToFallingBack() {
            assertAll("session_fallback: absent and false stay on one branch, only an explicit true falls back",
                    () -> assertNull(AuthConfig.builder().require(Require.BEARER).build().sessionFallback()),
                    () -> assertFalse(AuthConfig.builder().require(Require.BEARER).build().effectiveSessionFallback(),
                            "an absent key resolves to false"),
                    () -> assertFalse(AuthConfig.builder().require(Require.BEARER).sessionFallback(false).build()
                            .effectiveSessionFallback(), "a declared false resolves to false"),
                    () -> assertTrue(AuthConfig.builder().require(Require.BEARER).sessionFallback(true).build()
                            .effectiveSessionFallback(), "a declared true resolves to true"));
        }

        @Test
        void endpointConfigCopiesScopesDefensively() {
            List<String> source = new ArrayList<>(List.of("orders.read"));
            EndpointConfig cfg = EndpointConfig.builder().id("orders").scopes(source).build();
            source.add("orders.write");
            List<String> scopes = cfg.scopes();
            assertAll(
                    () -> assertEquals(List.of("orders.read"), scopes,
                            "mutating the source list after construction must not affect the record"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> scopes.add("x")));
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
            assertTrue(rendered.contains("backChannelOrigin=" + BACK_CHANNEL_ORIGIN),
                    "an origin without user-info is an address, not a secret, so toString renders it: " + rendered);
            assertTrue(rendered.contains("backChannelAllowInsecureHttp=false"),
                    "toString must surface the cleartext opt-in: " + rendered);
            assertTrue(rendered.contains("***REDACTED***"), "the client secret must stay redacted");
            assertFalse(rendered.contains("${OIDC_SECRET}"), "the raw client-secret reference must never appear");
        }

        @Test
        void oidcToStringMasksTheUserInfoOfTheBackChannelOrigin() {
            String rendered = OidcConfig.builder().backChannelOrigin("https://operator:s3cret@keycloak:8443/")
                    .build().toString();
            assertTrue(rendered.contains("backChannelOrigin=https://****@keycloak:8443/"),
                    "the user-info of the origin must be masked, its host kept: " + rendered);
            assertFalse(rendered.contains("s3cret"), "a credential written into the origin must never appear");
            assertFalse(rendered.contains("operator"), "the user name of the origin must never appear");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "https://svc:ab/cd@keycloak:8443 | ab/cd",
                "https://admin:pa#ss@keycloak:8443 | pa#ss",
                "https://admin:pa?ss@keycloak:8443 | pa?ss",
                "admin:s3cret@keycloak:8443 | s3cret",
                "https:/admin:s3cret@keycloak | s3cret",
                "svc:pa://ss@keycloak:8443 | svc:pa"})
        void oidcToStringMasksTheUserInfoOfAMalformedBackChannelOrigin(String origin, String secret) {
            String rendered = OidcConfig.builder().backChannelOrigin(origin).build().toString();
            assertFalse(rendered.contains(secret),
                    "a credential in a malformed origin must never appear, found it in: " + rendered);
            assertTrue(rendered.contains("****@keycloak"), "the masked origin must keep its host: " + rendered);
        }

        @Test
        void sessionToStringCarriesMaxSessionsAndRendersTheKeyFileAsIs() {
            String rendered = oidcConfig().session().toString();
            assertAll(
                    () -> assertTrue(rendered.contains("maxSessions="),
                            "Session toString must surface the max_sessions bound"),
                    () -> assertTrue(rendered.contains("keyFile=" + SESSION_KEY_FILE),
                            "the sealing key file is a location, not a secret: it is rendered as it is"),
                    () -> assertFalse(rendered.contains("***REDACTED***"),
                            "the session block holds no secret any more, so nothing in it is redacted"));
        }
    }

    // --- Client-authentication selection ------------------------------------

    @Nested
    @DisplayName("Client authentication — the client_authentication block and the mode predicate")
    class ClientAuthenticationSelection {

        private static final String KEY_FILE = "/etc/sheriff/keys/client-auth.pem";
        private static final String JWKS_PATH = "/auth/client-keys";
        private static final String SECRET = "resolved-client-secret-value";
        /**
         * Deliberately the literal and not {@code ClientAuthenticationSettings.DEFAULT_JWKS_PATH}: the
         * default is a documented contract of the configuration surface, so a change of the constant
         * must turn these tests red rather than follow it.
         */
        private static final String DEFAULT_JWKS_PATH = "/bff/jwks";

        private static OidcConfig.ClientAuthenticationSettings keyFileOnly() {
            return OidcConfig.ClientAuthenticationSettings.builder().keyFile(KEY_FILE).build();
        }

        @Test
        void clientAuthenticationSettingsBuilderMatchesConstructor() {
            OidcConfig.ClientAuthenticationSettings viaCtor =
                    new OidcConfig.ClientAuthenticationSettings(KEY_FILE, JWKS_PATH);
            OidcConfig.ClientAuthenticationSettings viaBuilder =
                    OidcConfig.ClientAuthenticationSettings.builder().keyFile(KEY_FILE).jwksPath(JWKS_PATH).build();

            assertAll("the builder and the canonical constructor agree",
                    () -> assertEquals(viaCtor, viaBuilder),
                    () -> assertEquals(KEY_FILE, viaBuilder.keyFile()),
                    () -> assertEquals(JWKS_PATH, viaBuilder.jwksPath()));
        }

        @Test
        void clientAuthenticationSettingsKeepsAbsentComponentsAbsent() {
            OidcConfig.ClientAuthenticationSettings empty = OidcConfig.ClientAuthenticationSettings.builder().build();

            assertAll("the record applies no default of its own",
                    () -> assertNull(empty.keyFile(),
                            "an omitted key_file stays absent; the runtime then generates a key on startup"),
                    () -> assertNull(empty.jwksPath(),
                            "an omitted jwks_path stays absent; the default is resolved by the one accessor"));
        }

        @Test
        void defaultJwksPathIsTheDocumentedDefault() {
            assertEquals(DEFAULT_JWKS_PATH, OidcConfig.ClientAuthenticationSettings.DEFAULT_JWKS_PATH);
        }

        @Test
        void effectiveClientJwksPathResolvesTheDefaultForAnAbsentBlockAndAnAbsentKey() {
            assertAll("both absences resolve to the one default",
                    () -> assertEquals(DEFAULT_JWKS_PATH, OidcConfig.builder().build().effectiveClientJwksPath(),
                            "no client_authentication block"),
                    () -> assertEquals(DEFAULT_JWKS_PATH,
                            OidcConfig.builder().clientAuthentication(keyFileOnly()).build().effectiveClientJwksPath(),
                            "a block that declares a key file and no jwks_path"));
        }

        @Test
        void effectiveClientJwksPathReturnsADeclaredPath() {
            OidcConfig oidc = OidcConfig.builder().clientAuthentication(
                    OidcConfig.ClientAuthenticationSettings.builder().jwksPath(JWKS_PATH).build()).build();

            assertEquals(JWKS_PATH, oidc.effectiveClientJwksPath(), "a declared jwks_path is returned as declared");
        }

        @Test
        void effectiveClientJwksPathDoesNotReadTheClientAuthenticationMode() {
            assertAll("the path is resolved the same with a client secret configured",
                    () -> assertEquals(DEFAULT_JWKS_PATH,
                            OidcConfig.builder().clientSecret(SECRET).build().effectiveClientJwksPath()),
                    () -> assertEquals(JWKS_PATH, OidcConfig.builder().clientSecret(SECRET).clientAuthentication(
                            OidcConfig.ClientAuthenticationSettings.builder().jwksPath(JWKS_PATH).build())
                            .build().effectiveClientJwksPath()));
        }

        @Test
        void oidcConfigKeepsAnAbsentClientAuthenticationBlockAbsent() {
            assertNull(OidcConfig.builder().build().clientAuthentication(),
                    "an omitted client_authentication block binds as absent");
        }

        /**
         * The two neighbouring components are rendered by opposite rules, and the rendering must keep
         * them apart: the key file is a location an operator needs to see in a diagnostic, the secret is
         * a credential that must never reach one.
         */
        @Test
        void oidcToStringRendersTheKeyFilePathAndStillRedactsTheSecret() {
            String rendered = OidcConfig.builder()
                    .clientSecret(SECRET)
                    .clientAuthentication(keyFileOnly())
                    .build().toString();

            assertAll("a path is rendered, a secret is not",
                    () -> assertTrue(rendered.contains("clientAuthentication="),
                            "toString must surface the client_authentication block: " + rendered),
                    () -> assertTrue(rendered.contains(KEY_FILE), "the key-file path is a location and is rendered"),
                    () -> assertTrue(rendered.contains("***REDACTED***"), "the client secret must stay redacted"),
                    () -> assertFalse(rendered.contains(SECRET), "the client-secret value must never appear"));
        }

        @Test
        void usesClientSecretIsFalseWhenNoSecretIsDeclared() {
            assertAll("an absent secret selects private_key_jwt, with or without a key file",
                    () -> assertFalse(OidcConfig.builder().build().usesClientSecret()),
                    () -> assertFalse(OidcConfig.builder()
                            .clientAuthentication(keyFileOnly())
                            .build().usesClientSecret()));
        }

        @Test
        void usesClientSecretIsTrueWhenASecretIsDeclared() {
            assertTrue(OidcConfig.builder().clientSecret(SECRET).build().usesClientSecret(),
                    "a declared secret selects client_secret_basic");
        }

        /**
         * The mode is selected by presence alone. A blank secret must NOT read as "no secret" here:
         * that would let a variable set to the empty string fall back to the key-based mode silently,
         * where boot validation is meant to refuse it.
         */
        @ParameterizedTest(name = "a declared secret of ''{0}'' still selects client-secret mode")
        @ValueSource(strings = {"", " ", "\t"})
        void usesClientSecretIsTrueForADeclaredButBlankSecret(String blank) {
            assertTrue(OidcConfig.builder().clientSecret(blank).build().usesClientSecret(),
                    "presence selects the mode whatever the value; the blank value is refused by validation");
        }
    }

    // --- Sender constraint ---------------------------------------------------

    @Nested
    @DisplayName("Sender constraint — the sender_constraint block")
    class SenderConstraintBlock {

        private static final String KEY_FILE = "/etc/sheriff/keys/dpop.pem";
        private static final String SECRET = "resolved-client-secret-value";

        @Test
        void senderConstraintSettingsExposesTheKeyFileAndKeepsAnAbsentOneAbsent() {
            assertAll("the record applies no default of its own",
                    () -> assertEquals(KEY_FILE, new OidcConfig.SenderConstraintSettings(KEY_FILE).keyFile()),
                    () -> assertNull(new OidcConfig.SenderConstraintSettings(null).keyFile(),
                            "an omitted key_file stays absent; the runtime then generates a key on startup"));
        }

        @Test
        void oidcConfigKeepsAnAbsentSenderConstraintBlockAbsent() {
            assertNull(OidcConfig.builder().build().senderConstraint(),
                    "an omitted sender_constraint block binds as absent");
        }

        @Test
        void oidcConfigCarriesTheSenderConstraintBlockInBothClientAuthenticationModes() {
            OidcConfig.SenderConstraintSettings settings = new OidcConfig.SenderConstraintSettings(KEY_FILE);
            OidcConfig keyMode = OidcConfig.builder().senderConstraint(settings).build();
            OidcConfig secretMode = OidcConfig.builder().clientSecret(SECRET).senderConstraint(settings).build();

            assertAll("the block does not depend on the client-authentication mode, and does not select it",
                    () -> assertEquals(settings, keyMode.senderConstraint()),
                    () -> assertEquals(settings, secretMode.senderConstraint()),
                    () -> assertFalse(keyMode.usesClientSecret(),
                            "a sender-constraint key file is not a client secret"),
                    () -> assertTrue(secretMode.usesClientSecret()));
        }

        /**
         * The sender-constraint key file is a location, exactly as the client-authentication one is: an
         * operator needs to see it in a diagnostic, while the neighbouring secret must never reach one.
         */
        @Test
        void oidcToStringRendersTheSenderConstraintKeyFileAndStillRedactsTheSecret() {
            String rendered = OidcConfig.builder()
                    .clientSecret(SECRET)
                    .senderConstraint(new OidcConfig.SenderConstraintSettings(KEY_FILE))
                    .build().toString();

            assertAll("a path is rendered, a secret is not",
                    () -> assertTrue(rendered.contains("senderConstraint="),
                            "toString must surface the sender_constraint block: " + rendered),
                    () -> assertTrue(rendered.contains(KEY_FILE), "the key-file path is a location and is rendered"),
                    () -> assertTrue(rendered.contains("***REDACTED***"), "the client secret must stay redacted"),
                    () -> assertFalse(rendered.contains(SECRET), "the client-secret value must never appear"));
        }
    }

    /**
     * {@code oidc.session.idle_timeout_seconds}: the record keeps the declared value as declared and an
     * omitted one absent, and {@code effectiveIdleTimeoutSeconds()} is the one resolution boot validation
     * and the runtime share. The numbers are literals on purpose — each is the documented boundary.
     */
    @Nested
    @DisplayName("OidcConfig.Session idle timeout")
    class SessionIdleTimeout {

        private static OidcConfig.Session session(@Nullable Integer ttlSeconds, @Nullable Integer idleTimeoutSeconds) {
            return OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_SERVER).ttlSeconds(ttlSeconds)
                    .idleTimeoutSeconds(idleTimeoutSeconds).build();
        }

        @Test
        @DisplayName("keeps a declared idle timeout as declared and an omitted one absent")
        void keepsTheDeclaredValueAndAnAbsentOneAbsent() {
            assertAll("the record applies no default of its own",
                    () -> assertEquals(120, session(3600, 120).idleTimeoutSeconds()),
                    () -> assertNull(session(3600, null).idleTimeoutSeconds(),
                            "an omitted idle_timeout_seconds stays absent; the effective value is resolved"));
        }

        @Test
        @DisplayName("resolves a declared idle timeout to itself")
        void resolvesADeclaredValueToItself() {
            assertAll(
                    () -> assertEquals(120, session(3600, 120).effectiveIdleTimeoutSeconds()),
                    () -> assertEquals(3600, session(3600, 3600).effectiveIdleTimeoutSeconds(),
                            "declaring it equal to ttl_seconds is how the idle timeout is switched off"));
        }

        @Test
        @DisplayName("resolves an omitted idle timeout to the smaller of 1800 and the effective ttl_seconds")
        void resolvesAnOmittedValue() {
            assertAll("the default never exceeds the lifetime it is measured inside",
                    () -> assertEquals(1800, OidcConfig.Session.DEFAULT_IDLE_TIMEOUT_SECONDS),
                    () -> assertEquals(1800, session(7200, null).effectiveIdleTimeoutSeconds(),
                            "a ttl above the default leaves the default"),
                    () -> assertEquals(1800, session(1800, null).effectiveIdleTimeoutSeconds()),
                    () -> assertEquals(600, session(600, null).effectiveIdleTimeoutSeconds(),
                            "a ttl below the default is the idle timeout too, so such a deployment keeps booting"),
                    () -> assertEquals(1800, session(null, null).effectiveIdleTimeoutSeconds(),
                            "an omitted ttl_seconds is compared as its default of 3600"));
        }

        @Test
        @DisplayName("renders the idle timeout and the key file in toString")
        void rendersTheIdleTimeoutInToString() {
            String rendered = OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_COOKIE)
                    .keyFile(SESSION_KEY_FILE).idleTimeoutSeconds(120).build().toString();

            assertAll(
                    () -> assertTrue(rendered.contains("idleTimeoutSeconds=120"), rendered),
                    () -> assertTrue(rendered.contains("keyFile=" + SESSION_KEY_FILE),
                            "the key file is a location and is rendered as it is: " + rendered));
        }
    }
}
