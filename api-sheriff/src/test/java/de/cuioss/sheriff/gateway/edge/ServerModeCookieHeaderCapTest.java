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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.edge.assembly.SecurityPostures;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.BasicChecksStage;
import de.cuioss.sheriff.gateway.pipeline.CanonicalPathGuard;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.RouteSelectionStage;
import de.cuioss.sheriff.gateway.pipeline.ThoroughChecksStage;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The server-mode BFF {@code Cookie} header-value cap, {@code oidc.session.max_cookie_header_value_length},
 * driven through the production reader chain the edge wires at boot:
 * {@link SecurityPostures#cookieHeaderConfigurationFor} derives the carve-out policy from the gateway
 * document and the active BFF runtime, and {@link BasicChecksStage} — the non-skippable pre-route floor —
 * measures every inbound {@code Cookie} value against it.
 * <p>
 * <strong>Why the key exists.</strong> A server-mode BFF that fronts its identity provider (the
 * {@code /auth}-fronted compose sample) carries the provider's own cookies through the gateway. Keycloak's
 * {@code KC_RESTART} cookie pair alone measures some 1400 characters, so on a {@code strict} gateway —
 * 1024-character header values — the login flow is rejected {@code 400} at the floor before route
 * selection. The whole {@code Cookie} header the sample sends is longer still; the sample sizes its own
 * declared cap against that header, not against this one cookie. The only
 * {@code Cookie} carve-out that existed belonged to cookie mode; this key gives a server-mode BFF the same,
 * equally narrow relaxation instead of moving the whole gateway to {@code lenient}.
 * <p>
 * <strong>The key is proven by its absence as much as by its presence.</strong> Each positive case below
 * has a matched control in which the key is omitted and the same request is rejected — the CLAUDE.md
 * question "if the key were deleted, would a test go red?" is answered by the pair, not by either half.
 */
@DisplayName("Server-mode BFF Cookie header cap — oidc.session.max_cookie_header_value_length")
class ServerModeCookieHeaderCapTest {

    /**
     * The size of Keycloak's {@code KC_RESTART} cookie pair ({@code name=value}) measured on the
     * {@code /auth}-fronted sample.
     */
    private static final int KEYCLOAK_RESTART_COOKIE_LENGTH = 1403;

    /**
     * A declared cap above the Keycloak cookie with headroom, and above the strict baseline. A test value,
     * not the sample's: the sample declares a larger cap sized against its whole {@code Cookie} header.
     */
    private static final int DECLARED_CAP = 2048;

    private static final SecurityConfiguration STRICT = SecurityProfile.STRICT.preset();

    private final SecurityEventCounter counter = new SecurityEventCounter();

    @Nested
    @DisplayName("the carve-out policy SecurityPostures derives")
    class CarveOutPolicy {

        @Test
        @DisplayName("an omitted key leaves an active server-mode BFF on the resolved baseline (the secure default)")
        void omittedKeyYieldsNoCarveOut() {
            SecurityConfiguration carveOut = SecurityPostures.cookieHeaderConfigurationFor(
                    serverModeGateway(null), activeRuntime(), STRICT);

            assertNull(carveOut, "without the key a server-mode BFF measures Cookie values at the baseline, as before");
        }

        @Test
        @DisplayName("a declared key raises the Cookie cap to the declared value and changes nothing else")
        void declaredKeyRaisesTheCapAlone() {
            SecurityConfiguration carveOut = SecurityPostures.cookieHeaderConfigurationFor(
                    serverModeGateway(DECLARED_CAP), activeRuntime(), STRICT);

            assertNotNull(carveOut, "a declared key on an active server-mode BFF yields a carve-out");
            assertTrue(STRICT.maxHeaderValueLength() < DECLARED_CAP,
                    "control precondition: the strict baseline must sit below the declared cap");
            assertEquals(DECLARED_CAP, carveOut.maxHeaderValueLength());
            assertDiffersFromBaselineInCapAlone(STRICT, carveOut);
        }

        @Test
        @DisplayName("the cap never LOWERS a baseline that already admits more")
        void declaredKeyNeverLowersTheBaseline() {
            SecurityConfiguration lenient = SecurityProfile.LENIENT.preset();

            SecurityConfiguration carveOut = SecurityPostures.cookieHeaderConfigurationFor(
                    serverModeGateway(DECLARED_CAP), activeRuntime(), lenient);

            assertNotNull(carveOut);
            assertTrue(lenient.maxHeaderValueLength() > DECLARED_CAP,
                    "control precondition: the lenient baseline must sit above the declared cap");
            assertEquals(lenient, carveOut, "a carve-out may only ever admit more length");
        }

        @Test
        @DisplayName("an inert runtime ignores the key — there is no BFF for it to scope to")
        void inertRuntimeIgnoresTheKey() {
            SecurityConfiguration carveOut = SecurityPostures.cookieHeaderConfigurationFor(
                    serverModeGateway(DECLARED_CAP), BffRuntime.inert(), STRICT);

            assertNull(carveOut, "a gateway that is not an active BFF keeps the baseline on every header");
        }

        @Test
        @DisplayName("cookie mode keeps max_cookie_size as its single source and never reads the key")
        void cookieModeIgnoresTheKey() {
            GatewayConfig cookieMode = gateway(OidcConfig.Session.builder()
                    .mode(OidcConfig.Session.MODE_COOKIE)
                    .maxCookieHeaderValueLength(DECLARED_CAP)
                    .build());

            SecurityConfiguration carveOut = SecurityPostures.cookieHeaderConfigurationFor(
                    cookieMode, activeRuntime(), STRICT);

            assertNotNull(carveOut);
            assertEquals(SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET
                    + SecurityPostures.COOKIE_HEADER_OVERHEAD_BYTES, carveOut.maxHeaderValueLength(),
                    "the cookie-mode cap is derived from the sealed-cookie budget, not from this key");
        }
    }

    @Nested
    @DisplayName("the pre-route floor fed with that policy, on a strict server-mode BFF")
    class PreRouteFloor {

        @Test
        @DisplayName("key omitted: Keycloak's restart cookie is rejected 400 as too long (matched control)")
        void omittedKeyRejectsTheKeycloakCookie() {
            BasicChecksStage stage = stageFor(serverModeGateway(null));
            PipelineRequest request = requestWithHeader("Cookie", keycloakRestartCookie());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
            assertTrue(thrown.getMessage().contains("INPUT_TOO_LONG"),
                    () -> "the refusal must be the length check, got: " + thrown.getMessage());
        }

        @Test
        @DisplayName("key declared: the same Keycloak restart cookie passes the floor")
        void declaredKeyAdmitsTheKeycloakCookie() {
            BasicChecksStage stage = stageFor(serverModeGateway(DECLARED_CAP));
            PipelineRequest request = requestWithHeader("Cookie", keycloakRestartCookie());

            assertDoesNotThrow(() -> stage.process(request),
                    "a Cookie value within the declared cap must clear the pre-route floor");
        }

        @Test
        @DisplayName("key declared: a NON-Cookie header of the same length is still rejected at the strict cap")
        void declaredKeyDoesNotLeakToOtherHeaders() {
            BasicChecksStage stage = stageFor(serverModeGateway(DECLARED_CAP));
            PipelineRequest request = requestWithHeader("X-Custom", "a".repeat(KEYCLOAK_RESTART_COOKIE_LENGTH));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
            assertTrue(thrown.getMessage().contains("INPUT_TOO_LONG"),
                    () -> "every other header keeps the strict cap, got: " + thrown.getMessage());
        }

        @Test
        @DisplayName("key declared: a Cookie value beyond the declared cap is still rejected")
        void declaredKeyRaisesButDoesNotRemoveTheCap() {
            BasicChecksStage stage = stageFor(serverModeGateway(DECLARED_CAP));
            PipelineRequest request = requestWithHeader("Cookie", "a".repeat(DECLARED_CAP + 1));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        private BasicChecksStage stageFor(GatewayConfig gateway) {
            return new BasicChecksStage(STRICT, counter,
                    SecurityPostures.cookieHeaderConfigurationFor(gateway, activeRuntime(), STRICT),
                    SecurityPostures.authorizationHeaderConfigurationFor(gateway, STRICT));
        }
    }

    /**
     * Past route selection. Stage 3 re-validates every header under the ROUTE's configuration whenever
     * it diverges from the gateway baseline, so a carve-out the pre-route floor grants would be undone
     * there unless stage 3 carries the same budget. The edge resolves the carve-out once and hands the
     * same policy to both stages ({@code GatewayEdgeRoute}); these cases wire the chain the same way —
     * the floor, the canonical-path guard, route selection and {@link ThoroughChecksStage}, all fed from
     * one {@link SecurityPostures#cookieHeaderConfigurationFor} result — over a route whose posture
     * diverges from the strict baseline in its body cap alone, the shape that first exposed the
     * post-route regression for {@code Authorization}.
     */
    @Nested
    @DisplayName("past route selection, on a route whose posture diverges from the strict baseline")
    class PostRoute {

        private static final String ROUTE_PREFIX = "/auth/realms/sample";

        @Test
        @DisplayName("key declared: a Cookie value between the strict cap and the declared cap is admitted post-route too")
        void declaredKeyAdmitsTheKeycloakCookiePastRouteSelection() {
            Route route = divergentRoute();
            PipelineRequest request = requestWithHeader("Cookie", keycloakRestartCookie());

            assertAll(
                    () -> assertNotEquals(STRICT, route.securityConfiguration(),
                            "control precondition: the route posture must diverge, or stage 3 skips its re-run"),
                    () -> assertEquals(STRICT.maxHeaderValueLength(),
                            route.securityConfiguration().maxHeaderValueLength(),
                            "control precondition: the route keeps the strict header-value cap"),
                    () -> assertTrue(KEYCLOAK_RESTART_COOKIE_LENGTH > STRICT.maxHeaderValueLength()
                            && KEYCLOAK_RESTART_COOKIE_LENGTH < DECLARED_CAP,
                            "control precondition: the cookie sits between the strict cap and the declared cap"),
                    () -> assertDoesNotThrow(() -> drive(serverModeGateway(DECLARED_CAP), route, request),
                            "the declared cap must survive stage 3's re-run under the route's configuration"),
                    () -> assertEquals(route.id(), Objects.requireNonNull(request.selectedRoute()).id(),
                            "the request must have been selected onto the divergent route, so stage 3 ran"));
        }

        @Test
        @DisplayName("key omitted: the same Cookie value on the same route is rejected (matched control)")
        void omittedKeyRejectsTheKeycloakCookie() {
            Route route = divergentRoute();
            PipelineRequest request = requestWithHeader("Cookie", keycloakRestartCookie());
            GatewayConfig gateway = serverModeGateway(null);

            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> drive(gateway, route, request));

            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
            assertTrue(thrown.getMessage().contains("INPUT_TOO_LONG"),
                    () -> "the refusal must be the length check, got: " + thrown.getMessage());
        }

        /** The edge's inbound chain up to and including stage 3, with one carve-out policy for both stages. */
        private void drive(GatewayConfig gateway, Route route, PipelineRequest request) {
            SecurityConfiguration cookiePolicy =
                    SecurityPostures.cookieHeaderConfigurationFor(gateway, activeRuntime(), STRICT);
            SecurityConfiguration authorizationPolicy =
                    SecurityPostures.authorizationHeaderConfigurationFor(gateway, STRICT);
            new BasicChecksStage(STRICT, counter, cookiePolicy, authorizationPolicy).process(request);
            new CanonicalPathGuard().process(request);
            new RouteSelectionStage(List.of(route)).process(request);
            new ThoroughChecksStage(STRICT, counter, cookiePolicy, authorizationPolicy).process(request, List.of());
        }

        /** A strict route whose posture differs from the baseline in its body cap alone. */
        private Route divergentRoute() {
            SecurityConfiguration raisedBodyCap = SecurityConfigurations.builderSeededFrom(STRICT)
                    .maxBodySize(STRICT.maxBodySize() * 2)
                    .build();
            return Route.builder()
                    .id("idp-realm-sample")
                    .match(MatchConfig.builder().pathPrefix(ROUTE_PREFIX).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("https", "keycloak", 8443, ROUTE_PREFIX))
                    .securityConfiguration(raisedBodyCap)
                    .securityProfile(SecurityProfile.STRICT)
                    .build();
        }
    }

    /**
     * A {@code Cookie} header value of exactly {@link #KEYCLOAK_RESTART_COOKIE_LENGTH} characters, shaped
     * like the cookie pair Keycloak sets on its login page.
     */
    private static String keycloakRestartCookie() {
        String prefix = "KC_RESTART=";
        return prefix + "a".repeat(KEYCLOAK_RESTART_COOKIE_LENGTH - prefix.length());
    }

    private static PipelineRequest requestWithHeader(String name, String value) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/auth/realms/sample/login-actions/authenticate")
                .queryParameters(List.of())
                .headers(Map.of(name, List.of(value)))
                .build();
    }

    private static GatewayConfig serverModeGateway(@Nullable Integer declaredCap) {
        return gateway(OidcConfig.Session.builder()
                .mode(OidcConfig.Session.MODE_SERVER)
                .store("memory")
                .maxCookieHeaderValueLength(declaredCap)
                .build());
    }

    private static GatewayConfig gateway(OidcConfig.Session session) {
        return ConfigFixtures.gateway()
                .securityDefaults(SecurityDefaultsConfig.builder().profile("strict").build())
                .oidc(OidcConfig.builder().session(session).build())
                .build();
    }

    private static BffRuntime activeRuntime() {
        return GatewayEdgeRouteBffWiringTest.activeRuntime(
                GatewayEdgeRouteBffWiringTest.serverBinding(GatewayEdgeRouteBffWiringTest.newStore()));
    }

    /**
     * Every {@link SecurityConfiguration} record component except {@code maxHeaderValueLength} must carry
     * the baseline's value — ADR-0019's "only the length cap changes" bound, swept by reflection so a
     * component added by a cui-http upgrade is covered the moment it exists.
     */
    private static void assertDiffersFromBaselineInCapAlone(SecurityConfiguration baseline,
            SecurityConfiguration carveOut) {
        for (RecordComponent component : SecurityConfiguration.class.getRecordComponents()) {
            if ("maxHeaderValueLength".equals(component.getName())) {
                continue;
            }
            Object baselineValue = assertDoesNotThrow(() -> component.getAccessor().invoke(baseline));
            Object carveOutValue = assertDoesNotThrow(() -> component.getAccessor().invoke(carveOut));
            assertEquals(baselineValue, carveOutValue,
                    "carve-out component '%s' must stay on the baseline".formatted(component.getName()));
        }
    }
}
