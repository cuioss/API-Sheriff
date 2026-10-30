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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.LogRecord;

import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.RateLimitConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the inbound-limit rules the {@link ConfigValidator} runs through {@link LimitRules}: the
 * {@code edge_hardening} admission budget, the {@code enumeration_detection} bounds, the
 * {@code threat_classification} bounds and inert-weight refusals, the
 * {@code security_defaults.max_authorization_header_value_length}
 * carve-out bound, and the fail-closed ADR-0024 {@code profile: minimal} refusal on effectively-authenticated
 * and BFF routes — plus the fail-closed default of the GET-body opt-in that shares the
 * {@code security_defaults} block, and the boot WARN for every route declaring the unenforced
 * {@code rate_limit} block.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("ConfigValidator — inbound limits")
class LimitRulesTest {

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

    /**
     * A gateway declaring the given global {@code profile} and — when present — the
     * {@code max_authorization_header_value_length} carve-out budget the boot rule bounds.
     */
    private static GatewayConfig gatewayWithAuthorizationCap(String profile, @Nullable Integer cap) {
        return validGateway()
                .securityDefaults(SecurityDefaultsConfig.builder().profile(profile)
                        .maxAuthorizationHeaderValueLength(cap).build())
                .build();
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

    @Nested
    @DisplayName("The edge_hardening admission budget")
    class EdgeHardening {

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
    }

    @Nested
    @DisplayName("The security_defaults.max_authorization_header_value_length carve-out bound")
    class AuthorizationHeaderValueLength {

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
                    .auth(require == null ? null : auth(require))
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
                    .securityDefaults(SecurityDefaultsConfig.builder().profile(globalProfile).build())
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
                    auth(Require.NONE),
                    profiledRoute("open-secured", "/open/secured", "open", MINIMAL_PROFILE,
                            auth(Require.BEARER)));

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
                    auth(Require.NONE),
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
                    .securityDefaults(SecurityDefaultsConfig.builder().profile(MINIMAL_PROFILE).build())
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("public-api", "API", "open",
                    auth(Require.NONE),
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
                    auth(Require.NONE),
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
            SecurityDefaultsConfig securityDefaults = SecurityDefaultsConfig.builder().profile("strict").build();

            assertFalse(securityDefaults.effectiveAllowGetWithContentLengthBody(),
                    "an omitted knob must resolve fail-closed, preserving every framing rejection "
                            + "the gateway made before the key existed");
        }

        @Test
        @DisplayName("Should resolve an explicitly declared value")
        void shouldResolveDeclaredKnob() {
            assertAll(
                    () -> assertTrue(SecurityDefaultsConfig.builder().profile("strict")
                            .allowGetWithContentLengthBody(true).build()
                            .effectiveAllowGetWithContentLengthBody(), "declared true resolves true"),
                    () -> assertFalse(SecurityDefaultsConfig.builder().profile("strict")
                            .allowGetWithContentLengthBody(false).build()
                            .effectiveAllowGetWithContentLengthBody(), "declared false resolves false"));
        }

        @Test
        @DisplayName("Should accept a gateway declaring the opt-in without any validation error")
        void shouldAcceptDeclaredOptIn() {
            GatewayConfig gateway = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().profile("strict")
                            .allowGetWithContentLengthBody(true).build())
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains("security_defaults")),
                    () -> "the opt-in carries no cross-cutting boot rule, got: " + errors);
        }
    }

    /**
     * The {@code profile: minimal} refusal covers the endpoint scope {@code security_filter} is
     * declarable at: a block declared on the endpoint is refused exactly like a route-level one, and a
     * route-level block replacing it wholesale is judged on its own.
     */
    @Nested
    @DisplayName("Endpoint-scope security_filter 'minimal' refusals")
    class EndpointScopeMinimal {

        private static final String ENDPOINT_ID = "orders";
        private static final String ALIAS = "ORDERS";
        private static final String MINIMAL_REFUSAL = "resolves inbound-filter profile 'minimal'";

        private static SecurityFilterConfig profile(String profile) {
            return SecurityFilterConfig.builder().profile(profile).build();
        }

        private static EndpointConfig.EndpointConfigBuilder endpointWith(AuthConfig auth, RouteConfig... routes) {
            return EndpointConfig.builder().id(ENDPOINT_ID).enabled(true).baseUrl(ALIAS).auth(auth)
                    .routes(List.of(routes));
        }

        private List<ConfigError> validate(GatewayConfig gateway, EndpointConfig endpoint) {
            return validator.validate(gateway, List.of(endpoint), topologyWith(ALIAS));
        }

        private static boolean anyMessageContains(List<ConfigError> errors, String fragment) {
            return errors.stream().anyMatch(error -> error.message().contains(fragment));
        }

        @Test
        @DisplayName("Should refuse 'minimal' arriving through an endpoint-level security_filter on an authenticated route")
        void shouldRefuseEndpointMinimalOnAuthenticatedRoute() {
            EndpointConfig endpoint = endpointWith(auth(Require.BEARER), route("r", HttpMethod.GET))
                    .securityFilter(profile("minimal")).build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            assertHasError(errors, "/endpoint/routes", "route 'r' " + MINIMAL_REFUSAL);
        }

        @Test
        @DisplayName("Should refuse 'minimal' arriving through an endpoint-level security_filter on a BFF route")
        void shouldRefuseEndpointMinimalOnBffRoute() {
            AnchorConfig bff = AnchorConfig.builder().name("app").pathPrefix("/r").type(AnchorType.BFF)
                    .access(AccessLevel.AUTHENTICATED).auth(auth(Require.SESSION)).build();
            GatewayConfig gateway = validGateway().anchors(Map.of(bff.name(), bff)).build();
            EndpointConfig endpoint = endpointWith(null, route("r", HttpMethod.GET)).anchor(bff.name())
                    .securityFilter(profile("minimal")).build();

            List<ConfigError> errors = validate(gateway, endpoint);

            assertHasError(errors, "/endpoint/routes", "its anchor 'app' is type 'bff'");
        }

        @Test
        @DisplayName("Should not refuse 'minimal' at the endpoint when the route restores a non-minimal profile")
        void shouldAcceptRouteRestoringNonMinimalProfile() {
            RouteConfig restoring = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .securityFilter(profile("strict")).build();
            EndpointConfig endpoint = endpointWith(auth(Require.BEARER), restoring)
                    .securityFilter(profile("minimal")).build();

            List<ConfigError> errors = validate(validGateway().build(), endpoint);

            assertFalse(anyMessageContains(errors, MINIMAL_REFUSAL),
                    () -> "the route-level block replaces the endpoint 'minimal' wholesale, got: " + errors);
        }
    }

    /**
     * The per-route {@code rate_limit} block is parsed but never enforced, so every route declaring it
     * is announced at boot with {@link ConfigLogMessages.WARN#RATE_LIMIT_NOT_ENFORCED} — a WARN, never
     * a refusal. Each warning case is paired with a control in which the block is absent and no such
     * WARN is emitted.
     */
    @Nested
    @DisplayName("The unenforced per-route rate_limit block")
    class RateLimitNotEnforced {

        private static final String ENDPOINT_ID = "orders";
        private static final String ALIAS = "ORDERS";
        private static final String LIMITED_ROUTE = "orders-throttled";
        private static final String PLAIN_ROUTE = "orders-plain";

        private static RouteConfig limitedRoute(String id, RateLimitConfig rateLimit) {
            return RouteConfig.builder().id(id).match(match("/" + id, HttpMethod.GET)).rateLimit(rateLimit)
                    .build();
        }

        private static String identifier() {
            return ConfigLogMessages.WARN.RATE_LIMIT_NOT_ENFORCED.resolveIdentifierString();
        }

        private static List<String> rateLimitWarnings() {
            return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN, identifier())
                    .stream()
                    .map(LogRecord::getMessage)
                    .toList();
        }

        private List<ConfigError> validate(EndpointConfig endpoint) {
            return validator.validate(validGateway().build(), List.of(endpoint), topologyWith(ALIAS));
        }

        @Test
        @DisplayName("Should warn for a route declaring rate_limit, naming the route and its endpoint, and boot on")
        void shouldWarnForDeclaredRateLimit() {
            RateLimitConfig rateLimit = new RateLimitConfig(Generators.integers(1, 10_000).next(),
                    Generators.integers(1, 10_000).next());
            EndpointConfig endpoint = endpoint(ENDPOINT_ID, ALIAS, List.of(), limitedRoute(LIMITED_ROUTE, rateLimit));

            List<ConfigError> errors = validate(endpoint);

            assertTrue(errors.isEmpty(), () -> "the unenforced rate_limit refuses nothing, but got: " + errors);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, identifier());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    "Route '" + LIMITED_ROUTE + "' of endpoint '" + ENDPOINT_ID + "' declares rate_limit");
        }

        @Test
        @DisplayName("Should warn for an empty rate_limit block, which enforces as little as a populated one")
        void shouldWarnForEmptyRateLimitBlock() {
            EndpointConfig endpoint = endpoint(ENDPOINT_ID, ALIAS, List.of(),
                    limitedRoute(LIMITED_ROUTE, new RateLimitConfig(null, null)));

            List<ConfigError> errors = validate(endpoint);

            assertTrue(errors.isEmpty(), () -> "an empty rate_limit refuses nothing, but got: " + errors);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, identifier());
        }

        @Test
        @DisplayName("Should not warn when no route declares rate_limit")
        void shouldNotWarnWithoutRateLimit() {
            EndpointConfig endpoint = endpoint(ENDPOINT_ID, ALIAS, List.of(), route(PLAIN_ROUTE, HttpMethod.GET));

            List<ConfigError> errors = validate(endpoint);

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            assertTrue(rateLimitWarnings().isEmpty(),
                    () -> "a route without rate_limit must not emit " + identifier() + ", got: "
                            + rateLimitWarnings());
        }

        @Test
        @DisplayName("Should warn once for the declaring route only, never for its block-less sibling")
        void shouldWarnOnlyForDeclaringRoute() {
            EndpointConfig endpoint = endpoint(ENDPOINT_ID, ALIAS, List.of(),
                    route(PLAIN_ROUTE, HttpMethod.GET),
                    limitedRoute(LIMITED_ROUTE, new RateLimitConfig(Generators.integers(1, 10_000).next(), null)));

            List<ConfigError> errors = validate(endpoint);

            List<String> warnings = rateLimitWarnings();
            assertAll(
                    () -> assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors),
                    () -> assertEquals(1, warnings.size(), () -> "expected one WARN per declaring route, got: "
                            + warnings),
                    () -> assertTrue(warnings.getFirst().contains("'" + LIMITED_ROUTE + "'"),
                            "the WARN names the declaring route"),
                    () -> assertFalse(warnings.getFirst().contains("'" + PLAIN_ROUTE + "'"),
                            "the WARN never names the block-less sibling"));
        }

        @Test
        @DisplayName("Should echo no configured limit value in the WARN")
        void shouldNotEchoConfiguredLimitValues() {
            int requestsPerSecond = Generators.integers(100_000, 999_999).next();
            int burst = Generators.integers(100_000, 999_999).next();
            EndpointConfig endpoint = endpoint(ENDPOINT_ID, ALIAS, List.of(),
                    limitedRoute(LIMITED_ROUTE, new RateLimitConfig(requestsPerSecond, burst)));

            validate(endpoint);

            List<String> warnings = rateLimitWarnings();
            assertAll(
                    () -> assertEquals(1, warnings.size(), () -> "expected the WARN, got: " + warnings),
                    () -> assertFalse(warnings.getFirst().contains(String.valueOf(requestsPerSecond)),
                            "the WARN never carries requests_per_second"),
                    () -> assertFalse(warnings.getFirst().contains(String.valueOf(burst)),
                            "the WARN never carries burst"));
        }
    }

    @Nested
    @DisplayName("enumeration_detection bounds")
    class EnumerationDetectionBounds {

        private static final String POINTER = "/enumeration_detection/";

        private List<ConfigError> validate(EnumerationDetectionConfig detection) {
            return validator.validate(validGateway().enumerationDetection(detection).build(), List.of(),
                    topologyWith());
        }

        @Test
        @DisplayName("Should accept an omitted block, an empty block and every member at its documented bound")
        void shouldAcceptOmittedAndBoundaryValues() {
            List<ConfigError> omitted = validator.validate(validGateway().build(), List.of(), topologyWith());
            List<ConfigError> empty = validate(EnumerationDetectionConfig.builder().build());
            List<ConfigError> lowest = validate(new EnumerationDetectionConfig(true, 1, 1,
                    EnumerationDetectionConfig.Action.BLOCK, 1, 1));
            List<ConfigError> highestDelay = validate(EnumerationDetectionConfig.builder()
                    .action(EnumerationDetectionConfig.Action.DELAY).delayMillis(LimitRules.MAX_DELAY_MILLIS).build());

            assertAll("nothing in range is refused",
                    () -> assertTrue(omitted.isEmpty(), () -> "omitted: " + omitted),
                    () -> assertTrue(empty.isEmpty(), () -> "empty: " + empty),
                    () -> assertTrue(lowest.isEmpty(), () -> "lowest: " + lowest),
                    () -> assertTrue(highestDelay.isEmpty(), () -> "highest delay: " + highestDelay));
        }

        @ParameterizedTest(name = "threshold {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Should refuse a threshold below 1, naming its pointer and value")
        void shouldRefuseThresholdBelowOne(int threshold) {
            List<ConfigError> errors = validate(EnumerationDetectionConfig.builder().threshold(threshold).build());

            assertHasError(errors, POINTER + "threshold", "threshold must be at least 1 (was " + threshold + ")");
        }

        @ParameterizedTest(name = "window_seconds {0}")
        @ValueSource(ints = {0, -60})
        @DisplayName("Should refuse a window below 1 second")
        void shouldRefuseWindowBelowOne(int windowSeconds) {
            List<ConfigError> errors = validate(EnumerationDetectionConfig.builder().windowSeconds(windowSeconds)
                    .build());

            assertHasError(errors, POINTER + "window_seconds", "(was " + windowSeconds + ")");
        }

        @ParameterizedTest(name = "block_seconds {0}")
        @ValueSource(ints = {0, -5})
        @DisplayName("Should refuse a block duration below 1 second")
        void shouldRefuseBlockBelowOne(int blockSeconds) {
            List<ConfigError> errors = validate(EnumerationDetectionConfig.builder().blockSeconds(blockSeconds)
                    .build());

            assertHasError(errors, POINTER + "block_seconds", "(was " + blockSeconds + ")");
        }

        @ParameterizedTest(name = "delay_millis {0}")
        @ValueSource(ints = {0, -1, LimitRules.MAX_DELAY_MILLIS + 1, Integer.MAX_VALUE})
        @DisplayName("Should refuse an answer hold outside 1..10000 milliseconds")
        void shouldRefuseDelayOutOfRange(int delayMillis) {
            List<ConfigError> errors = validate(EnumerationDetectionConfig.builder().delayMillis(delayMillis)
                    .build());

            assertHasError(errors, POINTER + "delay_millis", "1..10000 (was " + delayMillis + ")");
        }

        @Test
        @DisplayName("Should report every refused member in one pass")
        void shouldReportEveryViolationInOnePass() {
            List<ConfigError> errors = validate(new EnumerationDetectionConfig(true, 0, 0,
                    EnumerationDetectionConfig.Action.OBSERVE, 0, 0));

            assertEquals(4, errors.stream().filter(e -> e.pointer().startsWith(POINTER)).count(),
                    () -> "expected one refusal per member, got: " + errors);
        }
    }

    @Nested
    @DisplayName("threat_classification bounds")
    class ThreatClassificationBounds {

        private static final String POINTER = "/threat_classification/";
        private static final String WEIGHTS_POINTER = POINTER + "weights";
        private static final String NO_ACTING_WEIGHT = "no weight above 0 can act";
        private static final String RECON_TRIP_INERT = "can never act because enumeration_detection is disabled";

        private List<ConfigError> validate(ThreatClassificationConfig classification) {
            return validate(classification, null);
        }

        private List<ConfigError> validate(ThreatClassificationConfig classification,
                @Nullable EnumerationDetectionConfig detection) {
            return validator.validate(validGateway().threatClassification(classification)
                    .enumerationDetection(detection).build(), List.of(), topologyWith());
        }

        private static ThreatClassificationConfig withWeights(ThreatClassificationConfig.Weights weights) {
            return ThreatClassificationConfig.builder().weights(weights).build();
        }

        private static ThreatClassificationConfig.Weights allZero(@Nullable Integer reconTrip) {
            return new ThreatClassificationConfig.Weights(0, 0, 0, 0, reconTrip);
        }

        private static ThreatClassificationConfig.Weights singleWeight(String member, int value) {
            ThreatClassificationConfig.Weights.WeightsBuilder builder = ThreatClassificationConfig.Weights.builder();
            switch (member) {
                case "input_validation" -> builder.inputValidation(value);
                case "routing" -> builder.routing(value);
                case "authentication" -> builder.authentication(value);
                case "authorization" -> builder.authorization(value);
                case "recon_trip" -> builder.reconTrip(value);
                default -> throw new IllegalArgumentException("unknown weight member " + member);
            }
            return builder.build();
        }

        private static EnumerationDetectionConfig reconDisabled() {
            return EnumerationDetectionConfig.builder().enabled(false).build();
        }

        @Test
        @DisplayName("Should accept an omitted block, an empty block and the shipped defaults")
        void shouldAcceptOmittedEmptyAndDefaults() {
            List<ConfigError> omitted = validator.validate(validGateway().build(), List.of(), topologyWith());
            List<ConfigError> empty = validate(ThreatClassificationConfig.builder().build());
            List<ConfigError> defaults = validate(ThreatClassificationConfig.defaults());

            assertAll("nothing in range is refused",
                    () -> assertTrue(omitted.isEmpty(), () -> "omitted: " + omitted),
                    () -> assertTrue(empty.isEmpty(), () -> "empty: " + empty),
                    () -> assertTrue(defaults.isEmpty(), () -> "defaults: " + defaults));
        }

        @Test
        @DisplayName("Should accept a threshold and window of exactly 1")
        void shouldAcceptLowestThresholdAndWindow() {
            List<ConfigError> errors = validate(ThreatClassificationConfig.builder().threshold(1).windowSeconds(1)
                    .build());

            assertTrue(errors.isEmpty(), () -> "1 is the lowest admitted value, but got: " + errors);
        }

        @ParameterizedTest(name = "threshold {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Should refuse a threshold below 1, naming its pointer and value")
        void shouldRefuseThresholdBelowOne(int threshold) {
            List<ConfigError> errors = validate(ThreatClassificationConfig.builder().threshold(threshold).build());

            assertHasError(errors, POINTER + "threshold", "threshold must be at least 1 (was " + threshold + ")");
        }

        @ParameterizedTest(name = "window_seconds {0}")
        @ValueSource(ints = {0, -300})
        @DisplayName("Should refuse a window below 1 second")
        void shouldRefuseWindowBelowOne(int windowSeconds) {
            List<ConfigError> errors = validate(ThreatClassificationConfig.builder().windowSeconds(windowSeconds)
                    .build());

            assertHasError(errors, POINTER + "window_seconds",
                    "window_seconds must be at least 1 (was " + windowSeconds + ")");
        }

        @ParameterizedTest(name = "weights.{0}")
        @ValueSource(strings = {"input_validation", "routing", "authentication", "authorization", "recon_trip"})
        @DisplayName("Should refuse a declared weight of -1, naming its pointer and value")
        void shouldRefuseNegativeWeight(String member) {
            List<ConfigError> errors = validate(withWeights(singleWeight(member, -1)));

            assertHasError(errors, WEIGHTS_POINTER + "/" + member, "weights." + member + " must be at least 0 (was -1)");
        }

        @ParameterizedTest(name = "weights.{0}")
        @ValueSource(strings = {"input_validation", "routing", "authentication", "authorization", "recon_trip"})
        @DisplayName("Should accept a declared weight of 0 while another weight can act")
        void shouldAcceptZeroWeight(String member) {
            List<ConfigError> errors = validate(withWeights(singleWeight(member, 0)));

            assertTrue(errors.isEmpty(), () -> "a zero weight beside acting defaults is legal, but got: " + errors);
        }

        @Test
        @DisplayName("Should refuse an enabled block whose every weight is 0")
        void shouldRefuseAllZeroEnabledBlock() {
            List<ConfigError> errors = validate(withWeights(allZero(0)));

            assertHasError(errors, WEIGHTS_POINTER, NO_ACTING_WEIGHT);
            assertEquals(1, errors.size(), () -> "expected exactly the all-zero refusal, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an all-zero block that is disabled")
        void shouldAcceptAllZeroDisabledBlock() {
            List<ConfigError> errors = validate(ThreatClassificationConfig.builder().enabled(false)
                    .weights(allZero(0)).build());

            assertTrue(errors.isEmpty(), () -> "a disabled block cannot alert anyway, but got: " + errors);
        }

        @Test
        @DisplayName("Should accept a block in which only recon_trip acts while recon detection runs")
        void shouldAcceptReconTripOnlyWithReconEnabled() {
            List<ConfigError> errors = validate(withWeights(allZero(30)));

            assertTrue(errors.isEmpty(), () -> "a recon trip can feed the score, but got: " + errors);
        }

        @Test
        @DisplayName("Should refuse a block in which only the defaulted recon_trip could act while recon detection is off")
        void shouldRefuseOmittedReconTripAsOnlyWeightWithReconDisabled() {
            List<ConfigError> errors = validate(withWeights(allZero(null)), reconDisabled());

            assertAll(
                    () -> assertHasError(errors, WEIGHTS_POINTER, NO_ACTING_WEIGHT),
                    () -> assertTrue(errors.stream().noneMatch(e -> e.message().contains(RECON_TRIP_INERT)),
                            () -> "an omitted recon_trip is never refused on its own, got: " + errors));
        }

        @Test
        @DisplayName("Should refuse a declared recon_trip while enumeration_detection is disabled")
        void shouldRefuseDeclaredReconTripWithReconDisabled() {
            List<ConfigError> errors = validate(withWeights(singleWeight("recon_trip", 50)), reconDisabled());

            assertHasError(errors, WEIGHTS_POINTER + "/recon_trip", "weights.recon_trip 50 " + RECON_TRIP_INERT);
            assertEquals(1, errors.size(), () -> "expected exactly the recon_trip refusal, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an omitted recon_trip while enumeration_detection is disabled")
        void shouldAcceptOmittedReconTripWithReconDisabled() {
            List<ConfigError> errors = validate(ThreatClassificationConfig.builder().build(), reconDisabled());

            assertTrue(errors.isEmpty(), () -> "the caller weights still act, but got: " + errors);
        }

        @Test
        @DisplayName("Should accept a declared recon_trip while enumeration_detection is enabled or omitted")
        void shouldAcceptDeclaredReconTripWithReconRunning() {
            ThreatClassificationConfig classification = withWeights(singleWeight("recon_trip", 50));

            List<ConfigError> omitted = validate(classification);
            List<ConfigError> enabled = validate(classification,
                    EnumerationDetectionConfig.builder().enabled(true).build());

            assertAll(
                    () -> assertTrue(omitted.isEmpty(), () -> "omitted recon block: " + omitted),
                    () -> assertTrue(enabled.isEmpty(), () -> "enabled recon block: " + enabled));
        }

        @Test
        @DisplayName("Should report every refused member in one pass")
        void shouldReportEveryViolationInOnePass() {
            List<ConfigError> errors = validate(new ThreatClassificationConfig(true, 0, 0,
                    new ThreatClassificationConfig.Weights(-1, -1, -1, -1, 5)), reconDisabled());

            assertAll(
                    () -> assertEquals(1, errors.stream().filter(e -> e.pointer().equals(POINTER + "threshold"))
                            .count(), () -> "threshold: " + errors),
                    () -> assertEquals(1, errors.stream().filter(e -> e.pointer().equals(POINTER + "window_seconds"))
                            .count(), () -> "window_seconds: " + errors),
                    () -> assertEquals(4, errors.stream().filter(e -> e.message().contains("must be at least 0"))
                            .count(), () -> "negative weights: " + errors),
                    () -> assertHasError(errors, WEIGHTS_POINTER + "/recon_trip", RECON_TRIP_INERT),
                    () -> assertHasError(errors, WEIGHTS_POINTER, NO_ACTING_WEIGHT));
        }
    }
}
