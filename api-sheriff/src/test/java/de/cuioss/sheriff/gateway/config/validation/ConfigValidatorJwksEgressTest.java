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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link ConfigValidator} rule that refuses an unusable
 * {@code token_validation.issuers[].jwks.allowed_egress_hosts} entry: a blank or whitespace-only entry
 * and a {@code host:port} entry of an {@code http}-sourced issuer.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. Errors are filtered to the allowlist pointers, because the
 * fixtures are deliberately minimal and other rules may report unrelated gaps in them. The entries are
 * literals on purpose: each is a specific shape the rule exists to refuse or to admit, so the exact
 * string is the contract under test. Every refusal has a matched admission that differs from it only in
 * the entry's shape, so the refusal is attributable to that shape.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — jwks.allowed_egress_hosts entry shape")
class ConfigValidatorJwksEgressTest {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String POINTER_PREFIX = "/token_validation/issuers/";
    private static final String POINTER_SUFFIX = "/jwks/allowed_egress_hosts";
    private static final String HOST_EXACT = "host-exact";
    private static final String BLANK = "blank";

    private final ConfigValidator validator = new ConfigValidator();

    private static String issuerName() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static String pointer(int index) {
        return POINTER_PREFIX + index + POINTER_SUFFIX;
    }

    private static IssuerConfig issuer(String name, IssuerConfig.Jwks jwks) {
        return IssuerConfig.builder().name(name).issuer("https://" + name + ".example").jwks(jwks).build();
    }

    private static IssuerConfig httpIssuer(String name, List<String> allowedEgressHosts) {
        return issuer(name, IssuerConfig.Jwks.builder()
                .source("http")
                .url("https://idp.example/jwks")
                .allowedEgressHosts(allowedEgressHosts)
                .build());
    }

    private List<ConfigError> egressErrors(GatewayConfig gateway) {
        return validator.validate(gateway, List.of(), new ResolvedTopology(Map.of())).stream()
                .filter(error -> error.pointer().startsWith(POINTER_PREFIX) && error.pointer().endsWith(POINTER_SUFFIX))
                .toList();
    }

    private List<ConfigError> egressErrors(IssuerConfig... issuers) {
        return egressErrors(GatewayConfig.builder()
                .version(1)
                .tokenValidation(new TokenValidationConfig(List.of(issuers)))
                .build());
    }

    private static void assertSingleRefusal(List<ConfigError> errors, String messageContains) {
        assertEquals(1, errors.size(), errors::toString);
        ConfigError error = errors.getFirst();
        assertAll(
                () -> assertEquals(GATEWAY_FILE, error.file()),
                () -> assertEquals(pointer(0), error.pointer()),
                () -> assertTrue(error.message().contains(messageContains),
                        () -> "expected the refusal to contain '" + messageContains + "': " + error.message()));
    }

    @Nested
    @DisplayName("a blank entry names no host")
    class BlankEntry {

        @ParameterizedTest(name = "refuses ''{0}''")
        @ValueSource(strings = {"", " ", "\t", "   \t  "})
        void refusesBlankOrWhitespaceOnlyEntry(String entry) {
            List<ConfigError> errors = egressErrors(httpIssuer(issuerName(), List.of(entry)));

            assertSingleRefusal(errors, BLANK);
        }

        @Test
        @DisplayName("the same issuer declaring a bare host instead is admitted (matched control)")
        void admitsTheSameIssuerWithABareHost() {
            assertEquals(List.of(), egressErrors(httpIssuer(issuerName(), List.of("keycloak"))));
        }
    }

    @Nested
    @DisplayName("a host:port entry never matches the host-exact allowlist")
    class HostWithPort {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"keycloak:8443", "idp.example.com:443", "10.0.0.5:8443", "[fd00::1]:8443"})
        void refusesHostWithPort(String entry) {
            String name = issuerName();

            List<ConfigError> errors = egressErrors(httpIssuer(name, List.of(entry)));

            assertSingleRefusal(errors, HOST_EXACT);
            String message = errors.getFirst().message();
            assertAll(
                    () -> assertTrue(message.contains(entry), () -> "the entry must be echoed: " + message),
                    () -> assertTrue(message.contains(name), () -> "the issuer must be named: " + message));
        }

        @ParameterizedTest(name = "admits {0}")
        @ValueSource(strings = {"keycloak", "idp.example.com", "10.0.0.5", "fd00::1", "::1", "[fd00::1]"})
        void admitsBareHost(String entry) {
            assertEquals(List.of(), egressErrors(httpIssuer(issuerName(), List.of(entry))));
        }

        @Test
        @DisplayName("a CR/LF-bearing entry is echoed escaped, never raw")
        void echoesControlCharactersEscaped() {
            List<ConfigError> errors = egressErrors(httpIssuer(issuerName(), List.of("keycloak\r\nforged:8443")));

            assertSingleRefusal(errors, HOST_EXACT);
            String message = errors.getFirst().message();
            assertAll(
                    () -> assertTrue(message.contains("keycloak\\u000D\\u000Aforged:8443"),
                            () -> "the entry must be echoed with CR and LF escaped: " + message),
                    () -> assertFalse(message.contains("\r"), "no raw CR may reach the boot log"),
                    () -> assertFalse(message.contains("\n"), "no raw LF may reach the boot log"));
        }
    }

    @Nested
    @DisplayName("scope — only http issuers' declared entries are inspected, every violation in one pass")
    class Scope {

        private static final List<String> UNUSABLE = List.of("", "keycloak:8443");

        @Test
        @DisplayName("a file issuer's list is not inspected")
        void fileIssuerIsNotInspected() {
            IssuerConfig fileIssuer = issuer(issuerName(), IssuerConfig.Jwks.builder()
                    .source("file")
                    .file("/etc/sheriff/jwks.json")
                    .allowedEgressHosts(UNUSABLE)
                    .build());

            assertEquals(List.of(), egressErrors(fileIssuer));
        }

        @Test
        @DisplayName("the same list on an http issuer is refused entry by entry (matched control)")
        void httpIssuerWithTheSameListIsRefused() {
            List<ConfigError> errors = egressErrors(httpIssuer(issuerName(), UNUSABLE));

            assertEquals(List.of(pointer(0), pointer(0)), errors.stream().map(ConfigError::pointer).toList(),
                    errors::toString);
        }

        @Test
        @DisplayName("an absent list is admitted — the allowance is derived from jwks.url")
        void absentListIsAdmitted() {
            IssuerConfig withoutList = issuer(issuerName(), IssuerConfig.Jwks.builder()
                    .source("http")
                    .url("https://idp.example/jwks")
                    .build());

            assertEquals(List.of(), egressErrors(withoutList));
        }

        @Test
        @DisplayName("an issuer without a jwks block and an absent token_validation block are skipped")
        void missingBlocksAreSkipped() {
            IssuerConfig withoutJwks = IssuerConfig.builder().name(issuerName()).issuer("https://idp.example").build();

            assertAll(
                    () -> assertEquals(List.of(), egressErrors(withoutJwks)),
                    () -> assertEquals(List.of(), egressErrors(GatewayConfig.builder().version(1).build())));
        }

        @Test
        @DisplayName("violations across two issuers are all reported in one pass")
        void collectsEveryViolationAcrossIssuers() {
            IssuerConfig first = httpIssuer(issuerName(), List.of(" ", "keycloak"));
            IssuerConfig second = httpIssuer(issuerName(), List.of("keycloak:8443", "idp.example.com", "[fd00::1]:8443"));

            List<ConfigError> errors = egressErrors(first, second);

            assertEquals(List.of(pointer(0), pointer(1), pointer(1)),
                    errors.stream().map(ConfigError::pointer).toList(), errors::toString);
        }
    }
}
