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


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import de.cuioss.sheriff.api.config.load.ConfigError;
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
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;

/**
 * Tests for {@link ConfigValidator}: one negative case per enforced cross-cutting
 * rule, the structural {@code TRACE}/{@code CONNECT} rejection, and the
 * single-pass aggregation contract that reports every violation together rather
 * than stopping at the first.
 */
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

    private static EndpointConfig endpoint(String id, String alias, List<HttpMethod> allowedMethods,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .auth(new AuthConfig("none", List.of()))
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
                    .auth(new AuthConfig("bearer", List.of()))
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
                    .auth(new AuthConfig("session", List.of()))
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
        @DisplayName("Should reject a non whole-second upstream timeout")
        void shouldRejectNonWholeSecondTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(Optional.of(UpstreamConfig.builder().connectTimeoutMs(Optional.of(1500)).build()))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig("none", List.of()))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "connect_timeout_ms", "must be a whole-second multiple");
        }

        @Test
        @DisplayName("Should reject a non-positive upstream timeout")
        void shouldRejectNonPositiveTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(Optional.of(UpstreamConfig.builder().readTimeoutMs(Optional.of(0)).build()))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig("none", List.of()))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "read_timeout_ms", "must be a positive whole-second multiple");
        }

        @Test
        @DisplayName("Should reject a negative upstream timeout")
        void shouldRejectNegativeTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(Optional.of(UpstreamConfig.builder().connectTimeoutMs(Optional.of(-1000)).build()))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig("none", List.of()))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertHasError(errors, "connect_timeout_ms", "must be a positive whole-second multiple");
        }

        @Test
        @DisplayName("Should accept a positive whole-second upstream timeout")
        void shouldAcceptPositiveWholeSecondTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(Optional.of(UpstreamConfig.builder().readTimeoutMs(Optional.of(2000)).build()))
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(new AuthConfig("none", List.of()))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations for a positive whole-second timeout, got: "
                    + errors);
        }

        @Test
        @DisplayName("Should reject a trust-all CIDR in forwarded.trusted_proxies")
        void shouldRejectTrustAllCidr() {
            GatewayConfig gateway = validGateway()
                    .forwarded(Optional.of(ForwardedConfig.builder().trustedProxies(List.of("0.0.0.0/0")).build()))
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "trust-all CIDR is not permitted: 0.0.0.0/0");
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
                    .auth(new AuthConfig("bearer", List.of()))
                    .routes(List.of(route("r", HttpMethod.GET)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations for a valid bearer config, got: " + errors);
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
                    .auth(new AuthConfig("none", List.of()))
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
