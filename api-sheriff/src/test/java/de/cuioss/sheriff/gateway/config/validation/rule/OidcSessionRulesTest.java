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

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static de.cuioss.sheriff.gateway.testsupport.ValidationAsserts.assertHasError;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the OIDC session rules the {@link ConfigValidator} runs through {@link OidcSessionRules}:
 * the {@code oidc.session.mode} store requirement and its server-mode {@code key_file} refusal, the
 * {@code max_sessions},
 * {@code max_sessions_per_subject}, {@code idle_timeout_seconds} and {@code max_cookie_size} ranges, the
 * browser-guarantee warning on the derived session {@code Set-Cookie} header, and the
 * {@code cookie_name} refusal.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. The class-level test logger captures every logger, so the
 * budget-warning assertions read the warning wherever it is emitted.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("ConfigValidator — OIDC session rules")
class OidcSessionRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Nested
    @DisplayName("oidc.session.max_sessions_per_subject — its range, and its refusal in cookie mode")
    class SessionMaxSessionsPerSubject {

        private static final String POINTER = "/oidc/session/max_sessions_per_subject";

        /** The refusals recorded at the key's own pointer for a session block of the given shape. */
        private List<ConfigError> refusals(String mode, @Nullable Integer maxSessions, @Nullable Integer perSubject) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer("https://idp.example")
                    .clientId("gateway")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri("https://gw.example.com/auth/callback")
                    .session(OidcConfig.Session.builder().mode(mode).maxSessions(maxSessions)
                            .maxSessionsPerSubject(perSubject).build())
                    .build();
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("Should refuse a bound below one")
        void shouldRefuseBoundBelowOne(int perSubject) {
            assertEquals(1, refusals(OidcConfig.Session.MODE_SERVER, null, perSubject).size(),
                    "a subject must be able to hold at least one session");
        }

        @Test
        @DisplayName("Should refuse a bound above the declared max_sessions")
        void shouldRefuseBoundAboveDeclaredMaxSessions() {
            assertEquals(1, refusals(OidcConfig.Session.MODE_SERVER, 5, 6).size(),
                    "a per-subject bound above the store bound could never take effect");
        }

        @Test
        @DisplayName("Should refuse a bound above the default max_sessions when that key is omitted")
        void shouldRefuseBoundAboveDefaultMaxSessions() {
            assertAll("the upper bound is the effective store bound of 10 000",
                    () -> assertEquals(1, refusals(OidcConfig.Session.MODE_SERVER, null, 10_001).size()),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, null, 10_000),
                            "control: the store bound itself is accepted"));
        }

        @Test
        @DisplayName("Should accept a bound from one up to max_sessions, and an omitted bound")
        void shouldAcceptBoundInsideTheRange() {
            assertAll("accepted in server mode",
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, null, 1)),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, 5, 5),
                            "equal to max_sessions switches the bound off and is allowed"),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, 3, null),
                            "omitted beside a small max_sessions"),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, null, null)));
        }

        @Test
        @DisplayName("Should refuse the key in cookie mode, where no store exists to apply it")
        void shouldRefuseTheKeyInCookieMode() {
            assertAll("cookie mode",
                    () -> assertEquals(1, refusals(OidcConfig.Session.MODE_COOKIE, null, 1).size(),
                            "a declared bound that nothing would enforce is refused at boot"),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_COOKIE, null, null),
                            "an omitted key leaves cookie mode as it was"),
                    () -> assertEquals(List.of(), refusals(OidcConfig.Session.MODE_SERVER, null, 1),
                            "control: the same value is accepted in server mode"));
        }
    }

    @Nested
    @DisplayName("Each enforced rule")
    class RuleViolations {

        @Test
        @DisplayName("Should accept cookie session mode without a key_file (generate-on-startup)")
        void shouldAcceptCookieSessionWithoutKeyFile() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("cookie")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "omitting key_file selects the generate-on-startup key mode, got: " + errors);
        }

        @Test
        @DisplayName("Should accept cookie session mode with a key_file (provided-key-file mode)")
        void shouldAcceptCookieSessionWithKeyFile() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("cookie")
                                    .keyFile("/run/keys/session.key")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "naming the one sealing key file selects the provided-key-file mode, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse a key_file declared in server session mode, which never reads it")
        void shouldRefuseKeyFileInServerSessionMode() {
            String declaredPath = "/run/keys/never-read-session.key";
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("server")
                                    .store("memory")
                                    .keyFile(declaredPath)
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/key_file", "declared in server session mode");
            assertAll(
                    () -> assertTrue(errors.stream()
                                    .filter(e -> "/oidc/session/key_file".equals(e.pointer()))
                                    .allMatch(e -> e.message().contains("remove the key, or use cookie session mode")),
                            () -> "the refusal must name the remedy, got: " + errors),
                    () -> assertTrue(errors.stream().noneMatch(e -> e.message().contains(declaredPath)),
                            () -> "the refusal must name the key, never the declared path, got: " + errors));
        }

        @Test
        @DisplayName("Should accept server session mode without a key_file")
        void shouldAcceptServerSessionWithoutKeyFile() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("server")
                                    .store("memory")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "THE CONTROL for the server-mode key_file refusal: the same document without the key "
                            + "must pass, got: " + errors);
        }

        @Test
        @DisplayName("Should reject server session mode without a store")
        void shouldRejectServerSessionWithoutStore() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("server")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
        }

        @Test
        @DisplayName("Should not apply the server-mode store rule to a mixed-case cookie mode value")
        void shouldNotApplyServerRuleToMixedCaseCookieMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("  CoOkIe ")
                                    .keyFile("/run/keys/session.key")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("/oidc/session/")),
                    () -> "the canonicalized value is cookie mode, which holds no server-side store, got: " + errors);
        }

        @Test
        @DisplayName("Should apply the server-mode companion rule to a mixed-case mode value")
        void shouldApplyServerRuleToMixedCaseMode() {
            GatewayConfig gateway = validGateway()
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode("SERVER")
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/oidc/session/store", "server session mode requires a store");
        }
    }

    @Nested
    @DisplayName("The BFF OIDC/session fold rules (D1)")
    class BffOidcFoldRules {

        private static GatewayConfig gatewayWithOidc(OidcConfig oidc) {
            return validGateway().oidc(oidc).build();
        }

        @ParameterizedTest(name = "max_sessions = {0} is rejected")
        @ValueSource(ints = {0, -1, -1000})
        @DisplayName("Should reject a non-positive session max_sessions bound")
        void shouldRejectNonPositiveMaxSessions(int maxSessions) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().maxSessions(maxSessions).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/session/max_sessions", "must be a positive integer");
        }

        @Test
        @DisplayName("Should accept a positive session max_sessions bound")
        void shouldAcceptPositiveMaxSessions() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().maxSessions(10000).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is rejected")
        @ValueSource(ints = {0, -1, 39, 8193, 65536})
        @DisplayName("Should reject a session max_cookie_size outside the viable budget bounds")
        void shouldRejectOutOfBoundsMaxCookieSize(int maxCookieSize) {
            // Arrange — below the floor no sealed value could ever be emitted; above the ceiling the
            // derived pre-route Cookie cap would exceed what the transport carries.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertHasError(errors, "/oidc/session/max_cookie_size", "must be between");
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is accepted")
        @ValueSource(ints = {40, 4096, 8192})
        @DisplayName("Should accept a session max_cookie_size on and inside the viable budget bounds")
        void shouldAcceptInBoundsMaxCookieSize(int maxCookieSize) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        /**
         * A 48-byte {@code session.cookie_name} — more than double the 22-byte default — used to
         * pin the derived threshold against a configured name. Nothing in the schema bounds the
         * length, so this is an ordinary configuration rather than an extreme one.
         */
        private static final String LONG_COOKIE_NAME = "__Host-a-considerably-longer-session-cookie-name";

        /**
         * Matched positive/negative controls straddling the browser-safe <em>value</em> budget
         * <em>under the default configuration</em>
         * ({@code SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET}, 4019 = the 4096-byte
         * RFC 6265 6.1 header guarantee less the 77 bytes of cookie name and attributes the codec
         * emits for the default name and a four-digit {@code Max-Age}), every case inside the
         * accepted {@code 40..8192} range so the boot never fails and the warning is the only
         * variable. The sibling rows below vary the name and the TTL, where the threshold moves.
         * <p>
         * <strong>Both legs are required.</strong> The fire-only cases alone would pass against a
         * validator that warned unconditionally — which is the failure this record exists to avoid,
         * since a boot warning on every cookie-mode gateway is a warning operators learn to ignore.
         * The silent cases are what pin the threshold, and 4019 / 4020 straddle it exactly, so
         * moving the comparison off {@code BROWSER_SAFE_COOKIE_VALUE_BUDGET} in either direction —
         * or flipping it from {@code >} to {@code >=} — turns at least one case red.
         * <p>
         * <strong>4096 is a firing case, and that is the regression control.</strong> 4096 was once
         * the shipped default value budget, and comparing against it left the band
         * {@code 4020..4096} silent while the gateway emitted a {@code Set-Cookie} header past the
         * guarantee — the same silent, browser-side, unobservable drop the record exists to
         * announce. The default has since moved to the browser-safe 4019, but the row stays: a
         * validator that reintroduced a 4096 comparison would turn it green again, and this is what
         * keeps that impossible. It is now an ordinary declared budget like any other in the range.
         */
        static Stream<Arguments> browserGuaranteeThresholdControls() {
            return Stream.of(
                    Arguments.of("the floor is far below the browser guarantee", 40, false),
                    Arguments.of("one byte below the browser-safe value budget", 4018, false),
                    Arguments.of("exactly the browser-safe value budget", 4019, false),
                    Arguments.of("one byte above the browser-safe value budget", 4020, true),
                    Arguments.of("the retired 4096 default derives an over-guarantee header", 4096, true),
                    Arguments.of("the validated ceiling is above the guarantee", 8192, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1})")
        @MethodSource("browserGuaranteeThresholdControls")
        @DisplayName("Should warn only when the header derived from an accepted max_cookie_size exceeds the browser guarantee")
        void shouldWarnOnlyAboveTheBrowserGuarantee(String label, int maxCookieSize, boolean expectWarning) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a budget inside 40..8192 must be warned about, never refused, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize,
                        maxCookieSize + SealedSessionCookieCodec.DEFAULT_SET_COOKIE_HEADER_OVERHEAD);
            } else {
                assertNoBudgetWarning(maxCookieSize
                        + " derives a header inside the browser guarantee and must not warn");
            }
        }

        /**
         * The five-digit-{@code Max-Age} leg of the derived threshold. {@code toSetCookieHeader}
         * writes the <em>configured</em> lifetime into {@code Max-Age}, so a TTL past 9999 seconds
         * costs one more header byte than the default-configuration figure of 77 assumes: the
         * overhead becomes 78 and the browser-safe value budget 4018, not 4019.
         * <p>
         * <strong>4019 is the discriminating case.</strong> It is exactly the default-configuration
         * threshold and stays silent there, so a validator still comparing against a fixed 4019
         * passes the default row above and fails this one — which is the whole content of the
         * finding: the constant was standing in for a configurable quantity.
         */
        static Stream<Arguments> fiveDigitTtlThresholdControls() {
            return Stream.of(
                    Arguments.of("one byte below the TTL-widened browser-safe budget", 4017, false),
                    Arguments.of("exactly the TTL-widened browser-safe budget", 4018, false),
                    Arguments.of("the default-configuration threshold is already over-budget here", 4019, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1}, ttl_seconds = 86400)")
        @MethodSource("fiveDigitTtlThresholdControls")
        @DisplayName("Should widen the derived header by the Max-Age digits a five-digit ttl_seconds emits")
        void shouldDeriveTheThresholdFromTheResolvedTtl(String label, int maxCookieSize, boolean expectWarning) {
            // Arrange — 86400 seconds is five Max-Age digits against the default's four, so the
            // overhead is 22 name bytes + '=' + "; Max-Age=" + 5 digits + 40 attribute bytes = 78.
            // Every figure here is spelled independently of the production derivation on purpose.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .ttlSeconds(86_400)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize, maxCookieSize + 78);
            } else {
                assertNoBudgetWarning(maxCookieSize + " fits the 4096-byte guarantee at a 78-byte overhead");
            }
        }

        /**
         * The long-cookie-name leg of the derived threshold, and the one with no ceiling on it:
         * {@code session.cookie_name} is an unrestricted schema string, so the deviation from the
         * default 22-byte name is unbounded. The name below is 48 bytes, putting the overhead at 103
         * and the browser-safe value budget at 3993 — well under the default-configuration 4019,
         * which therefore has to warn here.
         */
        static Stream<Arguments> longCookieNameThresholdControls() {
            return Stream.of(
                    Arguments.of("one byte below the name-widened browser-safe budget", 3992, false),
                    Arguments.of("exactly the name-widened browser-safe budget", 3993, false),
                    Arguments.of("one byte above the name-widened browser-safe budget", 3994, true),
                    Arguments.of("the default-configuration threshold is already over-budget here", 4019, true));
        }

        @ParameterizedTest(name = "{0} (max_cookie_size = {1})")
        @MethodSource("longCookieNameThresholdControls")
        @DisplayName("Should widen the derived header by the configured cookie name's length")
        void shouldDeriveTheThresholdFromTheResolvedCookieName(String label, int maxCookieSize,
                boolean expectWarning) {
            // Arrange — 48 name bytes + '=' + "; Max-Age=" + 4 digits + 40 attribute bytes = 103.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .cookieName(LONG_COOKIE_NAME)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertEquals(48, LONG_COOKIE_NAME.length(),
                    "the arithmetic in this row depends on the fixture name's length");
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            if (expectWarning) {
                assertBudgetWarning(maxCookieSize, maxCookieSize + 103);
            } else {
                assertNoBudgetWarning(maxCookieSize + " fits the 4096-byte guarantee at a 103-byte overhead");
            }
        }

        /**
         * The omitted-key control pair — the path this guard was blind to.
         * <p>
         * The gateway emits the same {@code Set-Cookie} whether the operator wrote
         * {@code max_cookie_size} down or left it defaulted, so the check reads the <em>effective</em>
         * budget. It used to return early on an absent key, which meant the single most common
         * cookie-mode configuration — declare nothing — was never examined at all. With the default
         * then at 4096, every one of those gateways emitted a header 77 bytes past the guarantee and
         * nothing anywhere said so.
         * <p>
         * <strong>Both legs are required, and they fail for different reasons.</strong> The silent
         * leg fails if the default is ever moved back above the browser-safe budget, or if the
         * comparison is made unconditional — a warning on every default cookie-mode gateway is the
         * outcome that teaches operators to ignore the record. The firing leg fails if the early
         * return on an omitted key is ever restored: the budget is identical in both rows and only
         * the cookie name differs, so nothing but reading the effective budget can tell them apart.
         */
        @Test
        @DisplayName("Should not warn when max_cookie_size is omitted under the default cookie name")
        void shouldNotWarnOnOmittedBudgetUnderTheDefaultName() {
            // Arrange — no maxCookieSize at all: the shipped default (4019) applies, and under the
            // default 22-byte name plus a four-digit Max-Age the emitted header is 4019 + 77 = 4096,
            // exactly ON the guarantee rather than past it.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
            assertNoBudgetWarning("the shipped default lands exactly on the browser guarantee under "
                    + "the default cookie name, and warning here would fire on every cookie-mode gateway");
        }

        @Test
        @DisplayName("Should warn when max_cookie_size is omitted under a longer cookie name")
        void shouldWarnOnOmittedBudgetUnderALongerCookieName() {
            // Arrange — the SAME omitted budget as the row above. Only the 48-byte cookie name
            // differs, widening the overhead to 103 so the default budget derives a 4122-byte header.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_COOKIE)
                            .cookieName(LONG_COOKIE_NAME).build())
                    .build());

            // Act
            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Assert
            assertTrue(errors.isEmpty(),
                    () -> "an undeliverable default is warned about, never refused, got: " + errors);
            assertBudgetWarning(SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET,
                    SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET + 103);
        }

        /**
         * Outside {@code session.mode: cookie} the gateway emits no sealed session
         * {@code Set-Cookie} at all, so there is no header for the browser guarantee to govern and
         * the record would be noise about a cookie that does not exist. The budget used here (8192)
         * is the validated ceiling — the loudest firing case in cookie mode — so a validator that
         * dropped the mode guard turns every row red.
         */
        @ParameterizedTest(name = "session.mode = {0} emits no browser-guarantee warning")
        @ValueSource(strings = {OidcConfig.Session.MODE_SERVER, "", "bearer-only"})
        @DisplayName("Should not warn about the browser guarantee outside cookie mode")
        void shouldNotWarnOutsideCookieMode(String mode) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(mode)
                            .maxCookieSize(SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            // Scoped to this key: server mode brings its own companion rules (a store is required),
            // which are not what this row is about.
            assertTrue(errors.stream().noneMatch(error -> "/oidc/session/max_cookie_size".equals(error.pointer())),
                    () -> "an in-range budget is never refused, whatever the mode, got: " + errors);
            assertNoBudgetWarning("mode '" + mode + "' emits no session Set-Cookie to warn about");
        }

        @ParameterizedTest(name = "max_cookie_size = {0} is still range-checked outside cookie mode")
        @ValueSource(ints = {39, 8193})
        @DisplayName("Should range-check an explicit max_cookie_size in every mode, not only cookie mode")
        void shouldRangeCheckMaxCookieSizeOutsideCookieMode(int maxCookieSize) {
            // Only the WARNING is cookie-mode scoped: an out-of-range value is a misconfiguration
            // whatever the mode, and refusing it at boot is what stops it going live the moment the
            // mode is switched to cookie.
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_SERVER)
                            .maxCookieSize(maxCookieSize).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/session/max_cookie_size", "must be between");
        }

        private void assertBudgetWarning(int expectedBudget, int expectedHeaderBytes) {
            String identifier = ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE
                    .resolveIdentifierString();
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(expectedBudget));
            // The DERIVED header size is what the guarantee governs, so the message must name it
            // — a template carrying only the value budget is the drift these controls pin.
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    String.valueOf(expectedHeaderBytes));
            // The remedy figure has to be this gateway's browser-safe budget, not a fixed 4019.
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(
                    SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE
                            - (expectedHeaderBytes - expectedBudget)));
        }

        private void assertNoBudgetWarning(String because) {
            String identifier = ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE
                    .resolveIdentifierString();
            assertTrue(TestLoggerFactory.getTestHandler()
                            .resolveLogMessagesContaining(TestLogLevel.WARN, identifier).isEmpty(),
                    () -> because + ", so " + identifier + " must not be emitted");
        }
    }

    /**
     * The {@code oidc.session.cookie_name} refusal: a declared name must keep the {@code __Host-}
     * guarantee, be a non-empty RFC 9110 token, and not collide with a gateway-owned cookie.
     * <p>
     * The accepted rows are the matched controls for the refused ones — a validator that refused
     * every declared name would pass the refusal rows alone. Every row runs in both session modes,
     * because both emit the configured name. The names are literals on purpose: each one IS the
     * boundary it pins, so a generated value could not stand in for it.
     */
    @Nested
    @DisplayName("The session cookie_name __Host- refusal")
    class SessionCookieNameRefusal {

        private static final String COOKIE_NAME_POINTER = "/oidc/session/cookie_name";
        private static final String PREFIX_FAILURE = "does not start with the case-sensitive '__Host-' prefix";
        private static final String TOKEN_FAILURE = "is not a non-empty RFC 9110 token";
        private static final String COLLISION_FAILURE = "collides with the gateway-owned cookie";

        private static final List<String> SESSION_MODES =
                List.of(OidcConfig.Session.MODE_COOKIE, OidcConfig.Session.MODE_SERVER);

        private static GatewayConfig gatewayWithCookieName(String mode, @Nullable String cookieName) {
            return validGateway().oidc(OidcConfig.builder()
                    .session(OidcConfig.Session.builder().mode(mode).cookieName(cookieName).build())
                    .build()).build();
        }

        private static List<ConfigError> cookieNameErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> COOKIE_NAME_POINTER.equals(error.pointer())).toList();
        }

        private static Stream<Arguments> inEveryMode(Stream<Arguments> rows) {
            List<Arguments> materialized = rows.toList();
            return SESSION_MODES.stream().flatMap(mode -> materialized.stream()
                    .map(row -> {
                        Object[] values = row.get();
                        Object[] withMode = new Object[values.length + 1];
                        withMode[0] = mode;
                        System.arraycopy(values, 0, withMode, 1, values.length);
                        return Arguments.of(withMode);
                    }));
        }

        static Stream<Arguments> acceptedCookieNames() {
            return inEveryMode(Stream.of(
                    Arguments.of("the omitted key resolves to the default", null),
                    Arguments.of("the default name", SessionCookieCodec.DEFAULT_COOKIE_NAME),
                    Arguments.of("a custom well-formed __Host- name", "__Host-orders-session")));
        }

        /**
         * Each refused name with the failure fragment the refusal must name and the rendering the
         * message must echo. The rendering is spelled out independently of the production escaping:
         * CR and LF become {@code \\u000D} / {@code \\u000A}, everything printable is echoed as typed.
         */
        static Stream<Arguments> refusedCookieNames() {
            return inEveryMode(Stream.of(
                    Arguments.of("missing prefix", "sheriff-session", PREFIX_FAILURE, "sheriff-session"),
                    Arguments.of("wrong-case prefix", "__host-x", PREFIX_FAILURE, "__host-x"),
                    Arguments.of("__Secure- prefix", "__Secure-sheriff-session", PREFIX_FAILURE,
                            "__Secure-sheriff-session"),
                    Arguments.of("prefix alone", "__Host-", "is the bare '__Host-' prefix", "__Host-"),
                    Arguments.of("empty", "", TOKEN_FAILURE, ""),
                    Arguments.of("blank", "   ", TOKEN_FAILURE, "   "),
                    Arguments.of("carrying ';'", "__Host-a;b", TOKEN_FAILURE, "__Host-a;b"),
                    Arguments.of("carrying a space", "__Host-a b", TOKEN_FAILURE, "__Host-a b"),
                    Arguments.of("carrying CR/LF", "__Host-a\r\nSet-Cookie: x", TOKEN_FAILURE,
                            "__Host-a\\u000D\\u000ASet-Cookie: x"),
                    Arguments.of("the binding cookie name", BindingCookieCodec.COOKIE_NAME, COLLISION_FAILURE,
                            BindingCookieCodec.COOKIE_NAME),
                    Arguments.of("the logout-state cookie name", RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME,
                            COLLISION_FAILURE, RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME)));
        }

        @ParameterizedTest(name = "[{0}] {1} is accepted")
        @MethodSource("acceptedCookieNames")
        @DisplayName("Should accept an omitted, default or well-formed __Host- session cookie name")
        void shouldAcceptSafeCookieName(String mode, String label, @Nullable String cookieName) {
            GatewayConfig gateway = gatewayWithCookieName(mode, cookieName);

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(cookieNameErrors(errors).isEmpty(),
                    () -> label + " must not be refused in mode '" + mode + "', got: " + errors);
        }

        @ParameterizedTest(name = "[{0}] {1} is refused")
        @MethodSource("refusedCookieNames")
        @DisplayName("Should refuse a session cookie name that drops the __Host- guarantee, is no token, or collides")
        void shouldRefuseUnsafeCookieName(String mode, String label, String cookieName, String failure,
                String expectedRendering) {
            GatewayConfig gateway = gatewayWithCookieName(mode, cookieName);

            List<ConfigError> refusals = cookieNameErrors(validator.validate(gateway, List.of(), topologyWith()));

            assertEquals(1, refusals.size(),
                    () -> label + " must yield exactly one cookie_name violation in mode '" + mode + "', got: "
                            + refusals);
            String message = refusals.getFirst().message();
            assertAll(label,
                    () -> assertEquals("gateway.yaml", refusals.getFirst().file()),
                    () -> assertTrue(message.contains(failure),
                            () -> "expected the failure '" + failure + "' in: " + message),
                    () -> assertTrue(message.contains("'" + expectedRendering + "'"),
                            () -> "expected the escaped echo '" + expectedRendering + "' in: " + message),
                    () -> assertTrue(message.chars().noneMatch(character -> character < 0x20),
                            () -> "the echoed value must carry no control character: " + message));
        }
    }

    /**
     * The session idle-timeout rule, in both session modes: a declared
     * {@code oidc.session.idle_timeout_seconds} below {@code 1} or above the effective
     * {@code ttl_seconds} refuses the boot, and an omitted one is accepted under any {@code ttl_seconds}.
     * <p>
     * Every case reads the refusals at the key's own pointer through the default rule set, so the group
     * goes red when the rule is taken out of the validator. The numbers are literals: each is the
     * boundary the rule states.
     */
    @Nested
    @DisplayName("oidc.session.idle_timeout_seconds")
    class SessionIdleTimeout {

        private static final String POINTER = "/oidc/session/idle_timeout_seconds";

        private List<ConfigError> idleTimeoutRefusals(String mode, @Nullable Integer ttlSeconds,
                @Nullable Integer idleTimeoutSeconds) {
            OidcConfig.Session session = OidcConfig.Session.builder().mode(mode).ttlSeconds(ttlSeconds)
                    .idleTimeoutSeconds(idleTimeoutSeconds).build();
            GatewayConfig gateway = validGateway().oidc(OidcConfig.builder().session(session).build()).build();
            return validator.validate(gateway, List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(strings = {OidcConfig.Session.MODE_SERVER, OidcConfig.Session.MODE_COOKIE})
        @DisplayName("Should refuse a declared idle timeout above the effective ttl_seconds")
        void shouldRefuseAnIdleTimeoutAboveTheTtl(String mode) {
            List<ConfigError> refusals = idleTimeoutRefusals(mode, 600, 601);

            assertEquals(1, refusals.size(), () -> "exactly one refusal at the key, got: " + refusals);
            assertTrue(refusals.getFirst().message().contains("idle_timeout_seconds 601 exceeds the effective ttl_seconds 600"),
                    () -> refusals.getFirst().message());
        }

        @Test
        @DisplayName("Should compare a declared idle timeout with the default ttl_seconds when that is omitted")
        void shouldCompareWithTheDefaultTtl() {
            List<ConfigError> aboveTheDefault = idleTimeoutRefusals(OidcConfig.Session.MODE_SERVER, null, 3601);
            List<ConfigError> atTheDefault = idleTimeoutRefusals(OidcConfig.Session.MODE_SERVER, null, 3600);

            assertAll(
                    () -> assertEquals(1, aboveTheDefault.size(), () -> "3601 exceeds the default 3600: " + aboveTheDefault),
                    () -> assertEquals(List.of(), atTheDefault, "a value equal to the effective ttl is in range"));
        }

        @ParameterizedTest(name = "idle_timeout_seconds: {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Should refuse a non-positive declared idle timeout")
        void shouldRefuseANonPositiveIdleTimeout(int nonPositive) {
            List<ConfigError> refusals = idleTimeoutRefusals(OidcConfig.Session.MODE_SERVER, 3600, nonPositive);

            assertEquals(1, refusals.size(), () -> "exactly one refusal at the key, got: " + refusals);
            assertTrue(refusals.getFirst().message().contains("must be at least 1, but was " + nonPositive),
                    () -> refusals.getFirst().message());
        }

        @ParameterizedTest(name = "ttl_seconds: {0}")
        @ValueSource(ints = {1, 60, 600, 1800, 3600, 86400})
        @DisplayName("Should accept an omitted idle timeout under any ttl_seconds")
        void shouldAcceptAnOmittedIdleTimeout(int ttlSeconds) {
            assertEquals(List.of(), idleTimeoutRefusals(OidcConfig.Session.MODE_SERVER, ttlSeconds, null),
                    "an omitted key resolves to a value inside the range by construction");
        }

        @Test
        @DisplayName("Should accept a declared idle timeout inside the range, at both boundaries (matched control)")
        void shouldAcceptADeclaredIdleTimeoutInsideTheRange() {
            assertAll("the refusals above are about the range, not about declaring the key",
                    () -> assertEquals(List.of(), idleTimeoutRefusals(OidcConfig.Session.MODE_SERVER, 600, 1)),
                    () -> assertEquals(List.of(), idleTimeoutRefusals(OidcConfig.Session.MODE_COOKIE, 600, 600)));
        }

        @Test
        @DisplayName("Should record no idle-timeout refusal once the rule is absent from the rule set (the rule is what refuses)")
        void shouldAttributeTheRefusalToTheRule() {
            OidcConfig.Session session = OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_SERVER)
                    .ttlSeconds(600).idleTimeoutSeconds(601).build();
            GatewayConfig gateway = validGateway().oidc(OidcConfig.builder().session(session).build()).build();

            List<ConfigError> withoutAnyRule = new ConfigValidator(List.of()).validate(gateway, List.of(), topologyWith());

            assertEquals(List.of(), withoutAnyRule,
                    "control: the model itself refuses nothing, so the refusal above is the validator rule's");
        }
    }
}
