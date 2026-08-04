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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.database.AttackTestCase;
import de.cuioss.http.security.database.OWASPTop10AttackDatabase;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract of the non-skippable pre-route floor: collection caps, the URL path pipeline that
 * produces the canonical path, and header name/value validation including both header-name
 * carve-outs.
 * <p>
 * Every stage is exercised against BOTH the {@code strict} baseline — what an undeclared deployment
 * actually resolves to, and the baseline under which the {@code Authorization} regression appeared —
 * and the looser {@code defaults()} preset. Asserting only against {@code defaults()} is precisely
 * what let a strict-baseline regression stay invisible to this suite.
 * <p>
 * Query-parameter NAME and VALUE validation is deliberately absent here — it moved to the post-route
 * {@code ThoroughChecksStage}, where it runs under the route's own configuration and behind the
 * {@code profile} gate. Its coverage lives in {@code ThoroughChecksStageTest}; the reserved-path
 * exemption it used to need is now structural (a reserved path returns before route selection) and
 * is asserted in {@code GatewayEdgeRouteBffWiringTest}.
 */
@DisplayName("BasicChecksStage — the non-skippable pre-route floor")
class BasicChecksStageTest {

    /**
     * The cookie-mode budget the pre-route cap is derived from — the sealed session cookie's own
     * size budget plus the header overhead {@code GatewayEdgeRoute} adds for the cookie name and
     * any co-resident cookies.
     */
    private static final int COOKIE_HEADER_CAP = 4096 + 512;

    /** The {@code Authorization} carve-out budget, as an omitted configuration key resolves it. */
    private static final int AUTHORIZATION_CAP =
            SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH;

    private static final SecurityConfiguration STRICT = SecurityConfiguration.strict();
    private static final SecurityConfiguration DEFAULTS = SecurityConfiguration.defaults();

    /** An extended-ASCII character: admitted by the builder defaults, refused by {@code strict}. */
    private static final String EXTENDED_ASCII = "é";

    private final SecurityEventCounter counter = new SecurityEventCounter();

    private final BasicChecksStage strictStage = stageWithoutCookieCarveOut(STRICT);
    private final BasicChecksStage defaultStage = stageWithoutCookieCarveOut(DEFAULTS);
    /** A cookie-mode gateway's stage: the raised cap applies to Cookie header values only. */
    private final BasicChecksStage cookieModeStage = stageWithCookieCarveOut(DEFAULTS);
    /** The same cookie-mode gateway on the strict baseline every undeclared deployment resolves to. */
    private final BasicChecksStage strictCookieModeStage = stageWithCookieCarveOut(STRICT);

    static Stream<AttackTestCase> owaspTop10() {
        return StreamSupport.stream(new OWASPTop10AttackDatabase().getAttackTestCases().spliterator(), false);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("owaspTop10")
    @DisplayName("rejects every OWASP Top 10 attack-database path as a security-filter violation")
    void rejectsOwaspTop10(AttackTestCase attack) {
        // Arrange
        PipelineRequest request = requestWithPath(attack.attackString());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("accepts a legitimate path and records the single canonical path")
    void acceptsLegitimatePath() {
        // Arrange
        PipelineRequest request = requestWithPath("/api/v1/users");

        // Act
        defaultStage.process(request);

        // Assert — the recorded value is what route selection matches on, so its presence is not the
        // claim: a canonicalizer that emitted "/" or echoed a half-decoded path would satisfy a
        // non-null check while breaking every route match downstream.
        assertEquals("/api/v1/users", request.canonicalPath(),
                "the floor records the canonical form of the request path for route selection");
    }

    @Test
    @DisplayName("no longer validates query-parameter names or values — that moved post-route")
    void doesNotValidateParameterNamesOrValues() {
        // Arrange — a parameter value carrying '/' is rejected by the url-parameter pipeline, which
        // now runs post-route under the route's own configuration. The pre-route floor must let it
        // through untouched; the count cap below is the only parameter concern that stays here.
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .queryParameters(Map.of("return_to", List.of("/home")))
                .headers(Map.of())
                .build();

        // Act + Assert
        assertDoesNotThrow(() -> strictStage.process(request),
                "url-parameter name/value validation is no longer a pre-route concern");
    }

    @Test
    @DisplayName("rejects a query-parameter count beyond the configured cap")
    void rejectsExcessiveParameterCount() {
        // Arrange — the parameter COUNT cap stays in the floor even though the values it bounds are
        // validated post-route: a collection limit is a resource guard, not an injection defence.
        int cap = DEFAULTS.maxParameterCount();
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (int i = 0; i <= cap; i++) {
            parameters.put("p" + i, List.of("1"));
        }
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .queryParameters(parameters)
                .build();

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> defaultStage.process(request));

        // Assert
        assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a header count beyond the configured cap")
    void rejectsExcessiveHeaderCount() {
        // Arrange
        int cap = DEFAULTS.maxHeaderCount();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i <= cap; i++) {
            headers.put("x-h" + i, List.of("v"));
        }
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .headers(headers)
                .build();

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> defaultStage.process(request));

        // Assert
        assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, thrown.getEventType());
    }

    @Nested
    @DisplayName("Cookie / Set-Cookie carve-out")
    class CookieCarveOut {

        @Test
        @DisplayName("accepts a Cookie header value at the configured sealed-cookie budget")
        void acceptsCookieHeaderAtBudget() {
            // Arrange — the pre-route cap must admit the largest Cookie header a compliant sealed
            // session can produce; before the fix this was rejected 400 at the baseline cap.
            PipelineRequest request = requestWithHeader("Cookie", "__Host-sheriff-session=" + "a".repeat(4096));

            // Act + Assert
            assertDoesNotThrow(() -> cookieModeStage.process(request));
        }

        @Test
        @DisplayName("strict baseline: accepts a Cookie header value at the configured budget")
        void strictBaselineAcceptsCookieHeaderAtBudget() {
            // Arrange — the strict-baseline peer: the carve-out must clear the 1024 strict cap too,
            // not merely the looser preset the suite used to measure against.
            PipelineRequest request = requestWithHeader("Cookie", "__Host-sheriff-session=" + "a".repeat(4096));

            // Act + Assert
            assertDoesNotThrow(() -> strictCookieModeStage.process(request));
        }

        @Test
        @DisplayName("still rejects a Cookie header value beyond the raised cap")
        void rejectsCookieHeaderBeyondCap() {
            // Arrange — the relaxation raises the cap, it does not remove it.
            PipelineRequest request = requestWithHeader("Cookie", "a".repeat(COOKIE_HEADER_CAP + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> cookieModeStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("strict baseline: still rejects a Cookie header value beyond the raised cap")
        void strictBaselineRejectsCookieHeaderBeyondCap() {
            // Arrange
            PipelineRequest request = requestWithHeader("Cookie", "a".repeat(COOKIE_HEADER_CAP + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictCookieModeStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("an oversized NON-cookie header is still rejected at the baseline cap")
        void rejectsOversizedNonCookieHeader() {
            // Arrange — the carve-out is scoped to the Cookie header; every other header keeps the
            // resolved baseline cap.
            PipelineRequest request = requestWithHeader("X-Custom", "a".repeat(DEFAULTS.maxHeaderValueLength() + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> cookieModeStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("strict baseline: an oversized NON-cookie header is still rejected at the baseline cap")
        void strictBaselineRejectsOversizedNonCookieHeader() {
            // Arrange
            PipelineRequest request = requestWithHeader("X-Custom", "a".repeat(STRICT.maxHeaderValueLength() + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictCookieModeStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("server / bearer-only mode: an oversized Cookie header is still rejected at the baseline cap")
        void nonCookieModeRejectsOversizedCookieHeader() {
            // Arrange — proves the lift did not leak across modes: a gateway that is not a
            // cookie-mode BFF keeps the baseline cap on the Cookie header exactly as before.
            PipelineRequest request = requestWithHeader("Cookie", "a".repeat(DEFAULTS.maxHeaderValueLength() + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> defaultStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("strict baseline, bearer-only mode: an oversized Cookie header is still rejected")
        void strictBaselineNonCookieModeRejectsOversizedCookieHeader() {
            // Arrange
            PipelineRequest request = requestWithHeader("Cookie", "a".repeat(STRICT.maxHeaderValueLength() + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }
    }

    @Nested
    @DisplayName("Authorization carve-out")
    class AuthorizationCarveOut {

        @Test
        @DisplayName("admits a bearer token above the strict baseline cap and within the configured cap")
        void admitsBearerTokenAboveBaselineCap() {
            // Arrange — the regression itself: a real access token plus the 'Bearer ' prefix lands
            // above the strict 1024 cap, which rejected every bearer request 400 at this floor.
            // strictStage carries NO cookie configuration, so this doubles as the proof that the
            // Authorization carve-out has no mode axis: it is present on a bearer-only gateway.
            String value = "Bearer " + "a".repeat(2000);
            PipelineRequest request = requestWithHeader("Authorization", value);

            // Act + Assert
            assertDoesNotThrow(() -> strictStage.process(request),
                    "an Authorization value within the carve-out budget must clear the pre-route floor");
        }

        @Test
        @DisplayName("still rejects an Authorization value beyond the configured cap")
        void rejectsAuthorizationBeyondConfiguredCap() {
            // Arrange — the carve-out raises the cap, it does not remove it.
            PipelineRequest request = requestWithHeader("Authorization", "a".repeat(AUTHORIZATION_CAP + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("matches the header name case-insensitively, as HTTP requires")
        void matchesHeaderNameCaseInsensitively() {
            // Arrange — inbound header names arrive in whatever case the client sent.
            PipelineRequest request = requestWithHeader("authorization", "Bearer " + "a".repeat(2000));

            // Act + Assert
            assertDoesNotThrow(() -> strictStage.process(request));
        }

        @Test
        @DisplayName("an oversized header that is neither Authorization nor a cookie keeps the baseline cap")
        void doesNotLeakToOtherHeaders() {
            // Arrange — the carve-out is scoped by header name and must not widen the floor.
            PipelineRequest request = requestWithHeader("X-Custom", "a".repeat(STRICT.maxHeaderValueLength() + 1));

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }
    }

    @Nested
    @DisplayName("Carve-out validator inheritance — only the length cap changes")
    class CarveOutValidatorInheritance {

        @Test
        @DisplayName("an Authorization value within the cap is still refused for an extended-ASCII character")
        void authorizationValueStillRefusesExtendedAscii() {
            // Arrange — the ADR-0019 'only the length cap changes' bound: the carve-out pipeline
            // applies every NON-length validator of the baseline it was seeded from. A carve-out
            // seeded from the builder defaults instead would admit this value, because the builder
            // defaults permit extended ASCII while strict does not.
            PipelineRequest request = requestWithHeader("Authorization", "Bearer " + EXTENDED_ASCII + "token");

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("a Cookie value within the cap is still refused for an extended-ASCII character")
        void cookieValueStillRefusesExtendedAscii() {
            // Arrange — the same bound for the cookie carve-out, which is where it was actually
            // broken: builder-default seeding silently relaxed extended-ASCII, suspicious-pattern
            // and case-sensitivity handling for cookie header values on a strict gateway.
            PipelineRequest request = requestWithHeader("Cookie",
                    "__Host-sheriff-session=" + EXTENDED_ASCII + "value");

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> strictCookieModeStage.process(request));

            // Assert
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("the same extended-ASCII value is admitted under a baseline that permits it")
        void extendedAsciiIsAdmittedUnderAPermissiveBaseline() {
            // Arrange — the matched control: without it the two refusals above could not distinguish
            // 'the carve-out inherited the strict baseline' from 'the value is refused regardless'.
            PipelineRequest request = requestWithHeader("Authorization", "Bearer " + EXTENDED_ASCII + "token");

            // Act + Assert
            assertDoesNotThrow(() -> defaultStage.process(request),
                    "the builder-default baseline permits extended ASCII, so the refusal above is "
                            + "attributable to the strict seeding and not to the value itself");
        }
    }

    private BasicChecksStage stageWithoutCookieCarveOut(SecurityConfiguration baseline) {
        return new BasicChecksStage(baseline, counter, null, withHeaderValueCap(baseline, AUTHORIZATION_CAP));
    }

    private BasicChecksStage stageWithCookieCarveOut(SecurityConfiguration baseline) {
        return new BasicChecksStage(baseline, counter, withHeaderValueCap(baseline, COOKIE_HEADER_CAP),
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

    private static PipelineRequest requestWithHeader(String name, String value) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .queryParameters(Map.of())
                .headers(Map.of(name, List.of(value)))
                .build();
    }

    private static PipelineRequest requestWithPath(String path) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(path)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
    }
}
