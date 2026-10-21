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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.PortalRules;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigValidator}: one negative case per enforced cross-cutting
 * rule (the anchor rules, namespace coverage among them, are tested in {@code AnchorRulesTest}, the
 * auth and OIDC rules in {@code AuthRulesTest}, {@code OidcClientRulesTest} and
 * {@code OidcSessionRulesTest}, and the topology rules in {@code TopologyRulesTest}), the
 * structural {@code TRACE}/{@code CONNECT} rejection, and the single-pass aggregation contract that reports
 * every violation together rather than stopping at the first.
 */
@EnableGeneratorController
class ConfigValidatorTest {

    /**
     * The part of a reserved-path refusal that names the query and fragment restriction. The three
     * reserved-path keys share one rule, so the groups testing them share this expectation.
     */
    private static final String QUERY_OR_FRAGMENT_REFUSAL = "carrying no query ('?') and no fragment ('#')";

    /**
     * The part of a reserved-path refusal that names the backslash, control-character and whitespace
     * restriction, shared by the groups testing the three reserved-path keys.
     */
    private static final String BACKSLASH_CONTROL_OR_WHITESPACE_REFUSAL =
            "no backslash ('\\'), no control character and no whitespace";

    /** {@code /\host}: a browser parses the backslash as {@code /} and reads {@code //host}. */
    private static final String BACKSLASH_AUTHORITY_PATH = "/\\evil.example";

    /** A backslash inside an otherwise plain reserved path. */
    private static final String BACKSLASH_SEGMENT_PATH = "/auth\\reserved";

    /** {@code /<TAB>/host}: a browser removes the tab before parsing and reads {@code //host}. */
    private static final String TAB_AUTHORITY_PATH = "/\t/evil.example";

    /** A tab inside an otherwise plain reserved path. */
    private static final String TAB_SEGMENT_PATH = "/auth/re\tserved";

    /** {@code /<LF>/host}: a browser removes the line break before parsing and reads {@code //host}. */
    private static final String LINE_BREAK_AUTHORITY_PATH = "/\n/evil.example";

    /** A plain space inside an otherwise plain reserved path. */
    private static final String SPACE_PATH = "/auth/re served";

    /** A trailing no-break space — a Unicode space separator Java does not class as whitespace. */
    private static final String NO_BREAK_SPACE_PATH = "/auth/reserved ";

    /**
     * A relative reserved path — refused whatever else it holds — that carries a CR/LF and a forged
     * header line, to observe how the refusal echoes it.
     */
    private static final String FORGED_LINE_PATH = "reserved\r\nX-Forged: 1";

    /** How a refusal message renders the CR/LF of {@link #FORGED_LINE_PATH}. */
    private static final String ESCAPED_CARRIAGE_RETURN_LINE_FEED = "\\u000D\\u000A";

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

    /** The single refusal recorded at exactly {@code pointer}; fails when there is none or more than one. */
    private static ConfigError singleRefusalAt(List<ConfigError> errors, String pointer) {
        List<ConfigError> refusals = errors.stream().filter(error -> pointer.equals(error.pointer())).toList();
        assertEquals(1, refusals.size(), () -> "expected exactly one refusal at " + pointer + ", got: " + refusals);
        return refusals.getFirst();
    }

    /**
     * Asserts that a reserved path holding a backslash, a control character or whitespace is refused
     * once at {@code pointer}, that the refusal names that restriction, and that the message echoes no
     * control character raw.
     */
    private static void assertBackslashControlOrWhitespaceRefusal(List<ConfigError> errors, String pointer) {
        String message = singleRefusalAt(errors, pointer).message();

        assertAll(pointer,
                () -> assertTrue(message.contains(BACKSLASH_CONTROL_OR_WHITESPACE_REFUSAL),
                        () -> "the refusal names the backslash, control-character and whitespace restriction: "
                                + message),
                () -> assertTrue(message.chars().noneMatch(Character::isISOControl),
                        () -> "the refusal echoes no control character raw: " + message));
    }

    /**
     * Asserts that the refusal of {@link #FORGED_LINE_PATH} at {@code pointer} renders its CR/LF as
     * escapes and so stays on one log line.
     */
    private static void assertForgedLineEchoedEscaped(List<ConfigError> errors, String pointer) {
        String message = singleRefusalAt(errors, pointer).message();

        assertAll(pointer,
                () -> assertTrue(message.contains(ESCAPED_CARRIAGE_RETURN_LINE_FEED),
                        () -> "the CR/LF is rendered as an escape: " + message),
                () -> assertTrue(message.indexOf('\r') < 0 && message.indexOf('\n') < 0,
                        () -> "the refusal carries no raw line break: " + message));
    }

    private static GatewayConfig gatewayWithAnchors(Map<String, AnchorConfig> anchors) {
        return validGateway().anchors(anchors).build();
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
                "default-src 'self'\rX-Injected: 1", "default-src 'self'", "default-src\t'self'",
                "default-src'self'", "   "})
        @DisplayName("Should refuse a global policy that is blank or carries a CR, LF or other control character")
        void shouldRefuseInjectingGlobalPolicy(String policy) {
            List<ConfigError> errors = validator.validate(gatewayWithPolicies(policy, null), List.of(), topologyWith());

            assertHasError(errors, GLOBAL_POINTER, "without CR, LF or other control characters");
        }

        @ParameterizedTest(name = "anchor value #{index}")
        @ValueSource(strings = {"default-src 'self'\r\nSet-Cookie: session=forged", "default-src 'self'\nX-Injected: 1",
                "default-src 'self'", "   "})
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

        @Test
        @DisplayName("Should accept a millisecond-precision upstream timeout")
        void shouldAcceptMillisecondPrecisionTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(UpstreamConfig.builder().readTimeoutMs(2500).build())
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.NONE))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    () -> "expected no violations for a millisecond-precision timeout, got: " + errors);
        }
    }

    @Nested
    @DisplayName("Each enforced rule")
    class RuleViolations {

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
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), route("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/security_headers/cors", "wildcard origin '*' is not permitted");
        }

        @Test
        @DisplayName("Should check CORS on the global block only — an anchor cannot carry cors (ADR-0007 Amendment A1)")
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
                    .auth(auth(Require.NONE))
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

    @Nested
    @DisplayName("The BFF OIDC/session fold rules (D1)")
    class BffOidcFoldRules {

        private static final String USER_INFO_PATH_POINTER = "/oidc/user_info/path";
        private static final String LOGIN_PATH_POINTER = "/oidc/login/path";

        private static GatewayConfig gatewayWithOidc(OidcConfig oidc) {
            return validGateway().oidc(oidc).build();
        }

        private static List<ConfigError> errorsAt(List<ConfigError> errors, String pointer) {
            return errors.stream().filter(error -> pointer.equals(error.pointer())).toList();
        }

        @Test
        @DisplayName("Should accept a user_info block whose default_view claims all lie within the allowlist")
        void shouldAcceptUserInfoWithDefaultViewWithinAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .allowedClaims(List.of("sub", "name", "roles"))
                            .defaultView(List.of("sub", "name"))
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should accept a user_info block with an empty allowlist as the secure closed default")
        void shouldAcceptUserInfoWithEmptyAllowlistSecureDefault() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "an empty allowlist is the secure closed default and must not be an error, got: " + errors);
        }

        @Test
        @DisplayName("Should reject a default_view claim that lies outside the operator allowlist")
        void shouldRejectDefaultViewClaimOutsideAllowlist() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/session/userinfo")
                            .allowedClaims(List.of("sub", "name"))
                            .defaultView(List.of("sub", "email"))
                            .build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/default_view", "not in allowed_claims");
        }

        @ParameterizedTest(name = "user_info path \"{0}\" is rejected as non-absolute")
        @ValueSource(strings = {"session/userinfo", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject a malformed user_info path that is not an absolute gateway path")
        void shouldRejectNonAbsoluteUserInfoPath(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/user_info/path", "must be an absolute gateway path");
        }

        @ParameterizedTest(name = "user_info path \"{0}\" is rejected for its query or fragment")
        @ValueSource(strings = {"/session/userinfo?x=1", "/session/userinfo#part", "/session/userinfo?",
                "/session/userinfo#"})
        @DisplayName("Should reject a user_info path carrying a query or a fragment, at its own pointer in gateway.yaml")
        void shouldRejectUserInfoPathWithQueryOrFragment(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(path).build())
                    .build());

            List<ConfigError> refusals = errorsAt(validator.validate(gateway, List.of(), topologyWith()),
                    USER_INFO_PATH_POINTER);

            assertEquals(1, refusals.size(), () -> path + " must yield exactly one refusal, got: " + refusals);
            ConfigError refusal = refusals.getFirst();
            assertAll(path,
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(QUERY_OR_FRAGMENT_REFUSAL),
                            () -> "the refusal names the query and fragment restriction: " + refusal.message()));
        }

        @ParameterizedTest(name = "user_info path #{index} is rejected for its backslash, control character or whitespace")
        @ValueSource(strings = {BACKSLASH_AUTHORITY_PATH, BACKSLASH_SEGMENT_PATH, TAB_AUTHORITY_PATH,
                TAB_SEGMENT_PATH, LINE_BREAK_AUTHORITY_PATH, SPACE_PATH, NO_BREAK_SPACE_PATH})
        @DisplayName("Should reject a user_info path carrying a backslash, a control character or whitespace")
        void shouldRejectUserInfoPathWithBackslashControlOrWhitespace(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertBackslashControlOrWhitespaceRefusal(errors, USER_INFO_PATH_POINTER);
        }

        @Test
        @DisplayName("Should refuse a CR/LF in the user_info path without echoing it raw into the boot log")
        void shouldRenderUserInfoPathControlCharactersEscaped() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(FORGED_LINE_PATH).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertForgedLineEchoedEscaped(errors, USER_INFO_PATH_POINTER);
        }

        @ParameterizedTest(name = "user_info path \"{0}\" is accepted")
        @ValueSource(strings = {"/session/userinfo", "/userinfo", "/"})
        @DisplayName("Should accept a plain absolute user_info path")
        void shouldAcceptPlainAbsoluteUserInfoPath(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .userInfo(OidcConfig.UserInfo.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errorsAt(errors, USER_INFO_PATH_POINTER).isEmpty(),
                    () -> path + " carries no query and no fragment and must not be refused, got: " + errors);
        }

        @ParameterizedTest(name = "login path \"{0}\" is rejected for its query or fragment")
        @ValueSource(strings = {"/session/login?x=1", "/session/login#part", "/session/login?", "/session/login#"})
        @DisplayName("Should reject a login path carrying a query or a fragment, at its own pointer in gateway.yaml")
        void shouldRejectLoginPathWithQueryOrFragment(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(path).build())
                    .build());

            List<ConfigError> refusals = errorsAt(validator.validate(gateway, List.of(), topologyWith()),
                    LOGIN_PATH_POINTER);

            assertEquals(1, refusals.size(), () -> path + " must yield exactly one refusal, got: " + refusals);
            ConfigError refusal = refusals.getFirst();
            assertAll(path,
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(QUERY_OR_FRAGMENT_REFUSAL),
                            () -> "the refusal names the query and fragment restriction: " + refusal.message()));
        }

        @ParameterizedTest(name = "login path #{index} is rejected for its backslash, control character or whitespace")
        @ValueSource(strings = {BACKSLASH_AUTHORITY_PATH, BACKSLASH_SEGMENT_PATH, TAB_AUTHORITY_PATH,
                TAB_SEGMENT_PATH, LINE_BREAK_AUTHORITY_PATH, SPACE_PATH, NO_BREAK_SPACE_PATH})
        @DisplayName("Should reject a login path carrying a backslash, a control character or whitespace")
        void shouldRejectLoginPathWithBackslashControlOrWhitespace(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertBackslashControlOrWhitespaceRefusal(errors, LOGIN_PATH_POINTER);
        }

        @Test
        @DisplayName("Should refuse a CR/LF in the login path without echoing it raw into the boot log")
        void shouldRenderLoginPathControlCharactersEscaped() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(FORGED_LINE_PATH).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertForgedLineEchoedEscaped(errors, LOGIN_PATH_POINTER);
        }

        @ParameterizedTest(name = "login path \"{0}\" is accepted")
        @ValueSource(strings = {"/session/login", "/login", "/"})
        @DisplayName("Should accept a plain absolute login path")
        void shouldAcceptPlainAbsoluteLoginPath(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errorsAt(errors, LOGIN_PATH_POINTER).isEmpty(),
                    () -> path + " carries no query and no fragment and must not be refused, got: " + errors);
        }

        @ParameterizedTest(name = "off-path login value \"{0}\" is rejected")
        @ValueSource(strings = {"login", "//evil.example.com", "https://evil.example.com"})
        @DisplayName("Should reject an off-path login value")
        void shouldRejectOffPathLoginValue(String path) {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path(path).build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/oidc/login/path", "must be an absolute gateway path");
        }

        @Test
        @DisplayName("Should accept an absolute login path")
        void shouldAcceptAbsoluteLoginPath() {
            GatewayConfig gateway = gatewayWithOidc(OidcConfig.builder()
                    .login(OidcConfig.Login.builder().path("/session/login").build())
                    .build());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }
    }

    /**
     * The client JWKS path must not be the path of another reserved OIDC endpoint. The registry keeps
     * the first registration for a path and registers the client JWKS path last, so a collision would
     * drop the key-set endpoint without a diagnostic; the rule refuses it instead, naming both keys.
     * It reads the effective path, so the default is covered exactly as a declared path is.
     */
    @Nested
    @DisplayName("oidc.client_authentication.jwks_path must differ from every other reserved OIDC path")
    class ClientJwksPathCollision {

        private static final String POINTER = "/oidc/client_authentication/jwks_path";
        private static final String JWKS_PATH_KEY = "oidc.client_authentication.jwks_path";
        private static final String ORIGIN = "https://gateway.example.com";
        private static final String DEFAULT_JWKS_PATH = "/bff/jwks";
        private static final String DECLARED_JWKS_PATH = "/auth/client-keys";

        /** The seven reserved keys a JWKS path can collide with, each placing a path under its own key. */
        enum ReservedKey {

            REDIRECT_URI("oidc.redirect_uri") {
            @Override
            OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                return oidc.redirectUri(ORIGIN + path);
            }
        },

            LOGOUT_PATH("oidc.logout.path") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.logout(OidcConfig.Logout.builder().path(path).build());
                }
            },

            POST_LOGOUT_REDIRECT_URI("oidc.logout.post_logout_redirect_uri") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.logout(OidcConfig.Logout.builder().postLogoutRedirectUri(ORIGIN + path).build());
                }
            },

            BACKCHANNEL_PATH("oidc.logout.backchannel_path") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.logout(OidcConfig.Logout.builder().backchannelPath(path).build());
                }
            },

            USER_INFO_PATH("oidc.user_info.path") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.userInfo(OidcConfig.UserInfo.builder().path(path).build());
                }
            },

            LOGIN_PATH("oidc.login.path") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.login(OidcConfig.Login.builder().path(path).build());
                }
            },

            STEP_UP_PATH("oidc.step_up.path") {
                @Override
                OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path) {
                    return oidc.stepUp(OidcConfig.StepUp.builder().path(path).build());
                }
            };

            private final String key;

            ReservedKey(String key) {
                this.key = key;
            }

            abstract OidcConfig.OidcConfigBuilder declare(OidcConfig.OidcConfigBuilder oidc, String path);
        }

        private List<ConfigError> jwksPathErrors(OidcConfig oidc) {
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        private static OidcConfig.ClientAuthenticationSettings declaring(String jwksPath) {
            return OidcConfig.ClientAuthenticationSettings.builder().jwksPath(jwksPath).build();
        }

        /** A document declaring all seven other reserved paths, none of them the default JWKS path. */
        private static OidcConfig.OidcConfigBuilder everyOtherReservedPath() {
            return OidcConfig.builder()
                    .redirectUri(ORIGIN + "/auth/callback")
                    .logout(OidcConfig.Logout.builder()
                            .path("/auth/logout")
                            .postLogoutRedirectUri(ORIGIN + "/auth/logout/return")
                            .backchannelPath("/auth/backchannel")
                            .build())
                    .userInfo(OidcConfig.UserInfo.builder().path("/auth/userinfo").build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .stepUp(OidcConfig.StepUp.builder().path("/auth/step-up").build());
        }

        /**
         * What makes "every other reserved key" true, and keeps it true: one enum constant per
         * reserved kind other than the client JWKS path itself. A reserved kind added to the registry
         * fails here until the enum carries the key it is declared under.
         */
        @Test
        @DisplayName("Should cover one colliding key per reserved kind other than the client JWKS path")
        void shouldCoverEveryOtherReservedKind() {
            assertEquals(ReservedPathRegistry.ReservedEndpoint.values().length - 1, ReservedKey.values().length,
                    "one colliding key per reserved kind, the client JWKS path excepted");
            assertEquals(ReservedKey.values().length,
                    ReservedPathRegistry.reservedPaths(everyOtherReservedPath()
                            .clientAuthentication(declaring(DECLARED_JWKS_PATH)).build()).size() - 1,
                    "the admitted fixture declares a distinct path under every one of those keys");
        }

        @Test
        @DisplayName("Should admit the default and a declared JWKS path beside seven distinct reserved paths")
        void shouldAdmitDistinctPaths() {
            assertAll("no reserved path equals the client JWKS path",
                    () -> assertEquals(List.of(), jwksPathErrors(everyOtherReservedPath().build()),
                            "the default path"),
                    () -> assertEquals(List.of(), jwksPathErrors(
                            everyOtherReservedPath().clientAuthentication(declaring(DECLARED_JWKS_PATH)).build()),
                            "a declared path"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReservedKey.class)
        @DisplayName("Should refuse a declared JWKS path that another reserved key names, naming both keys")
        void shouldRefuseADeclaredPathAnotherKeyNames(ReservedKey reservedKey) {
            OidcConfig oidc = reservedKey.declare(OidcConfig.builder(), DECLARED_JWKS_PATH)
                    .clientAuthentication(declaring(DECLARED_JWKS_PATH)).build();

            List<ConfigError> errors = jwksPathErrors(oidc);

            assertEquals(1, errors.size(), () -> "exactly one refusal at " + POINTER + ", got: " + errors);
            assertAll("the refusal names both colliding keys",
                    () -> assertEquals("gateway.yaml", errors.getFirst().file()),
                    () -> assertTrue(errors.getFirst().message().contains(JWKS_PATH_KEY),
                            () -> "the refusal must name " + JWKS_PATH_KEY + ": " + errors.getFirst().message()),
                    () -> assertTrue(errors.getFirst().message().contains(reservedKey.key),
                            () -> "the refusal must name " + reservedKey.key + ": " + errors.getFirst().message()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReservedKey.class)
        @DisplayName("Should refuse another reserved key that names the default JWKS path while no jwks_path is declared")
        void shouldRefuseACollisionWithTheDefaultPath(ReservedKey reservedKey) {
            OidcConfig oidc = reservedKey.declare(OidcConfig.builder(), DEFAULT_JWKS_PATH).build();

            List<ConfigError> errors = jwksPathErrors(oidc);

            assertEquals(1, errors.size(),
                    () -> "the default path is covered exactly as a declared one, got: " + errors);
            assertAll("the refusal names both keys and the default the omitted key resolves to",
                    () -> assertTrue(errors.getFirst().message().contains(JWKS_PATH_KEY)),
                    () -> assertTrue(errors.getFirst().message().contains(reservedKey.key),
                            () -> "the refusal must name " + reservedKey.key + ": " + errors.getFirst().message()),
                    () -> assertTrue(errors.getFirst().message().contains(DEFAULT_JWKS_PATH),
                            () -> "the refusal must name the default: " + errors.getFirst().message()));
        }

        /**
         * The matched control for the default-path refusal: the same {@code logout.path} is admitted
         * once the JWKS path is declared elsewhere, so the refusal above is about the two endpoints
         * sharing a path and not about the literal {@code /bff/jwks}.
         */
        @Test
        @DisplayName("Should admit another key on the default JWKS path once jwks_path is declared elsewhere (matched control)")
        void shouldAdmitTheDefaultPathUnderAnotherKeyOnceJwksPathMoves() {
            OidcConfig oidc = ReservedKey.LOGOUT_PATH.declare(OidcConfig.builder(), DEFAULT_JWKS_PATH)
                    .clientAuthentication(declaring(DECLARED_JWKS_PATH)).build();

            assertEquals(List.of(), jwksPathErrors(oidc));
        }

        @Test
        @DisplayName("Should hold the rule with a client secret configured — the path stays reserved in that mode")
        void shouldRefuseTheCollisionInClientSecretMode() {
            OidcConfig oidc = ReservedKey.LOGIN_PATH.declare(OidcConfig.builder(), DEFAULT_JWKS_PATH)
                    .clientSecret("configured-secret-value").build();

            assertEquals(1, jwksPathErrors(oidc).size(), "the rule does not read the client-authentication mode");
        }

        @Test
        @DisplayName("Should report nothing without an oidc block")
        void shouldReportNothingWithoutOidc() {
            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> POINTER.equals(error.pointer())), errors::toString);
        }
    }

    /**
     * The client JWKS path is registered verbatim and matched by exact equality with the canonical
     * request path. A request that spells a non-canonical path as configured is therefore never
     * answered by the key-set endpoint, and the canonical path it resolves to is left to the route
     * table; the rule refuses such a path at boot instead. What a request can and cannot reach is
     * proven on the request side, in {@code GatewayEdgeRouteBffWiringTest.NonCanonicalClientJwksPath}.
     * <p>
     * The parameterized cases run in both client-authentication modes, because the path is reserved in
     * both. The refused spellings are literals on purpose: each is one specific shape the rule exists to refuse,
     * so the exact string is the contract under test. Each row also names the reason it must be
     * refused for, so a spelling that one check stopped catching cannot pass on another's account.
     */
    @Nested
    @DisplayName("oidc.client_authentication.jwks_path must be a canonical gateway path")
    class ClientJwksPathCanonicalForm {

        private static final String POINTER = "/oidc/client_authentication/jwks_path";
        private static final String JWKS_PATH_KEY = "oidc.client_authentication.jwks_path";
        private static final String ORIGIN = "https://gateway.example.com";
        private static final String DEFAULT_JWKS_PATH = "/bff/jwks";

        /** Each refused spelling with a fragment of the reason it must be refused for. */
        private static final List<List<String>> NON_CANONICAL_PATHS = List.of(
                List.of("/keys/./client", "dot-segment"),
                List.of("/keys/../client", "dot-segment"),
                List.of("/keys/%2e/client", "dot-segment"),
                List.of("/keys//client", "must not contain '//'"),
                List.of("/keys/client;v=1", "matrix parameter"),
                List.of("/keys/%63lient", "percent-encoded character"),
                List.of("/keys/client%20set", "percent-encoded character"),
                List.of("/keys%2Fclient", "percent-encoded '/'"),
                List.of("/keys/client?v=1", "query or a fragment"),
                List.of("/keys/client#set", "query or a fragment"),
                List.of("keys/client", "must start with '/'"));

        /** The two client-authentication modes; the path is reserved, and so judged, in both. */
        enum Mode {

            KEY {
            @Override
            OidcConfig.OidcConfigBuilder select(OidcConfig.OidcConfigBuilder oidc) {
                return oidc;
            }
        },

            CLIENT_SECRET {
                @Override
                OidcConfig.OidcConfigBuilder select(OidcConfig.OidcConfigBuilder oidc) {
                    return oidc.clientSecret("configured-secret-value");
                }
            };

            abstract OidcConfig.OidcConfigBuilder select(OidcConfig.OidcConfigBuilder oidc);
        }

        static Stream<Arguments> nonCanonicalPathsInBothModes() {
            return Stream.of(Mode.values()).flatMap(mode -> NON_CANONICAL_PATHS.stream()
                    .map(row -> Arguments.of(mode, row.getFirst(), row.getLast())));
        }

        static Stream<Arguments> canonicalPathsInBothModes() {
            return Stream.of(Mode.values()).flatMap(mode -> Stream
                    .of("/bff/jwks", "/keys/client.jwks", "/auth/client-keys", "/.well-known/client-jwks.json", "/jwks")
                    .map(path -> Arguments.of(mode, path)));
        }

        private List<ConfigError> jwksPathErrors(OidcConfig.OidcConfigBuilder oidc) {
            return validator.validate(validGateway().oidc(oidc.build()).build(), List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        /** An {@code oidc} block in the given mode that reserves a callback and declares no JWKS path. */
        private static OidcConfig.OidcConfigBuilder inMode(Mode mode) {
            return mode.select(OidcConfig.builder().redirectUri(ORIGIN + "/auth/callback"));
        }

        private static OidcConfig.OidcConfigBuilder declaring(Mode mode, String jwksPath) {
            return inMode(mode).clientAuthentication(
                    OidcConfig.ClientAuthenticationSettings.builder().jwksPath(jwksPath).build());
        }

        @ParameterizedTest(name = "{0}: {1}")
        @MethodSource("nonCanonicalPathsInBothModes")
        @DisplayName("Should refuse a non-canonical path at the jwks_path pointer, for its own reason and without echoing it")
        void shouldRefuseANonCanonicalPath(Mode mode, String path, String reason) {
            List<ConfigError> errors = jwksPathErrors(declaring(mode, path));

            assertEquals(1, errors.size(), () -> "exactly one refusal at " + POINTER + ", got: " + errors);
            ConfigError refusal = errors.getFirst();
            assertAll("the refusal of a non-canonical client JWKS path",
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(JWKS_PATH_KEY),
                            () -> "the refusal must name " + JWKS_PATH_KEY + ": " + refusal.message()),
                    () -> assertTrue(refusal.message().contains("canonical gateway path"),
                            () -> "the refusal must name the defect: " + refusal.message()),
                    () -> assertTrue(refusal.message().contains(
                                    "A request that spells the path as configured would never be answered"),
                            () -> "the consequence is stated for the configured spelling only — a matrix "
                                    + "parameter path is reachable with its ';' percent-encoded: "
                                    + refusal.message()),
                    () -> assertTrue(refusal.message().contains(reason),
                            () -> "the refusal must carry the reason '" + reason + "': " + refusal.message()),
                    () -> assertFalse(refusal.message().contains(path),
                            () -> "the configured value must not be echoed: " + refusal.message()));
        }

        @ParameterizedTest(name = "{0}: {1}")
        @MethodSource("canonicalPathsInBothModes")
        @DisplayName("Should admit the default spelling and an ordinary custom path")
        void shouldAdmitACanonicalPath(Mode mode, String path) {
            assertEquals(List.of(), jwksPathErrors(declaring(mode, path)));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Mode.class)
        @DisplayName("Should admit the default path when jwks_path is omitted, with and without a client_authentication block")
        void shouldAdmitTheDefaultPath(Mode mode) {
            OidcConfig.OidcConfigBuilder noBlock = inMode(mode);
            OidcConfig.OidcConfigBuilder emptyBlock = inMode(mode)
                    .clientAuthentication(OidcConfig.ClientAuthenticationSettings.builder().build());

            assertAll("the default " + DEFAULT_JWKS_PATH + " is canonical",
                    () -> assertEquals(List.of(), jwksPathErrors(noBlock), "no client_authentication block"),
                    () -> assertEquals(List.of(), jwksPathErrors(emptyBlock), "a block that declares no jwks_path"));
        }

        /**
         * The two refusals of the rule are independent: a path that is not canonical and that another
         * reserved key also names is reported for both, in one pass.
         */
        @Test
        @DisplayName("Should report a non-canonical path and its collision together rather than stopping at the first")
        void shouldCollectTheCanonicalAndTheCollisionRefusalTogether() {
            String shared = "/keys//client";
            OidcConfig.OidcConfigBuilder oidc = declaring(Mode.KEY, shared)
                    .logout(OidcConfig.Logout.builder().path(shared).build());

            List<ConfigError> errors = jwksPathErrors(oidc);

            assertAll("both violations of one jwks_path are reported",
                    () -> assertEquals(2, errors.size(), () -> "two refusals at " + POINTER + ", got: " + errors),
                    () -> assertTrue(errors.stream()
                                    .anyMatch(error -> error.message().contains("canonical gateway path")),
                            () -> "the canonical-form refusal is reported, got: " + errors),
                    () -> assertTrue(errors.stream()
                                    .anyMatch(error -> error.message().contains("oidc.logout.path")),
                            () -> "the collision refusal is reported, got: " + errors));
        }
    }

    @Nested
    @DisplayName("oidc.step_up.path must be an absolute gateway path and not collide with the portal")
    class StepUpPath {

        private static final String REDIRECT_URI = "https://gateway.example.com/callback";
        private static final String POINTER = "/oidc/step_up/path";
        private static final String MESSAGE = "must be an absolute gateway path starting with a single '/'";

        private OidcConfig oidcWithStepUpPath(String path) {
            return OidcConfig.builder().redirectUri(REDIRECT_URI)
                    .stepUp(OidcConfig.StepUp.builder().path(path).build()).build();
        }

        private List<ConfigError> validateStepUpPath(String path) {
            return validator.validate(validGateway().oidc(oidcWithStepUpPath(path)).build(), List.of(),
                    topologyWith());
        }

        private boolean refused(List<ConfigError> errors) {
            return errors.stream().anyMatch(error -> POINTER.equals(error.pointer()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/auth/step-up", "/step-up", "/"})
        @DisplayName("Should accept an absolute gateway path")
        void shouldAcceptAbsolutePath(String path) {
            List<ConfigError> errors = validateStepUpPath(path);

            assertFalse(refused(errors), () -> path + " is an absolute gateway path, got: " + errors);
        }

        @ParameterizedTest
        @ValueSource(strings = {"auth/step-up", "step-up", "//evil.example/step-up", "", "   "})
        @DisplayName("Should refuse a relative, scheme-relative or blank path")
        void shouldRefuseNonAbsolutePath(String path) {
            List<ConfigError> errors = validateStepUpPath(path);

            assertHasError(errors, POINTER, MESSAGE);
            assertTrue(errors.stream().filter(error -> POINTER.equals(error.pointer()))
                            .allMatch(error -> "gateway.yaml".equals(error.file())),
                    () -> "the refusal names gateway.yaml, got: " + errors);
        }

        @ParameterizedTest(name = "step_up path \"{0}\" is refused for its query or fragment")
        @ValueSource(strings = {"/auth/step-up?x=1", "/auth/step-up#part", "/auth/step-up?", "/auth/step-up#"})
        @DisplayName("Should refuse a path carrying a query or a fragment, naming the restriction")
        void shouldRefusePathWithQueryOrFragment(String path) {
            List<ConfigError> refusals = validateStepUpPath(path).stream()
                    .filter(error -> POINTER.equals(error.pointer())).toList();

            assertEquals(1, refusals.size(), () -> path + " must yield exactly one refusal, got: " + refusals);
            ConfigError refusal = refusals.getFirst();
            assertAll(path,
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(MESSAGE),
                            () -> "the refusal keeps its leading wording: " + refusal.message()),
                    () -> assertTrue(refusal.message().contains(QUERY_OR_FRAGMENT_REFUSAL),
                            () -> "the refusal names the query and fragment restriction: " + refusal.message()));
        }

        @ParameterizedTest(name = "step_up path #{index} is refused for its backslash, control character or whitespace")
        @ValueSource(strings = {BACKSLASH_AUTHORITY_PATH, BACKSLASH_SEGMENT_PATH, TAB_AUTHORITY_PATH,
                TAB_SEGMENT_PATH, LINE_BREAK_AUTHORITY_PATH, SPACE_PATH, NO_BREAK_SPACE_PATH})
        @DisplayName("Should refuse a path carrying a backslash, a control character or whitespace")
        void shouldRefusePathWithBackslashControlOrWhitespace(String path) {
            List<ConfigError> errors = validateStepUpPath(path);

            assertBackslashControlOrWhitespaceRefusal(errors, POINTER);
        }

        @Test
        @DisplayName("Should refuse a CR/LF in the path without echoing it raw into the boot log")
        void shouldRenderControlCharactersEscaped() {
            List<ConfigError> errors = validateStepUpPath("step-up\r\nX-Forged: 1");

            assertHasError(errors, POINTER, MESSAGE);
            assertTrue(errors.stream().filter(error -> POINTER.equals(error.pointer()))
                            .noneMatch(error -> error.message().indexOf('\n') >= 0
                                    || error.message().indexOf('\r') >= 0),
                    () -> "a control character is escaped in the refusal, got: " + errors);
        }

        @Test
        @DisplayName("Should not refuse an omitted step-up path or step_up block")
        void shouldAcceptOmittedPath() {
            OidcConfig withoutPath = OidcConfig.builder().redirectUri(REDIRECT_URI)
                    .stepUp(new OidcConfig.StepUp(true, true, null)).build();
            OidcConfig withoutBlock = OidcConfig.builder().redirectUri(REDIRECT_URI).build();

            assertAll(
                    () -> assertFalse(refused(validator.validate(validGateway().oidc(withoutPath).build(),
                            List.of(), topologyWith()))),
                    () -> assertFalse(refused(validator.validate(validGateway().oidc(withoutBlock).build(),
                            List.of(), topologyWith()))));
        }

        @Test
        @DisplayName("Should refuse a portal.path equal to the step-up path")
        void shouldRefusePortalCollision() {
            String stepUpPath = "/auth/step-up";
            GatewayConfig gateway = validGateway().oidc(oidcWithStepUpPath(stepUpPath))
                    .portal(PortalConfig.builder().path(stepUpPath).title("Applications").build()).build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, PortalRules.PORTAL_PATH_POINTER, "reserved OIDC path");
        }

        @Test
        @DisplayName("Should accept a portal.path next to the step-up path")
        void shouldAcceptPortalNextToStepUpPath() {
            GatewayConfig gateway = validGateway().oidc(oidcWithStepUpPath("/auth/step-up"))
                    .portal(PortalConfig.builder().path("/auth").title("Applications").build()).build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> PortalRules.PORTAL_PATH_POINTER.equals(error.pointer())),
                    () -> "a path that is not the reserved one does not collide, got: " + errors);
        }
    }

    /**
     * The canonical-form refusal of the three operator-declared reserved paths
     * {@code oidc.login.path}, {@code oidc.user_info.path} and {@code oidc.step_up.path}: a path that
     * passes the absolute-path shape check but is not canonical — an inner {@code //}, a dot segment,
     * a matrix parameter or a percent-encoded character — is refused at the key's own pointer, naming
     * the key, because a reserved path is matched by exact equality with the canonical request path.
     * <p>
     * Every refused value starts with a single {@code /} and carries no query, fragment, backslash,
     * control character or whitespace, so the shape check admits it and the canonical-form check is
     * the only rule that can refuse it. The values are literals: each is the spelling the rule names.
     */
    @Nested
    @DisplayName("Reserved paths must be canonical")
    class ReservedPathCanonicalForm {

        private static final String CANONICAL_REFUSAL = "must be a canonical gateway path";

        /** The three operator-declared reserved paths, each with the key, pointer and fixture it needs. */
        enum ReservedPathKey {
            LOGIN("oidc.login.path", "/oidc/login/path", "/bff/login"),
            USER_INFO("oidc.user_info.path", "/oidc/user_info/path", "/bff/session"),
            STEP_UP("oidc.step_up.path", "/oidc/step_up/path", "/bff/step-up");

            private final String key;
            private final String pointer;
            private final String canonicalPath;

            ReservedPathKey(String key, String pointer, String canonicalPath) {
                this.key = key;
                this.pointer = pointer;
                this.canonicalPath = canonicalPath;
            }

            GatewayConfig gatewayDeclaring(String path) {
                OidcConfig.OidcConfigBuilder oidc = OidcConfig.builder();
                switch (this) {
                    case LOGIN -> oidc.login(new OidcConfig.Login(path, null));
                    case USER_INFO -> oidc.userInfo(new OidcConfig.UserInfo(path, List.of(), List.of()));
                    case STEP_UP -> oidc.stepUp(new OidcConfig.StepUp(null, null, path));
                }
                return validGateway().oidc(oidc.build()).build();
            }
        }

        static Stream<Arguments> nonCanonicalPaths() {
            Stream.Builder<Arguments> cases = Stream.builder();
            for (ReservedPathKey reservedPath : ReservedPathKey.values()) {
                cases.add(Arguments.of(reservedPath, "/bff/./login"));
                cases.add(Arguments.of(reservedPath, "/bff/../session"));
                cases.add(Arguments.of(reservedPath, "/bff//step-up"));
                cases.add(Arguments.of(reservedPath, "/bff/step-up;x"));
                cases.add(Arguments.of(reservedPath, "/bff/%2e/login"));
                cases.add(Arguments.of(reservedPath, "/bff/sess%69on"));
            }
            return cases.build();
        }

        private List<ConfigError> refusalsAt(ReservedPathKey reservedPath, String path) {
            return validator.validate(reservedPath.gatewayDeclaring(path), List.of(), topologyWith()).stream()
                    .filter(error -> reservedPath.pointer.equals(error.pointer()))
                    .toList();
        }

        @ParameterizedTest(name = "{0}: {1}")
        @MethodSource("nonCanonicalPaths")
        @DisplayName("Should refuse a non-canonical reserved path at the key's pointer, naming the key")
        void shouldRefuseANonCanonicalReservedPath(ReservedPathKey reservedPath, String path) {
            List<ConfigError> refusals = refusalsAt(reservedPath, path);

            assertEquals(1, refusals.size(), () -> "exactly one refusal at " + reservedPath.pointer + ", got: "
                    + refusals);
            String message = refusals.getFirst().message();
            assertAll(path,
                    () -> assertTrue(message.contains(reservedPath.key + " " + CANONICAL_REFUSAL),
                            () -> "the refusal names the key and the canonical-form requirement: " + message),
                    () -> assertFalse(message.contains(path),
                            () -> "the canonical-form refusal echoes no configured value: " + message));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReservedPathKey.class)
        @DisplayName("Should accept the canonical /bff/* form of each reserved path (matched control)")
        void shouldAcceptTheCanonicalForm(ReservedPathKey reservedPath) {
            assertEquals(List.of(), refusalsAt(reservedPath, reservedPath.canonicalPath),
                    "the refusals above are about the canonical form, not about declaring the key");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReservedPathKey.class)
        @DisplayName("Should report a scheme-relative path once, as a shape refusal, not twice")
        void shouldReportASchemeRelativePathOnce(ReservedPathKey reservedPath) {
            List<ConfigError> refusals = refusalsAt(reservedPath, "//bff/session");

            assertEquals(1, refusals.size(), () -> "one defect is reported once, got: " + refusals);
            assertTrue(refusals.getFirst().message().contains("must be an absolute gateway path starting with a single '/'"),
                    () -> refusals.getFirst().message());
        }
    }
}
