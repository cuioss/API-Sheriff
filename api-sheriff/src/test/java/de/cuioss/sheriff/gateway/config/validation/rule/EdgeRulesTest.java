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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
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
 * Tests for the edge rules the {@link ConfigValidator} runs through {@link EdgeRules}: the D5
 * boot-time hardening of {@code forwarded.trusted_proxies} (real-CIDR parsing with full-space
 * rejection and the broad-prefix boot WARN, ADR-0044), the boot WARN for an acting
 * {@code enumeration_detection} action behind trusted proxies, the {@code tls.passthrough_sni} host
 * collision and alias-resolvability rules, and the boot WARN for a passthrough topology meeting an
 * acting detection action or a loopback trusted proxy.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("ConfigValidator — edge trust and TLS passthrough")
class EdgeRulesTest {

    private static final String DETECTION_BEHIND_TRUSTED_PROXIES_ID =
            ConfigLogMessages.WARN.DETECTION_ACTION_BEHIND_TRUSTED_PROXIES.resolveIdentifierString();

    private static final String PASSTHROUGH_SOURCE_COLLAPSE_ID =
            ConfigLogMessages.WARN.PASSTHROUGH_SOURCE_COLLAPSE.resolveIdentifierString();

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static RouteConfig routeWithHost(String id, String host, HttpMethod... methods) {
        MatchConfig match = MatchConfig.builder()
                .pathPrefix("/" + id)
                .methods(List.of(methods))
                .host(host)
                .build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static EnumerationDetectionConfig detection(boolean enabled, EnumerationDetectionConfig.Action action) {
        return EnumerationDetectionConfig.builder().enabled(enabled).action(action).build();
    }

    private static GatewayConfig gatewayWithPassthrough(Map<String, String> passthroughSni) {
        return validGateway()
                .tls(TlsConfig.builder().passthroughSni(passthroughSni).build())
                .build();
    }

    @Nested
    @DisplayName("A well-formed configuration")
    class ValidConfiguration {

        @Test
        @DisplayName("Should report no violations for a route host that no passthrough_sni host claims")
        void shouldAcceptRouteHostWithoutPassthroughCollision() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(),
                    routeWithHost("orders-list", "api.example.com", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report no violations for a passthrough_sni alias resolving to a bare '/' base path")
        void shouldAcceptPassthroughAliasWithTrailingSlashBasePath() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("orders-list", HttpMethod.GET));
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    "ORDERS", new ResolvedUpstream("https", "orders.internal", 443, ""),
                    "SECURE", new ResolvedUpstream("https", "secure.internal", 443, "/")));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topology);

            assertTrue(errors.isEmpty(),
                    () -> "expected no violations for a topology URL ending in a bare '/', got: " + errors);
        }
    }

    @Nested
    @DisplayName("Each enforced rule")
    class RuleViolations {

        static Stream<Arguments> fullAddressSpaceTrustedProxies() {
            return Stream.of(
                    Arguments.of("a single full-space IPv4 CIDR", List.of("0.0.0.0/0"), "entire IPv4 address space"),
                    Arguments.of("a single full-space IPv6 CIDR", List.of("::/0"), "entire IPv6 address space"),
                    Arguments.of("a complementary /1 IPv4 pair", List.of("0.0.0.0/1", "128.0.0.0/1"),
                            "entire IPv4 address space"),
                    Arguments.of("a complementary /1 IPv6 pair", List.of("::/1", "8000::/1"),
                            "entire IPv6 address space"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("fullAddressSpaceTrustedProxies")
        @DisplayName("Should reject forwarded.trusted_proxies entries that cover the whole address space")
        void shouldRejectFullAddressSpaceTrustedProxies(String label, List<String> trustedProxies,
                String expectedDetail) {
            GatewayConfig gateway = validGateway()
                    .forwarded(ForwardedConfig.builder().trustedProxies(trustedProxies).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", expectedDetail);
        }

        /**
         * Each entry fails CIDR parsing at a different point: no slash at all, a numeric prefix
         * length outside the address width, a prefix length that is not a number, and an address
         * part that is not an IP literal. All four must be reported identically.
         */
        @ParameterizedTest(name = "trusted_proxies entry \"{0}\" is rejected as malformed")
        @ValueSource(strings = {"not-a-cidr", "10.0.0.0/33", "10.0.0.0/abc", "not-an-ip/24"})
        @DisplayName("Should reject a malformed trusted_proxies CIDR entry with file/pointer context")
        void shouldRejectMalformedCidr(String malformedCidr) {
            GatewayConfig gateway = validGateway()
                    .forwarded(ForwardedConfig.builder()
                            .trustedProxies(List.of(malformedCidr)).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/forwarded/trusted_proxies", "malformed trusted_proxies CIDR: " + malformedCidr);
        }

        /**
         * Matched positive/negative controls straddling the broad-prefix threshold in both address
         * families. The warning fires when one entry spans more than a single operator-provisioned
         * network: broader than an IPv4 {@code /16}, or broader than an IPv6 {@code /48} site
         * allocation. Each family carries a range just past the boundary and one exactly at it, so
         * reverting either constant alone turns at least one case red. The IPv4 {@code /4} row pins the
         * far end of the band: a very broad range that still stops short of the whole address space
         * warns and boots, and is never mistaken for the trust-all refusal.
         */
        static Stream<Arguments> broadPrefixThresholdControls() {
            return Stream.of(
                    Arguments.of("IPv4 /4 is very broad yet short of the whole address space", "10.0.0.0/4", true),
                    Arguments.of("IPv4 /12 spans many provisioned networks", "172.16.0.0/12", true),
                    Arguments.of("IPv4 /8 spans many provisioned networks", "10.0.0.0/8", true),
                    Arguments.of("IPv4 /16 is one provisioned network", "172.16.0.0/16", false),
                    Arguments.of("IPv6 /32 is broader than one site allocation", "2001:db8::/32", true),
                    Arguments.of("IPv6 /48 is one site allocation", "2001:db8::/48", false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("broadPrefixThresholdControls")
        @DisplayName("Should warn only on a trusted_proxies range broader than one provisioned network")
        void shouldApplyBroadPrefixThreshold(String label, String cidr, boolean expectWarning) {
            GatewayConfig gateway = validGateway()
                    .forwarded(ForwardedConfig.builder().trustedProxies(List.of(cidr)).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.stream().noneMatch(e -> e.pointer().contains("trusted_proxies")),
                    () -> "a broad-but-not-total CIDR must not fail the boot, got: " + errors);
            String identifier = ConfigLogMessages.WARN.BROAD_TRUSTED_PROXY.resolveIdentifierString();
            if (expectWarning) {
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier);
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, cidr);
            } else {
                assertTrue(TestLoggerFactory.getTestHandler()
                                .resolveLogMessagesContaining(TestLogLevel.WARN, identifier).isEmpty(),
                        () -> cidr + " is within the broad-prefix threshold and must not emit " + identifier);
            }
        }

        /**
         * The boot WARN that an acting detection action meets trusted proxies, whose requests without a
         * resolvable client share the exempt {@code unknown} source: it fires for {@code block} and for
         * {@code delay} together with a non-empty {@code trusted_proxies}, and its matched controls —
         * {@code observe} with trusted proxies, {@code block} without them, an empty list, detection
         * disabled and an omitted block — stay silent.
         */
        static Stream<Arguments> actingDetectionBehindTrustedProxiesControls() {
            List<String> trusted = List.of("192.0.2.0/24");
            return Stream.of(
                    Arguments.of("block with trusted proxies", detection(true, EnumerationDetectionConfig.Action.BLOCK),
                            trusted, true),
                    Arguments.of("delay with trusted proxies", detection(true, EnumerationDetectionConfig.Action.DELAY),
                            trusted, true),
                    Arguments.of("observe with trusted proxies",
                            detection(true, EnumerationDetectionConfig.Action.OBSERVE), trusted, false),
                    Arguments.of("block without a forwarded block",
                            detection(true, EnumerationDetectionConfig.Action.BLOCK), null, false),
                    Arguments.of("block with an empty trusted_proxies",
                            detection(true, EnumerationDetectionConfig.Action.BLOCK), List.of(), false),
                    Arguments.of("block with detection disabled",
                            detection(false, EnumerationDetectionConfig.Action.BLOCK), trusted, false),
                    Arguments.of("an omitted enumeration_detection block", null, trusted, false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("actingDetectionBehindTrustedProxiesControls")
        @DisplayName("Should warn only when an acting enumeration_detection action meets non-empty trusted_proxies")
        void shouldWarnOnActingDetectionBehindTrustedProxies(String label,
                @Nullable EnumerationDetectionConfig detection, @Nullable List<String> trustedProxies,
                boolean expectWarning) {
            GatewayConfig.GatewayConfigBuilder builder = validGateway().enumerationDetection(detection);
            if (trustedProxies != null) {
                builder.forwarded(ForwardedConfig.builder().trustedProxies(trustedProxies).build());
            }
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(builder.build(), List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "the combination boots, got: " + errors);
            List<String> warnings = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, DETECTION_BEHIND_TRUSTED_PROXIES_ID).stream()
                    .map(LogRecord::getMessage).toList();
            if (expectWarning) {
                assertEquals(1, warnings.size(), () -> label + " must warn once, got: " + warnings);
                assertTrue(warnings.getFirst().contains(detection.effectiveAction().toString()),
                        () -> "the warning names the action: " + warnings);
                assertTrue(warnings.stream().noneMatch(message -> message.contains("192.0.2.")),
                        () -> "the warning never names an address: " + warnings);
            } else {
                assertTrue(warnings.isEmpty(), () -> label + " must stay silent, got: " + warnings);
            }
        }

        /**
         * The boot WARN that a passthrough topology meets a configuration acting on the loopback relay
         * leg as if it were a client. It fires once for {@code block} and once for {@code delay} under a
         * non-empty {@code passthrough_sni}, and once for a {@code trusted_proxies} range holding a
         * loopback address — overlapping {@code 127.0.0.0/8}, or containing {@code ::1}. Its matched
         * controls stay silent: passthrough alone, each combination without passthrough, {@code observe}
         * or disabled detection under passthrough, and a non-loopback proxy list under passthrough.
         */
        static Stream<Arguments> passthroughSourceCollapseControls() {
            EnumerationDetectionConfig block = detection(true, EnumerationDetectionConfig.Action.BLOCK);
            List<String> nonLoopback = List.of("192.0.2.0/24", "2001:db8::/48");
            return Stream.of(
                    Arguments.of("passthrough with block", true, block, null, "enumeration_detection.action is block"),
                    Arguments.of("passthrough with delay", true,
                            detection(true, EnumerationDetectionConfig.Action.DELAY), null,
                            "enumeration_detection.action is delay"),
                    Arguments.of("passthrough with an IPv4 range overlapping 127.0.0.0/8", true, null,
                            List.of("192.0.2.0/24", "127.0.0.1/32"), "forwarded.trusted_proxies holds a loopback range"),
                    Arguments.of("passthrough with an IPv6 range containing ::1", true, null, List.of("::1/128"),
                            "forwarded.trusted_proxies holds a loopback range"),
                    Arguments.of("passthrough alone", true, null, null, null),
                    Arguments.of("block without passthrough", false, block, null, null),
                    Arguments.of("a loopback proxy without passthrough", false, null, List.of("127.0.0.0/8"), null),
                    Arguments.of("passthrough with observe", true,
                            detection(true, EnumerationDetectionConfig.Action.OBSERVE), null, null),
                    Arguments.of("passthrough with detection disabled and block", true,
                            detection(false, EnumerationDetectionConfig.Action.BLOCK), null, null),
                    Arguments.of("passthrough with a non-loopback proxy list", true, null, nonLoopback, null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("passthroughSourceCollapseControls")
        @DisplayName("Should warn only when passthrough_sni meets an acting detection action or a loopback trusted proxy")
        void shouldWarnOnPassthroughSourceCollapse(String label, boolean passthrough,
                @Nullable EnumerationDetectionConfig detection, @Nullable List<String> trustedProxies,
                @Nullable String expectedToken) {
            GatewayConfig.GatewayConfigBuilder builder = validGateway().enumerationDetection(detection);
            if (passthrough) {
                builder.tls(TlsConfig.builder().passthroughSni(Map.of("secure.example.com", "SECURE")).build());
            }
            if (trustedProxies != null) {
                builder.forwarded(ForwardedConfig.builder().trustedProxies(trustedProxies).build());
            }
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(builder.build(), List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertTrue(errors.isEmpty(), () -> "the combination boots, got: " + errors);
            List<String> warnings = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, PASSTHROUGH_SOURCE_COLLAPSE_ID).stream()
                    .map(LogRecord::getMessage).toList();
            if (expectedToken != null) {
                assertEquals(1, warnings.size(), () -> label + " must warn once, got: " + warnings);
                assertTrue(warnings.getFirst().contains(expectedToken),
                        () -> "the warning names the combination by its fixed token: " + warnings);
                assertTrue(warnings.stream().noneMatch(message -> message.contains("127.0.0.")
                                || message.contains("::1/") || message.contains("192.0.2.")),
                        () -> "the warning never names a configured range: " + warnings);
            } else {
                assertTrue(warnings.isEmpty(), () -> label + " must stay silent, got: " + warnings);
            }
        }

        @Test
        @DisplayName("Should warn once per combination when passthrough_sni meets both an acting action and a loopback proxy")
        void shouldWarnOncePerPassthroughSourceCollapseCombination() {
            GatewayConfig gateway = validGateway()
                    .enumerationDetection(detection(true, EnumerationDetectionConfig.Action.BLOCK))
                    .tls(TlsConfig.builder().passthroughSni(Map.of("secure.example.com", "SECURE")).build())
                    .forwarded(ForwardedConfig.builder().trustedProxies(List.of("127.0.0.0/8")).build())
                    .build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS", "SECURE"));

            assertTrue(errors.isEmpty(), () -> "the combination boots, got: " + errors);
            List<String> warnings = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, PASSTHROUGH_SOURCE_COLLAPSE_ID).stream()
                    .map(LogRecord::getMessage).toList();
            assertEquals(2, warnings.size(), () -> "one warning per combination, got: " + warnings);
            assertTrue(warnings.stream().anyMatch(message -> message.contains("enumeration_detection.action is block"))
                    && warnings.stream().anyMatch(message -> message.contains("holds a loopback range")),
                    () -> "each combination is named by its own token: " + warnings);
        }

        @Test
        @DisplayName("Should reject a route whose match.host collides case-insensitively with a passthrough_sni host")
        void shouldRejectRouteHostCollidingWithPassthroughSni() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(),
                    routeWithHost("orders-list", "SECURE.EXAMPLE.COM", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint),
                    topologyWith("ORDERS", "SECURE"));

            assertHasError(errors, "/tls/passthrough_sni",
                    "route 'orders-list' matches host 'secure.example.com'");
        }

        @Test
        @DisplayName("Should reject a passthrough_sni alias that does not resolve")
        void shouldRejectUnresolvedPassthroughAlias() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "MISSING"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("orders-list", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/tls/passthrough_sni",
                    "unresolved topology alias 'MISSING' referenced by passthrough_sni host 'secure.example.com'");
        }

        @Test
        @DisplayName("Should reject a passthrough_sni alias resolving to an upstream carrying a base path")
        void shouldRejectPassthroughAliasWithBasePath() {
            GatewayConfig gateway = gatewayWithPassthrough(Map.of("secure.example.com", "SECURE"));
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("orders-list", HttpMethod.GET));
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    "ORDERS", new ResolvedUpstream("https", "orders.internal", 443, ""),
                    "SECURE", new ResolvedUpstream("https", "secure.internal", 443, "/api")));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topology);

            assertHasError(errors, "/tls/passthrough_sni", "must resolve to an origin without a base path");
        }
    }
}
