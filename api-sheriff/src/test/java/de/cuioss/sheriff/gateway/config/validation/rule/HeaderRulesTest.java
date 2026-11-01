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
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code security_headers} rules the {@link ConfigValidator} runs through
 * {@link HeaderRules}: the global-only CORS wildcard-with-credentials refusal (ADR-0007),
 * the header-injection refusal of a {@code content_security_policy} on the global and every anchor
 * block, and the refusal of a {@code header_modes} entry for a header its own block does not enable.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — security_headers rules")
class HeaderRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Nested
    @DisplayName("cors — a wildcard origin must not combine with allow_credentials, on the global block only")
    class CorsWildcardWithCredentials {

        @Test
        @DisplayName("Should reject a wildcard CORS origin combined with allow_credentials")
        void shouldRejectWildcardOriginWithCredentials() {
            GatewayConfig gateway = validGateway()
                    .securityHeaders(SecurityHeadersConfig.builder()
                            .cors(SecurityHeadersConfig.Cors.builder()
                                    .allowedOrigins(List.of("*"))
                                    .allowCredentials(true)
                                    .build())
                            .build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/security_headers/cors", "wildcard origin '*' is not permitted");
        }

        @Test
        @DisplayName("Should check CORS on the global block only — an anchor cannot carry cors (ADR-0007)")
        void shouldCheckCorsOnGlobalBlockOnly() {
            SecurityHeadersConfig wildcardWithCredentials = SecurityHeadersConfig.builder()
                    .cors(SecurityHeadersConfig.Cors.builder()
                            .allowedOrigins(List.of("*"))
                            .allowCredentials(true)
                            .build())
                    .build();
            AnchorConfig anchorWithCors = AnchorConfig.builder()
                    .name("open").pathPrefix("/open").type(AnchorType.PROXY).access(AccessLevel.PUBLIC)
                    .securityHeaders(wildcardWithCredentials)
                    .build();
            GatewayConfig gateway = gatewayWithAnchors(Map.of("open", anchorWithCors));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains("cors")),
                    () -> "CORS is evaluated before route selection, so an anchor block is never a CORS source and "
                            + "the gateway schema refuses one at load; the validator checks the global block only, got: "
                            + errors);
        }
    }

    @Nested
    @DisplayName("content_security_policy — header-injection refusal on the global and every anchor block")
    class ContentSecurityPolicyInjection {

        private static final String GLOBAL_POINTER = "/security_headers/content_security_policy";
        private static final String ANCHOR_POINTER = "/anchors/frontend/security_headers/content_security_policy";
        private static final String VALID_POLICY = "default-src 'self'; img-src 'self' data:";

        private static GatewayConfig gatewayWithPolicies(@Nullable String globalPolicy, @Nullable String anchorPolicy) {
            AnchorConfig frontend = AnchorConfig.builder()
                    .name("frontend")
                    .pathPrefix("/frontend")
                    .type(AnchorType.PROXY)
                    .access(AccessLevel.PUBLIC)
                    .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(anchorPolicy).build())
                    .build();
            return validGateway()
                    .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(globalPolicy).build())
                    .anchors(Map.of(frontend.name(), frontend))
                    .build();
        }

        private static List<ConfigError> policyErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.pointer().endsWith("/content_security_policy")).toList();
        }

        @ParameterizedTest(name = "global value #{index}")
        @ValueSource(strings = {"default-src 'self'\r\nSet-Cookie: session=forged", "default-src 'self'\nX-Injected: 1",
                "default-src 'self'\rX-Injected: 1", "default-src\0'self'", "default-src\t'self'",
                "default-src\177'self'", "   "})
        @DisplayName("Should refuse a global policy that is blank or carries a CR, LF or other control character")
        void shouldRefuseInjectingGlobalPolicy(String policy) {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies(policy, null), List.of(), topologyWith());

            assertHasError(errors, GLOBAL_POINTER, "without CR, LF or other control characters");
        }

        @ParameterizedTest(name = "anchor value #{index}")
        @ValueSource(strings = {"default-src 'self'\r\nSet-Cookie: session=forged", "default-src 'self'\nX-Injected: 1",
                "default-src\0'self'", "   "})
        @DisplayName("Should refuse an anchor policy that is blank or carries a control character, naming the anchor block")
        void shouldRefuseInjectingAnchorPolicy(String policy) {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies(VALID_POLICY, policy), List.of(),
                    topologyWith());

            assertAll("only the anchor block is named",
                    () -> assertHasError(errors, ANCHOR_POINTER, "without CR, LF or other control characters"),
                    () -> assertTrue(errors.stream().noneMatch(error -> GLOBAL_POINTER.equals(error.pointer())),
                            () -> "the valid global policy must not be reported, got: " + errors));
        }

        @Test
        @DisplayName("Should never echo a raw line terminator of the refused value into the error message")
        void shouldNotEchoRawLineTerminator() {
            List<ConfigError> errors = validator.validate(
                    gatewayWithPolicies("default-src 'self'\r\nSet-Cookie: session=forged", null), List.of(),
                    topologyWith());

            assertAll("the refusal message stays single-line (CWE-117)",
                    policyErrors(errors).stream().map(error -> () -> assertFalse(
                            error.message().contains("\r") || error.message().contains("\n"),
                            () -> "the message must not carry a raw line terminator: " + error.message())));
        }

        @Test
        @DisplayName("Should collect a global and an anchor violation together rather than stopping at the first")
        void shouldCollectGlobalAndAnchorViolationsTogether() {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies("a\nb", "c\rd"), List.of(),
                    topologyWith());

            List<String> pointers = policyErrors(errors).stream().map(ConfigError::pointer).toList();
            assertAll("both blocks are reported in one pass",
                    () -> assertEquals(2, pointers.size(), () -> "exactly one error per block, got: " + pointers),
                    () -> assertTrue(pointers.containsAll(List.of(GLOBAL_POINTER, ANCHOR_POINTER)),
                            () -> "the global and the anchor block are both named, got: " + pointers));
        }

        @Test
        @DisplayName("Should accept a well-formed policy on both blocks, and an omitted policy on either")
        void shouldAcceptWellFormedOrOmittedPolicies() {
            List<ConfigError> declared = validator.validate(gatewayWithPolicies(VALID_POLICY, VALID_POLICY), List.of(),
                    topologyWith());
            List<ConfigError> omitted = validator.validate(gatewayWithPolicies(null, null), List.of(), topologyWith());

            assertAll("a control-character-free policy, or none, raises no content_security_policy error",
                    () -> assertTrue(policyErrors(declared).isEmpty(), () -> "got: " + declared),
                    () -> assertTrue(policyErrors(omitted).isEmpty(), () -> "got: " + omitted));
        }
    }

    @Nested
    @DisplayName("header_modes — a mode may only name a header its own block enables")
    class HeaderModesOrphanRefusal {

        private static final String GLOBAL_MODES = "/security_headers/header_modes/";
        private static final String ANCHOR_MODES = "/anchors/frontend/security_headers/header_modes/";

        private static List<ConfigError> modeErrors(List<ConfigError> errors) {
            return errors.stream().filter(error -> error.pointer().contains("/header_modes/")).toList();
        }

        private static GatewayConfig gatewayWithBlocks(SecurityHeadersConfig global,
                @Nullable SecurityHeadersConfig anchorBlock) {
            AnchorConfig frontend = AnchorConfig.builder()
                    .name("frontend")
                    .pathPrefix("/frontend")
                    .type(AnchorType.PROXY)
                    .access(AccessLevel.PUBLIC)
                    .securityHeaders(anchorBlock)
                    .build();
            return validGateway().securityHeaders(global).anchors(Map.of(frontend.name(), frontend)).build();
        }

        private static SecurityHeadersConfig.HeaderModes allDefault() {
            SecurityHeadersConfig.HeaderMode mode = SecurityHeadersConfig.HeaderMode.DEFAULT;
            return new SecurityHeadersConfig.HeaderModes(mode, mode, mode, mode);
        }

        @Test
        @DisplayName("Should refuse every mode naming a header the global block does not enable, one error per key")
        void shouldRefuseEveryOrphanModeOnTheGlobalBlock() {
            SecurityHeadersConfig global = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(false)
                    .headerModes(allDefault())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(global, null), List.of(), topologyWith());

            assertAll("an absent hsts / csp and a false nosniff / absent frame_deny are all orphans",
                    () -> assertEquals(4, modeErrors(errors).size(), () -> "got: " + errors),
                    () -> assertHasError(errors, GLOBAL_MODES + "hsts", "does not enable hsts"),
                    () -> assertHasError(errors, GLOBAL_MODES + "content_type_nosniff",
                            "does not enable content_type_nosniff"),
                    () -> assertHasError(errors, GLOBAL_MODES + "frame_deny", "does not enable frame_deny"),
                    () -> assertHasError(errors, GLOBAL_MODES + "content_security_policy",
                            "does not enable content_security_policy"));
        }

        @Test
        @DisplayName("Should judge an anchor block's modes against that anchor block, not against the global block")
        void shouldJudgeAnchorModesAgainstTheAnchorBlock() {
            SecurityHeadersConfig global = SecurityHeadersConfig.builder().frameDeny(true).build();
            SecurityHeadersConfig anchorBlock = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(true)
                    .headerModes(SecurityHeadersConfig.HeaderModes.builder()
                            .frameDeny(SecurityHeadersConfig.HeaderMode.DEFAULT).build())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(global, anchorBlock), List.of(),
                    topologyWith());

            assertAll("the global frame_deny does not legitimise the anchor's frame_deny mode (wholesale replacement)",
                    () -> assertEquals(1, modeErrors(errors).size(), () -> "got: " + errors),
                    () -> assertHasError(errors, ANCHOR_MODES + "frame_deny", "does not enable frame_deny"));
        }

        @Test
        @DisplayName("Should accept a mode for every header its block enables, on the global and an anchor block")
        void shouldAcceptModesForEnabledHeaders() {
            SecurityHeadersConfig enabledAll = SecurityHeadersConfig.builder()
                    .hsts(new SecurityHeadersConfig.Hsts(31536000, true))
                    .contentTypeNosniff(true)
                    .frameDeny(true)
                    .contentSecurityPolicy("default-src 'self'")
                    .headerModes(allDefault())
                    .build();

            List<ConfigError> errors = validator.validate(gatewayWithBlocks(enabledAll, enabledAll), List.of(),
                    topologyWith());

            assertTrue(modeErrors(errors).isEmpty(), () -> "no mode is an orphan, got: " + errors);
        }
    }
}
