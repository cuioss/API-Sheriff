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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for the {@code auth.session_fallback} boot refusal the {@link ConfigValidator} runs: a declared
 * {@code session_fallback: true} is refused on any posture other than {@code require: bearer}, and
 * refused when the gateway declares no {@code oidc} block — on anchor, endpoint and route blocks alike.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. The two refusal fragments are literals on purpose: each is the
 * fixed detail text operators and the invalid-config script grep for, so the exact wording is the
 * contract under test.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — auth.session_fallback")
class ConfigValidatorSessionFallbackTest {

    private static final String NOT_BEARER = "declares auth.session_fallback: true on a posture other than require: bearer";
    private static final String NO_OIDC = "declares auth.session_fallback: true but the gateway declares no oidc block";
    private static final String SESSION_FALLBACK_KEY = "auth.session_fallback";
    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String ROUTES_POINTER = "/endpoint/routes";
    private static final String ENDPOINT_AUTH_POINTER = "/endpoint/auth";

    private final ConfigValidator validator = new ConfigValidator();

    private static String id() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static AuthConfig auth(Require require, @Nullable Boolean sessionFallback) {
        return new AuthConfig(require, null, sessionFallback);
    }

    private static OidcConfig oidc() {
        return OidcConfig.builder().redirectUri("https://gw.example.com/auth/callback").build();
    }

    private static TokenValidationConfig tokenValidation() {
        return new TokenValidationConfig(List.of(
                IssuerConfig.builder().name("main").issuer("https://idp.example").build()));
    }

    private static GatewayConfig gateway(boolean withOidc, boolean withTokenValidation, AnchorConfig... anchors) {
        GatewayConfig.GatewayConfigBuilder builder = GatewayConfig.builder().version(1);
        if (withOidc) {
            builder.oidc(oidc());
        }
        if (withTokenValidation) {
            builder.tokenValidation(tokenValidation());
        }
        if (anchors.length > 0) {
            builder.anchors(Arrays.stream(anchors).collect(Collectors.toMap(AnchorConfig::name, anchor -> anchor)));
        }
        return builder.build();
    }

    private static AnchorConfig anchor(String name, String prefix, AuthConfig auth) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(AnchorType.PROXY)
                .access(AccessLevel.AUTHENTICATED)
                .auth(auth)
                .build();
    }

    private static RouteConfig route(String routeId, String prefix, @Nullable AuthConfig auth) {
        return RouteConfig.builder()
                .id(routeId)
                .match(MatchConfig.builder().pathPrefix(prefix).build())
                .redirect(RedirectConfig.builder().location("/elsewhere").status(302).build())
                .auth(auth)
                .build();
    }

    private static EndpointConfig endpoint(String endpointId, @Nullable String anchorName, @Nullable AuthConfig auth,
            RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(endpointId)
                .enabled(true)
                .anchor(anchorName)
                .auth(auth)
                .routes(List.of(routes))
                .build();
    }

    private List<ConfigError> validate(GatewayConfig gateway, EndpointConfig... endpoints) {
        return validator.validate(gateway, List.of(endpoints), new ResolvedTopology(Map.of()));
    }

    private static List<ConfigError> sessionFallbackErrors(List<ConfigError> errors) {
        return errors.stream().filter(error -> error.message().contains(SESSION_FALLBACK_KEY)).toList();
    }

    private static void assertSingleRefusal(List<ConfigError> errors, String file, String pointer, String owner,
            String detail) {
        List<ConfigError> refusals = sessionFallbackErrors(errors);
        assertEquals(1, refusals.size(), () -> "expected exactly one session_fallback refusal, got: " + errors);
        ConfigError refusal = refusals.getFirst();
        assertAll(
                () -> assertEquals(file, refusal.file()),
                () -> assertEquals(pointer, refusal.pointer()),
                () -> assertTrue(refusal.message().startsWith(owner + " "), refusal::message),
                () -> assertTrue(refusal.message().contains(detail), refusal::message));
    }

    @Nested
    @DisplayName("route-level auth block")
    class RouteLevel {

        @ParameterizedTest(name = "refuses session_fallback on require: {0}")
        @EnumSource(value = Require.class, names = {"NONE", "SESSION"})
        void refusesNonBearerPosture(Require require) {
            String endpointId = id();
            String routeId = id();
            EndpointConfig endpoint = endpoint(endpointId, null, null, route(routeId, "/" + routeId, auth(require, true)));

            List<ConfigError> errors = validate(gateway(true, true), endpoint);

            assertSingleRefusal(errors, "endpoints/" + endpointId + ".yaml", ROUTES_POINTER,
                    "route '" + routeId + "'", NOT_BEARER);
        }

        @Test
        void refusesBearerPostureWithoutOidcBlock() {
            String endpointId = id();
            String routeId = id();
            EndpointConfig endpoint = endpoint(endpointId, null, null,
                    route(routeId, "/" + routeId, auth(Require.BEARER, true)));

            List<ConfigError> errors = validate(gateway(false, true), endpoint);

            assertSingleRefusal(errors, "endpoints/" + endpointId + ".yaml", ROUTES_POINTER,
                    "route '" + routeId + "'", NO_OIDC);
        }

        @Test
        void reportsBothRefusalsIndependently() {
            String routeId = id();
            EndpointConfig endpoint = endpoint(id(), null, null,
                    route(routeId, "/" + routeId, auth(Require.SESSION, true)));

            List<ConfigError> refusals = sessionFallbackErrors(validate(gateway(false, true), endpoint));

            assertAll(
                    () -> assertEquals(2, refusals.size(), refusals::toString),
                    () -> assertTrue(refusals.stream().anyMatch(error -> error.message().contains(NOT_BEARER)),
                            refusals::toString),
                    () -> assertTrue(refusals.stream().anyMatch(error -> error.message().contains(NO_OIDC)),
                            refusals::toString));
        }
    }

    @Nested
    @DisplayName("endpoint-level auth block")
    class EndpointLevel {

        @Test
        void refusesNonBearerPosture() {
            String endpointId = id();
            String routeId = id();
            EndpointConfig endpoint = endpoint(endpointId, null, auth(Require.SESSION, true),
                    route(routeId, "/" + routeId, null));

            List<ConfigError> errors = validate(gateway(true, true), endpoint);

            assertSingleRefusal(errors, "endpoints/" + endpointId + ".yaml", ENDPOINT_AUTH_POINTER,
                    "endpoint '" + endpointId + "'", NOT_BEARER);
        }

        @Test
        void refusesBearerPostureWithoutOidcBlock() {
            String endpointId = id();
            String routeId = id();
            EndpointConfig endpoint = endpoint(endpointId, null, auth(Require.BEARER, true),
                    route(routeId, "/" + routeId, null));

            List<ConfigError> errors = validate(gateway(false, true), endpoint);

            assertSingleRefusal(errors, "endpoints/" + endpointId + ".yaml", ENDPOINT_AUTH_POINTER,
                    "endpoint '" + endpointId + "'", NO_OIDC);
        }
    }

    @Nested
    @DisplayName("anchor-level auth block")
    class AnchorLevel {

        @Test
        void refusesNonBearerPosture() {
            String anchorName = id();
            AnchorConfig anchor = anchor(anchorName, "/" + anchorName, auth(Require.SESSION, true));

            List<ConfigError> errors = validate(gateway(true, true, anchor));

            assertSingleRefusal(errors, GATEWAY_FILE, "/anchors/" + anchorName + "/auth",
                    "anchor '" + anchorName + "'", NOT_BEARER);
        }

        @Test
        void refusesBearerPostureWithoutOidcBlock() {
            String anchorName = id();
            AnchorConfig anchor = anchor(anchorName, "/" + anchorName, auth(Require.BEARER, true));

            List<ConfigError> errors = validate(gateway(false, true, anchor));

            assertSingleRefusal(errors, GATEWAY_FILE, "/anchors/" + anchorName + "/auth",
                    "anchor '" + anchorName + "'", NO_OIDC);
        }
    }

    @Nested
    @DisplayName("accepted postures and untouched neighbouring rules")
    class Accepted {

        @Test
        void acceptsBearerPostureWithOidcAndTokenValidation() {
            String routeId = id();
            EndpointConfig endpoint = endpoint(id(), null, null,
                    route(routeId, "/" + routeId, auth(Require.BEARER, true)));

            assertEquals(List.of(), validate(gateway(true, true), endpoint));
        }

        @ParameterizedTest(name = "session_fallback: false on require: {0} triggers no refusal")
        @EnumSource(Require.class)
        void explicitFalseNeverRefused(Require require) {
            String anchorName = id();
            String routeId = id();
            AnchorConfig anchor = anchor(anchorName, "/" + anchorName, auth(require, false));
            EndpointConfig endpoint = endpoint(id(), null, auth(require, false),
                    route(routeId, "/" + routeId, auth(require, false)));

            assertEquals(List.of(), sessionFallbackErrors(validate(gateway(false, false, anchor), endpoint)));
        }

        @Test
        void bearerPostureStillRequiresTokenValidation() {
            String routeId = id();
            EndpointConfig endpoint = endpoint(id(), null, null,
                    route(routeId, "/" + routeId, auth(Require.BEARER, true)));

            List<ConfigError> errors = validate(gateway(true, false), endpoint);

            assertAll(
                    () -> assertTrue(errors.stream().anyMatch(error -> "/token_validation".equals(error.pointer())
                            && error.message().contains("effective auth 'bearer' requires token_validation")),
                            errors::toString),
                    () -> assertEquals(List.of(), sessionFallbackErrors(errors)));
        }

        @Test
        void sessionFallbackRouteUnderSessionFloorPassesTheFloorRule() {
            String anchorName = id();
            String routeId = id();
            AnchorConfig anchor = anchor(anchorName, "/" + anchorName, auth(Require.SESSION, null));
            EndpointConfig endpoint = endpoint(id(), anchorName, null,
                    route(routeId, "/" + anchorName + "/" + routeId, auth(Require.BEARER, true)));

            List<ConfigError> errors = validate(gateway(true, true, anchor), endpoint);

            assertAll(
                    () -> assertTrue(errors.stream().noneMatch(error -> error.message().contains("weakens the anchor")),
                            errors::toString),
                    () -> assertEquals(List.of(), sessionFallbackErrors(errors)));
        }

        @Test
        void reportsEveryOffendingBlockInOnePass() {
            String anchorName = id();
            String routeId = id();
            AnchorConfig anchor = anchor(anchorName, "/" + anchorName, auth(Require.NONE, true));
            EndpointConfig endpoint = endpoint(id(), null, auth(Require.SESSION, true),
                    route(routeId, "/" + routeId, auth(Require.NONE, true)));

            List<ConfigError> refusals = sessionFallbackErrors(validate(gateway(true, true, anchor), endpoint));

            assertEquals(3, refusals.size(), () -> "the rule never fails fast, got: " + refusals);
        }
    }
}
