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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.database.AttackTestCase;
import de.cuioss.http.security.database.OWASPTop10AttackDatabase;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteMatcher;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ThoroughChecksStage — stage 3, always dispatched, with a profile-gated skippable half")
class ThoroughChecksStageTest {

    /** A parameter value the url-parameter pipeline rejects (a path separator inside a value). */
    private static final String REJECTED_PARAMETER_VALUE = "/home";
    /** A parameter name the url-parameter pipeline rejects (an embedded null byte). */
    private static final String REJECTED_PARAMETER_NAME = "evil\0name";

    /** The {@code Authorization} carve-out budget, as an omitted configuration key resolves it. */
    private static final int AUTHORIZATION_CAP =
            SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH;

    /** A cookie-mode gateway's sealed-cookie budget plus the header overhead the edge adds. */
    private static final int COOKIE_HEADER_CAP = 4096 + 512;

    private final SecurityConfiguration defaultConfiguration = SecurityConfiguration.defaults();
    private final SecurityEventCounter counter = new SecurityEventCounter();
    private final ThoroughChecksStage stage = stageWith(defaultConfiguration, null);

    static Stream<AttackTestCase> owaspTop10() {
        return StreamSupport.stream(new OWASPTop10AttackDatabase().getAttackTestCases().spliterator(), false);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("owaspTop10")
    @DisplayName("re-runs the divergent route filter and rejects an attack path as a security violation")
    void rejectsDivergentFilterViolation(AttackTestCase attack) {
        // Arrange — a route whose strict config diverges from the stage-1 default forces a re-run
        PipelineRequest request = requestFor(attack.attackString(),
                routeWithConfig(Optional.of(SecurityConfiguration.strict())));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of()));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("accepts a legitimate request under a divergent strict route filter")
    void acceptsLegitimateRequestUnderDivergentFilter() {
        // Arrange
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(Map.of("page", List.of("2")))
                .headers(Map.of("x-trace", List.of("abc123")))
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(routeWithConfig(Optional.of(SecurityConfiguration.strict())));

        // Act + Assert — every pipeline (path, params, headers) passes for a benign request
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("skips the pipeline re-run when the route config equals the stage-1 default")
    void skipsReRunWhenRouteConfigEqualsDefault() {
        // Arrange — a route whose config equals the default was already covered by stage 1
        PipelineRequest request = requestFor("/api/orders",
                routeWithConfig(Optional.of(defaultConfiguration)));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("falls back to the stage-1 baseline when a route carries no resolved configuration")
    void fallsBackToBaselineWhenRouteDeclaresNoConfig() {
        // Arrange — the posture resolver leaves every assembler-produced route with a configuration,
        // so this covers only a RouteRuntime built without one.
        PipelineRequest request = requestFor("/api/orders", routeWithConfig(Optional.empty()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("fast-rejects a declared body already exceeding the route cap before the body is read")
    void rejectsBodyExceedingRouteCap() {
        // Arrange
        long cap = defaultConfiguration.maxBodySize();
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.POST)
                .requestPath("/api/orders")
                .declaredContentLength(cap + 1)
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(routeWithConfig(Optional.of(defaultConfiguration)));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of()));

        // Assert
        assertEquals(EventType.CONTENT_TOO_LARGE, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a canonical path outside the route's allowed_paths whitelist")
    void rejectsPathOutsideAllowedPaths() {
        // Arrange
        PipelineRequest request = requestFor("/api/other", routeWithConfig(Optional.empty()));

        // Act
        List<String> allowedPaths = List.of("/api/orders");
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, allowedPaths));

        // Assert
        assertEquals(EventType.PATH_NOT_ALLOWED, thrown.getEventType());
    }

    @Test
    @DisplayName("admits a path matching an allowed_paths pattern with a single-segment wildcard")
    void admitsWildcardWhitelistMatch() {
        // Arrange — {id} matches exactly one non-empty path segment
        PipelineRequest request = requestFor("/api/42/detail", routeWithConfig(Optional.empty()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of("/api/{id}/detail")));
    }

    @Test
    @DisplayName("rejects a whitelist pattern whose segment count differs from the path")
    void rejectsWildcardSegmentCountMismatch() {
        // Arrange — the path has one segment too few to match the pattern
        PipelineRequest request = requestFor("/api/42", routeWithConfig(Optional.empty()));

        // Act
        List<String> allowedPaths = List.of("/api/{id}/detail");
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, allowedPaths));

        // Assert
        assertEquals(EventType.PATH_NOT_ALLOWED, thrown.getEventType());
    }

    @Test
    @DisplayName("fails loud when the route was not selected at stage 2")
    void failsLoudWhenRouteNotSelected() {
        // Arrange — no selected route recorded
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .build();

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("fails loud when the canonical path was not resolved at stage 1")
    void failsLoudWhenCanonicalPathMissing() {
        // Arrange — a selected route but no canonical path recorded
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .build();
        request.selectedRoute(routeWithConfig(Optional.empty()));

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> stage.process(request, List.of()));
    }

    /**
     * The relocated url-parameter validation: it now runs here, under the ROUTE's configuration,
     * for every non-{@code none} route — unconditionally, because it is the only run rather than a
     * re-run.
     */
    @Nested
    @DisplayName("relocated url-parameter name/value validation")
    class ParameterValidation {

        @ParameterizedTest(name = "profile {0} rejects a bad parameter VALUE")
        @EnumSource(value = SecurityProfile.class, names = {"STRICT", "LENIENT"})
        @DisplayName("rejects a parameter value the route's pipeline refuses")
        void rejectsParameterValue(SecurityProfile profile) {
            // Arrange
            PipelineRequest request = requestWithParameters(
                    Map.of("return_to", List.of(REJECTED_PARAMETER_VALUE)),
                    route(profile, Optional.of(defaultConfiguration)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @ParameterizedTest(name = "profile {0} rejects a bad parameter NAME")
        @EnumSource(value = SecurityProfile.class, names = {"STRICT", "LENIENT"})
        @DisplayName("rejects a parameter name with the same rigor as its value — the name check survived the move")
        void rejectsParameterName(SecurityProfile profile) {
            // Arrange — a parameter NAME carrying a null byte is a classic injection vector and must
            // be rejected, not only the value (symmetry with header-name validation).
            PipelineRequest request = requestWithParameters(
                    Map.of(REJECTED_PARAMETER_NAME, List.of("ok")),
                    route(profile, Optional.of(defaultConfiguration)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("validates parameters even when the route config equals the baseline (it is a run, not a re-run)")
        void validatesParametersWithoutDivergence() {
            // Arrange — the divergence guard skips the PATH/HEADER re-run for a baseline-equal route,
            // but parameter validation must still run: stage 1 no longer does it.
            PipelineRequest request = requestWithParameters(
                    Map.of("return_to", List.of(REJECTED_PARAMETER_VALUE)),
                    route(SecurityProfile.STRICT, Optional.of(defaultConfiguration)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }
    }

    /**
     * The {@code none} partial-disable matrix. The closed list of what {@code none} turns off is the
     * relocated parameter validation and the pipeline re-run — and nothing else.
     */
    @Nested
    @DisplayName("profile: none — the partial disable")
    class NoneModePartialDisable {

        @Test
        @DisplayName("still enforces the body cap")
        void stillEnforcesBodyCap() {
            // Arrange
            long cap = defaultConfiguration.maxBodySize();
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.POST)
                    .requestPath("/api/orders")
                    .declaredContentLength(cap + 1)
                    .build();
            request.canonicalPath("/api/orders");
            request.selectedRoute(route(SecurityProfile.NONE, Optional.of(defaultConfiguration)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, List.of()));

            // Assert — a DoS resource guard is not an injection defence, so it does not ride the mode
            assertEquals(EventType.CONTENT_TOO_LARGE, thrown.getEventType());
        }

        @Test
        @DisplayName("still enforces allowed_paths")
        void stillEnforcesAllowedPaths() {
            // Arrange
            PipelineRequest request = requestFor("/api/other",
                    route(SecurityProfile.NONE, Optional.of(defaultConfiguration)));

            // Act
            List<String> allowedPaths = List.of("/api/orders");
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, allowedPaths));

            // Assert
            assertEquals(EventType.PATH_NOT_ALLOWED, thrown.getEventType());
        }

        @Test
        @DisplayName("accepts a parameter name and value a non-none route rejects")
        void acceptsParameterValidationTheModeDisables() {
            // Arrange — both halves of the relocated validation are off under 'none'
            PipelineRequest request = requestWithParameters(
                    Map.of("return_to", List.of(REJECTED_PARAMETER_VALUE),
                            REJECTED_PARAMETER_NAME, List.of("ok")),
                    route(SecurityProfile.NONE, Optional.of(defaultConfiguration)));

            // Act + Assert
            assertDoesNotThrow(() -> stage.process(request, List.of()),
                    "'none' disables the relocated url-parameter name/value validation");
        }

        @Test
        @DisplayName("skips the divergent pipeline re-run")
        void skipsPipelineReRun() {
            // Arrange — a divergent strict config over a path the re-run would reject
            PipelineRequest request = requestFor("/api/../etc/passwd",
                    route(SecurityProfile.NONE, Optional.of(SecurityConfiguration.strict())));

            // Act + Assert
            assertDoesNotThrow(() -> stage.process(request, List.of()),
                    "'none' disables the per-route pipeline re-run");
        }

        @Test
        @DisplayName("a strict route rejects the very path and parameters the none route accepted")
        void strictRouteStillRejectsWhatNoneAccepts() {
            // Arrange — the control case that makes the two assertions above meaningful
            PipelineRequest divergentPath = requestFor("/api/../etc/passwd",
                    route(SecurityProfile.STRICT, Optional.of(SecurityConfiguration.strict())));
            PipelineRequest badParameters = requestWithParameters(
                    Map.of("return_to", List.of(REJECTED_PARAMETER_VALUE)),
                    route(SecurityProfile.STRICT, Optional.of(SecurityConfiguration.strict())));

            // Act + Assert
            assertThrows(GatewayException.class, () -> stage.process(divergentPath, List.of()),
                    "the same path is rejected once the profile is not 'none'");
            assertThrows(GatewayException.class, () -> stage.process(badParameters, List.of()),
                    "the same parameters are rejected once the profile is not 'none'");
        }
    }

    /**
     * The divergent-route header-NAME re-validation (finding b35d66). The pre-route
     * {@code BasicChecksStage} validates header names under the GATEWAY BASELINE only, so a route
     * whose declared {@code security_filter} carries a stricter name policy — here a lower
     * {@code maxHeaderNameLength} — is only ever enforced by the re-run in this stage.
     */
    @Nested
    @DisplayName("divergent route header-name policy")
    class DivergentHeaderNameValidation {

        /** 31 characters: accepted by the baseline's 128-character cap, refused by the route's 8. */
        private static final String OVERLONG_HEADER_NAME = "x-tenant-correlation-identifier";

        @ParameterizedTest(name = "profile {0} enforces the route's own header-name cap")
        @EnumSource(value = SecurityProfile.class, names = {"STRICT", "LENIENT"})
        @DisplayName("rejects a header name the route's divergent configuration refuses")
        void rejectsHeaderNameUnderDivergentConfig(SecurityProfile profile) {
            // Arrange — the baseline accepts this name, the route's own configuration does not
            assertTrue(OVERLONG_HEADER_NAME.length() <= defaultConfiguration.maxHeaderNameLength(),
                    "the baseline must accept the name, otherwise the case proves nothing about the re-run");
            PipelineRequest request = requestWithHeaders(
                    Map.of(OVERLONG_HEADER_NAME, List.of("abc123")),
                    route(profile, Optional.of(stricterHeaderNamePolicy())));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> stage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("leaves the header name unvalidated under profile: none — the closed disable list is unchanged")
        void skipsHeaderNameValidationUnderNone() {
            // Arrange — the same stricter route policy, this time on a 'none' route
            PipelineRequest request = requestWithHeaders(
                    Map.of(OVERLONG_HEADER_NAME, List.of("abc123")),
                    route(SecurityProfile.NONE, Optional.of(stricterHeaderNamePolicy())));

            // Act + Assert — reRunPipelines IS item two of what 'none' disables, in its entirety
            assertDoesNotThrow(() -> stage.process(request, List.of()),
                    "'none' skips the whole skippable half, header names included");
        }

        @Test
        @DisplayName("does not re-run the header-name pipeline for a route whose config equals the baseline")
        void skipsHeaderNameReRunWhenConfigEqualsBaseline() {
            // Arrange — a stage whose BASELINE itself refuses the name, and a route carrying exactly
            // that baseline: the skip-if-equal guard must leave the name to the pre-route stage.
            SecurityConfiguration baseline = stricterHeaderNamePolicy();
            ThoroughChecksStage baselineEqualStage = new ThoroughChecksStage(baseline,
                    new SecurityEventCounter(), null, withHeaderValueCap(baseline, AUTHORIZATION_CAP));
            PipelineRequest request = requestWithHeaders(
                    Map.of(OVERLONG_HEADER_NAME, List.of("abc123")),
                    route(SecurityProfile.STRICT, Optional.of(baseline)));

            // Act + Assert
            assertDoesNotThrow(() -> baselineEqualStage.process(request, List.of()),
                    "a baseline-equal route is not re-validated here — BasicChecksStage already did it");
        }

        private static SecurityConfiguration stricterHeaderNamePolicy() {
            return SecurityConfiguration.builder().maxHeaderNameLength(8).build();
        }
    }

    /**
     * The post-route half of the two header-value carve-outs (finding 5a573e). The pre-route floor
     * grants {@code Authorization} and {@code Cookie} / {@code Set-Cookie} a raised length cap; this
     * stage re-validates every header value under the ROUTE's configuration whenever that
     * configuration diverges from the baseline, and used to do so with no carve-out at all — silently
     * undoing both relaxations one stage later, and doing it BEFORE the authentication stage, so an
     * {@code Authorization} value was still present to be rejected.
     * <p>
     * The whole suite runs on the {@code strict} baseline every undeclared deployment resolves to,
     * because that is the baseline under which the regression appears.
     */
    @Nested
    @DisplayName("post-route header-value carve-outs")
    class PostRouteHeaderCarveOuts {

        /** The strict gateway baseline: a 1024-character header-value cap. */
        private final SecurityConfiguration strictBaseline = SecurityConfiguration.strict();

        /**
         * The auditor's exact route shape and the {@code /upload} anchor's shape in the integration
         * topology: a route declaring <em>nothing but</em> a raised {@code max_body_bytes}. It leaves
         * the header-value cap on the strict baseline, yet diverges from that baseline — which is
         * precisely what arms the re-run.
         */
        private final SecurityConfiguration raisedBodyRoute = SecurityConfigurations
                .builderSeededFrom(strictBaseline)
                .maxBodySize(64L * 1024 * 1024)
                .build();

        /** A route that legitimately RAISES its own header-value cap above the carve-out budget. */
        private final SecurityConfiguration raisedHeaderCapRoute = SecurityConfigurations
                .builderSeededFrom(strictBaseline)
                .maxHeaderValueLength(AUTHORIZATION_CAP * 2)
                .build();

        /** 1107 characters — the value the audit measured, above the strict 1024 cap. */
        private final String longBearer = "Bearer " + "a".repeat(1100);

        /** A bearer-only strict gateway: the Authorization carve-out only, no cookie carve-out. */
        private final ThoroughChecksStage strictStage = stageWith(strictBaseline, null);

        /** An active cookie-mode BFF on the same strict baseline. */
        private final ThoroughChecksStage cookieModeStage =
                stageWith(strictBaseline, withHeaderValueCap(strictBaseline, COOKIE_HEADER_CAP));

        @Test
        @DisplayName("admits a >1024-character Authorization value on a route declaring only a raised max_body_bytes")
        void admitsLongAuthorizationOnBodyCapOnlyRoute() {
            // Arrange — the empirically confirmed regression: this exact request was rejected 400
            // with SECURITY_FILTER_VIOLATION here, one stage before bearer validation ever ran.
            assertTrue(longBearer.length() > strictBaseline.maxHeaderValueLength(),
                    "the value must exceed the strict cap, otherwise the case proves nothing");
            assertNotEquals(strictBaseline, raisedBodyRoute,
                    "the route must diverge from the baseline, otherwise the re-run is skipped");
            PipelineRequest request = requestWithHeaders(
                    Map.of("Authorization", List.of(longBearer)),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act + Assert
            assertDoesNotThrow(() -> strictStage.process(request, List.of()),
                    "the pre-route Authorization carve-out must survive the divergent-route re-run");
        }

        @Test
        @DisplayName("without the carve-out budget the same request is rejected — the pre-fix behaviour")
        void rejectsLongAuthorizationWhenNoCarveOutBudgetExists() {
            // Arrange — the matched negative control pinning what the fix changed: a stage whose
            // Authorization budget does not exceed the route's own cap has no carve-out to apply, so
            // the re-run measures the value at the route cap exactly as the unfixed stage did.
            ThoroughChecksStage withoutCarveOut = new ThoroughChecksStage(strictBaseline, counter,
                    null, strictBaseline);
            PipelineRequest request = requestWithHeaders(
                    Map.of("Authorization", List.of(longBearer)),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> withoutCarveOut.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType(),
                    "the admission above is attributable to the carve-out, not to the value being short");
        }

        @ParameterizedTest(name = "admits a long {0} value on a cookie-mode gateway")
        @ValueSource(strings = {"Cookie", "Set-Cookie"})
        @DisplayName("admits a >1024-character cookie header value on the same body-cap-only route")
        void admitsLongCookieOnCookieModeGateway(String headerName) {
            // Arrange — the symmetric case for the second carve-out
            String sealedCookie = "__Host-sheriff-session=" + "a".repeat(4096);
            PipelineRequest request = requestWithHeaders(
                    Map.of(headerName, List.of(sealedCookie)),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act + Assert
            assertDoesNotThrow(() -> cookieModeStage.process(request, List.of()),
                    "the pre-route cookie carve-out must survive the divergent-route re-run");
        }

        @Test
        @DisplayName("a bearer-only gateway still rejects the same long cookie — the by-deployment bound holds")
        void bearerOnlyGatewayStillRejectsLongCookie() {
            // Arrange — the cookie carve-out has a MODE axis the Authorization one does not; carrying
            // it post-route must not quietly grant it to gateways that never had it.
            PipelineRequest request = requestWithHeaders(
                    Map.of("Cookie", List.of("__Host-sheriff-session=" + "a".repeat(4096))),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictStage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("an oversized header that is neither Authorization nor a cookie keeps the ROUTE's cap")
        void doesNotLeakToOtherHeaders() {
            // Arrange — the by-header bound: exactly two names, never a third
            PipelineRequest request = requestWithHeaders(
                    Map.of("X-Custom", List.of("a".repeat(1100))),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> cookieModeStage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("the carve-out raises the cap, it does not remove it")
        void stillRejectsBeyondTheCarveOutBudget() {
            // Arrange
            PipelineRequest request = requestWithHeaders(
                    Map.of("Authorization", List.of("a".repeat(AUTHORIZATION_CAP + 1))),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictStage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("the carve-out differs from the ROUTE configuration in the length cap alone")
        void carveOutDiffersFromRouteConfigInCapAlone() {
            // Arrange + Act — seeded from the ROUTE's own policy, never from the gateway baseline
            SecurityConfiguration carveOut =
                    ThoroughChecksStage.carveOutConfigurationFor(raisedBodyRoute, AUTHORIZATION_CAP);

            // Assert
            assertEquals(AUTHORIZATION_CAP, carveOut.maxHeaderValueLength(),
                    "the carve-out budget raises the route's cap for the carved-out header");
            assertNotEquals(raisedBodyRoute.maxHeaderValueLength(), carveOut.maxHeaderValueLength());
            assertDiffersFromRouteConfigInCapAlone(raisedBodyRoute, carveOut);
        }

        @Test
        @DisplayName("a route whose own cap exceeds the carve-out budget keeps its higher cap")
        void routeCapAboveTheBudgetIsNeverLowered() {
            // Arrange — max(routeCap, budget), never a blind override: this route declared 16384
            assertTrue(raisedHeaderCapRoute.maxHeaderValueLength() > AUTHORIZATION_CAP,
                    "the route must out-declare the budget, otherwise max() is not being exercised");
            String aboveBudget = "Bearer " + "a".repeat(AUTHORIZATION_CAP + 500);

            // Act
            SecurityConfiguration carveOut = ThoroughChecksStage.carveOutConfigurationFor(
                    raisedHeaderCapRoute, AUTHORIZATION_CAP);
            PipelineRequest admitted = requestWithHeaders(
                    Map.of("Authorization", List.of(aboveBudget)),
                    route(SecurityProfile.STRICT, Optional.of(raisedHeaderCapRoute)));
            PipelineRequest refused = requestWithHeaders(
                    Map.of("Authorization", List.of("a".repeat(
                            raisedHeaderCapRoute.maxHeaderValueLength() + 1))),
                    route(SecurityProfile.STRICT, Optional.of(raisedHeaderCapRoute)));

            // Assert
            assertEquals(raisedHeaderCapRoute.maxHeaderValueLength(), carveOut.maxHeaderValueLength(),
                    "the route's higher cap wins over the lower carve-out budget");
            assertEquals(raisedHeaderCapRoute, carveOut,
                    "no dimension changes at all when the route already out-declares the budget");
            assertDoesNotThrow(() -> strictStage.process(admitted, List.of()),
                    "a value above the budget but within the route's own cap must be admitted");
            assertThrows(GatewayException.class, () -> strictStage.process(refused, List.of()),
                    "the route's own cap is still enforced above the budget");
        }

        @Test
        @DisplayName("a carved-out value is still refused for an extended-ASCII character")
        void carveOutKeepsEveryNonLengthValidatorOnTheRouteSetting() {
            // Arrange — the by-validator bound, behaviourally: the strict route forbids extended
            // ASCII, and the carve-out must not relax that while raising the length cap. A carve-out
            // seeded from the builder defaults instead would admit this value.
            PipelineRequest request = requestWithHeaders(
                    Map.of("Authorization", List.of("Bearer é" + "a".repeat(1100))),
                    route(SecurityProfile.STRICT, Optional.of(raisedBodyRoute)));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictStage.process(request, List.of()));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("the same extended-ASCII value is admitted under a route whose policy permits it")
        void extendedAsciiIsAdmittedUnderAPermissiveRoute() {
            // Arrange — the matched control: without it the refusal above could not distinguish 'the
            // carve-out inherited the ROUTE's strict setting' from 'the value is refused regardless'.
            SecurityConfiguration permissiveRoute = SecurityConfiguration.defaults();
            assertTrue(permissiveRoute.allowExtendedAscii(),
                    "the control route must permit what the strict route refuses");
            PipelineRequest request = requestWithHeaders(
                    Map.of("Authorization", List.of("Bearer é" + "a".repeat(1100))),
                    route(SecurityProfile.STRICT, Optional.of(permissiveRoute)));

            // Act + Assert
            assertDoesNotThrow(() -> strictStage.process(request, List.of()),
                    "the refusal above is attributable to the route's own policy, not to the value");
        }

        /**
         * Exhaustive component sweep, mirroring {@code GatewayEdgeRouteTest}'s pre-route peer: every
         * {@link SecurityConfiguration} record component except {@code maxHeaderValueLength} must
         * carry the ROUTE configuration's value. Reflection rather than a hand-written list so a
         * component added by a cui-http upgrade is covered the moment it exists.
         */
        private void assertDiffersFromRouteConfigInCapAlone(SecurityConfiguration routeConfig,
                SecurityConfiguration carveOut) {
            for (RecordComponent component : SecurityConfiguration.class.getRecordComponents()) {
                if ("maxHeaderValueLength".equals(component.getName())) {
                    continue;
                }
                Object routeValue = assertDoesNotThrow(() -> component.getAccessor().invoke(routeConfig));
                Object carveOutValue = assertDoesNotThrow(() -> component.getAccessor().invoke(carveOut));
                assertEquals(routeValue, carveOutValue,
                        "carve-out component '%s' must stay on the ROUTE's setting — only the length cap changes"
                                .formatted(component.getName()));
            }
        }
    }

    /**
     * Builds a stage as {@code GatewayEdgeRoute} wires it: the gateway baseline plus the two
     * header-value carve-out policies the pre-route floor receives, the {@code Authorization} one
     * unconditional and the cookie one supplied only by an active cookie-mode BFF.
     */
    private ThoroughChecksStage stageWith(SecurityConfiguration baseline,
            @Nullable SecurityConfiguration cookieHeaderConfiguration) {
        return new ThoroughChecksStage(baseline, counter, cookieHeaderConfiguration,
                withHeaderValueCap(baseline, AUTHORIZATION_CAP));
    }

    /**
     * Mirrors the production seeding: a configuration identical to {@code baseline} in every
     * dimension except {@code maxHeaderValueLength}. Routed through the shared production seam so the
     * fixture cannot drift from the component set the production carve-outs copy.
     */
    private static SecurityConfiguration withHeaderValueCap(SecurityConfiguration baseline, int cap) {
        return SecurityConfigurations.builderSeededFrom(baseline).maxHeaderValueLength(cap).build();
    }

    private static PipelineRequest requestWithHeaders(Map<String, List<String>> headers, RouteRuntime route) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(Map.of())
                .headers(headers)
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(route);
        return request;
    }

    private static PipelineRequest requestFor(String canonicalPath, RouteRuntime route) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(canonicalPath)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(canonicalPath);
        request.selectedRoute(route);
        return request;
    }

    private static PipelineRequest requestWithParameters(Map<String, List<String>> parameters, RouteRuntime route) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(parameters)
                .headers(Map.of())
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(route);
        return request;
    }

    private static RouteRuntime routeWithConfig(Optional<SecurityConfiguration> config) {
        return route(SecurityProfile.STRICT, config);
    }

    private static RouteRuntime route(SecurityProfile profile, Optional<SecurityConfiguration> config) {
        return RouteRuntime.builder()
                .id("r")
                .matcher(RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").build()))
                .securityProfile(profile)
                .securityConfiguration(config)
                .build();
    }
}
