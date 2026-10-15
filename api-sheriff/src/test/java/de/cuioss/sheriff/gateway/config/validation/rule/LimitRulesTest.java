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

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the inbound-limit rules the {@link ConfigValidator} runs through {@link LimitRules}: the
 * {@code edge_hardening} admission budget, the {@code security_defaults.max_authorization_header_value_length}
 * carve-out bound, and the fail-closed ADR-0024 {@code profile: minimal} refusal on effectively-authenticated
 * and BFF routes — plus the fail-closed default of the GET-body opt-in that shares the
 * {@code security_defaults} block.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
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
}
