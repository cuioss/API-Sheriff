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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code oidc.allowed_egress_hosts} boot refusal the {@link ConfigValidator} runs through
 * {@link OidcClientRules#validateAllowedEgressHosts}: every entry must name a bare host, because the
 * token engine's SSRF egress guard matches the list host-exact and an entry it can never match leaves
 * the operator believing an identity provider is reachable that every dial then refuses.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. The hosts are generated and carry a hyphen, so no value can be
 * mistaken for a fragment of the fixed refusal text.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — oidc.allowed_egress_hosts")
class OidcClientRulesAllowedEgressHostsTest {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String POINTER = "/oidc/allowed_egress_hosts";

    private final ConfigValidator validator = new ConfigValidator();

    /** A generated host carrying a hyphen, e.g. {@code abcd-efgh}, so it is never a word of the refusal text. */
    private static String host() {
        return letters() + "-" + letters();
    }

    private static String letters() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private List<ConfigError> egressErrors(List<String> entries) {
        OidcConfig oidc = OidcConfig.builder()
                .issuer("https://" + host() + "/realms/" + letters())
                .redirectUri("https://gw.example.com/bff/callback")
                .allowedEgressHosts(entries)
                .build();
        GatewayConfig gateway = GatewayConfig.builder().version(1).oidc(oidc).build();
        return validator.validate(gateway, List.of(), new ResolvedTopology(Map.of())).stream()
                .filter(error -> POINTER.equals(error.pointer()))
                .toList();
    }

    static Stream<Arguments> acceptedEntries() {
        return Stream.of(
                Arguments.of("a host name", host()),
                Arguments.of("a single-label host", letters()),
                Arguments.of("an IPv4 literal", "127.0.0.1"),
                Arguments.of("a bare IPv6 literal, which is a host and not a host:port pair", "fd00::1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedEntries")
    @DisplayName("Should admit an entry that names a bare host")
    void shouldAdmitABareHost(String description, String entry) {
        assertEquals(List.of(), egressErrors(List.of(entry)), description);
    }

    @Test
    @DisplayName("Should admit an absent or empty list, which leaves the secure default in force")
    void shouldAdmitAnEmptyList() {
        assertEquals(List.of(), egressErrors(List.of()));
    }

    @Test
    @DisplayName("Should skip the rule when the document declares no oidc block")
    void shouldSkipWhenThereIsNoOidcBlock() {
        GatewayConfig gateway = GatewayConfig.builder().version(1).build();

        List<ConfigError> errors = validator.validate(gateway, List.of(), new ResolvedTopology(Map.of()));

        assertTrue(errors.stream().noneMatch(error -> POINTER.equals(error.pointer())), errors::toString);
    }

    @ParameterizedTest(name = "[{index}] blank entry")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("Should refuse a blank entry, which names no host to exempt")
    void shouldRefuseABlankEntry(String entry) {
        List<ConfigError> errors = egressErrors(List.of(entry));

        assertEquals(1, errors.size(), () -> "expected exactly one refusal, got: " + errors);
        ConfigError refusal = errors.getFirst();
        assertAll("the refusal of a blank entry",
                () -> assertEquals(GATEWAY_FILE, refusal.file()),
                () -> assertEquals(POINTER, refusal.pointer()),
                () -> assertTrue(refusal.message().contains("oidc.allowed_egress_hosts"), refusal::message),
                () -> assertTrue(refusal.message().contains("names no host"), refusal::message),
                () -> assertTrue(refusal.message().contains("keep the secure default"), refusal::message));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"keycloak:8443", "127.0.0.1:8080", "[fd00::1]:8443"})
    @DisplayName("Should refuse an entry carrying a port, which the host-exact guard never matches")
    void shouldRefuseAnEntryCarryingAPort(String entry) {
        List<ConfigError> errors = egressErrors(List.of(entry));

        assertEquals(1, errors.size(), () -> "expected exactly one refusal, got: " + errors);
        ConfigError refusal = errors.getFirst();
        assertAll("the refusal of " + entry,
                () -> assertEquals(GATEWAY_FILE, refusal.file()),
                () -> assertEquals(POINTER, refusal.pointer()),
                () -> assertTrue(refusal.message().contains("'" + entry + "'"),
                        () -> "the refusal names the offending entry: " + refusal.message()),
                () -> assertTrue(refusal.message().contains("carrying a port"), refusal::message),
                () -> assertTrue(refusal.message().contains("declare the bare host"), refusal::message));
    }

    @Test
    @DisplayName("Should report every offending entry in one pass and none of the good ones")
    void shouldReportEveryOffendingEntry() {
        List<ConfigError> errors = egressErrors(List.of("good-host", "bad-one:80", " ", "bad-two:81"));

        assertEquals(3, errors.size(), () -> "one refusal per offending entry, got: " + errors);
        assertTrue(errors.stream().noneMatch(error -> error.message().contains("good-host")),
                () -> "an admitted entry is not echoed: " + errors);
    }

    @Test
    @DisplayName("Should escape a hostile entry so it cannot forge a boot log line (CWE-117)")
    void shouldEscapeAHostileEntry() {
        List<ConfigError> errors = egressErrors(List.of("evil\nforged:80"));

        assertEquals(1, errors.size(), () -> "expected exactly one refusal, got: " + errors);
        String message = errors.getFirst().message();
        assertAll("the echoed entry is escaped",
                () -> assertFalse(message.contains("\n"), "no raw line feed survives"),
                () -> assertTrue(message.contains("\\u000A"), message));
    }
}
