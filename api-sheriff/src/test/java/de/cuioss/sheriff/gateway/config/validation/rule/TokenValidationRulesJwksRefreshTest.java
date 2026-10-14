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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the {@link TokenValidationRules} rule the {@link ConfigValidator} runs that bounds the three
 * JWKS refresh keys of
 * {@code token_validation.issuers[].jwks} — {@code refresh_interval_seconds},
 * {@code key_rotation_grace_period_seconds} and {@code unknown_kid_refresh_window_seconds} — and
 * refuses each of them on a {@code file} source.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. Errors are filtered to the three keys' pointers, because the
 * fixtures are deliberately minimal and other rules may report unrelated gaps in them. The bounds are
 * literals on purpose: each is the exact number the rule admits or refuses, so the literal is the
 * contract under test. Every refusal has a matched admission that differs from it only in the value
 * or only in the source, so the refusal is attributable to that one difference.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — jwks refresh keys: ranges and file-source refusal")
class TokenValidationRulesJwksRefreshTest {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String POINTER_PREFIX = "/token_validation/issuers/";
    private static final String JWKS_URL = "https://idp.example/jwks";

    /** One of the three keys under test: its spelling, its admitted range and its builder setter. */
    enum RefreshKey {

        REFRESH_INTERVAL("refresh_interval_seconds", 60, 86_400,
            IssuerConfig.Jwks.JwksBuilder::refreshIntervalSeconds),
        GRACE_PERIOD("key_rotation_grace_period_seconds", 0, 3_600,
                IssuerConfig.Jwks.JwksBuilder::keyRotationGracePeriodSeconds),
        UNKNOWN_KID_WINDOW("unknown_kid_refresh_window_seconds", 5, 3_600,
                IssuerConfig.Jwks.JwksBuilder::unknownKidRefreshWindowSeconds);

        private final String key;
        private final int minimum;
        private final int maximum;
        private final BiConsumer<IssuerConfig.Jwks.JwksBuilder, Integer> setter;

        RefreshKey(String key, int minimum, int maximum,
                BiConsumer<IssuerConfig.Jwks.JwksBuilder, Integer> setter) {
            this.key = key;
            this.minimum = minimum;
            this.maximum = maximum;
            this.setter = setter;
        }

        String pointer(int index) {
            return POINTER_PREFIX + index + "/jwks/" + key;
        }

        String range() {
            return minimum + " to " + maximum;
        }
    }

    private final ConfigValidator validator = new ConfigValidator();

    private static String issuerName() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static IssuerConfig issuer(String name, IssuerConfig.Jwks jwks) {
        return IssuerConfig.builder().name(name).issuer("https://" + name + ".example").jwks(jwks).build();
    }

    private static IssuerConfig httpIssuer(String name, RefreshKey key, int value) {
        IssuerConfig.Jwks.JwksBuilder builder = IssuerConfig.Jwks.builder().source("http").url(JWKS_URL);
        key.setter.accept(builder, value);
        return issuer(name, builder.build());
    }

    private static IssuerConfig fileIssuer(String name, RefreshKey key, int value) {
        IssuerConfig.Jwks.JwksBuilder builder = IssuerConfig.Jwks.builder().source("file")
                .file("/etc/sheriff/jwks.json");
        key.setter.accept(builder, value);
        return issuer(name, builder.build());
    }

    private List<ConfigError> refreshErrors(GatewayConfig gateway) {
        return validator.validate(gateway, List.of(), new ResolvedTopology(Map.of())).stream()
                .filter(error -> error.pointer().startsWith(POINTER_PREFIX)
                        && Stream.of(RefreshKey.values()).anyMatch(key -> error.pointer().endsWith("/jwks/" + key.key)))
                .toList();
    }

    private List<ConfigError> refreshErrors(IssuerConfig... issuers) {
        return refreshErrors(GatewayConfig.builder()
                .version(1)
                .tokenValidation(new TokenValidationConfig(List.of(issuers)))
                .build());
    }

    private static void assertSingleRefusal(List<ConfigError> errors, RefreshKey key, String issuerName) {
        assertEquals(1, errors.size(), errors::toString);
        ConfigError error = errors.getFirst();
        String message = error.message();
        assertAll(
                () -> assertEquals(GATEWAY_FILE, error.file()),
                () -> assertEquals(key.pointer(0), error.pointer()),
                () -> assertTrue(message.contains("'" + issuerName + "'"),
                        () -> "the issuer must be named: " + message),
                () -> assertTrue(message.contains(key.key), () -> "the key must be named: " + message),
                () -> assertTrue(message.contains(key.range()),
                        () -> "the admitted range must be stated: " + message),
                () -> assertFalse(message.contains(JWKS_URL), () -> "the JWKS url must never be echoed: " + message),
                () -> assertFalse(message.contains("idp.example"),
                        () -> "no part of the JWKS url may be echoed: " + message));
    }

    @Nested
    @DisplayName("each key is bounded on an http source")
    class Range {

        static Stream<Arguments> refusedValues() {
            return Stream.of(RefreshKey.values()).flatMap(key -> Stream.of(
                    Arguments.of(key, key.minimum - 1),
                    Arguments.of(key, key.maximum + 1)));
        }

        static Stream<Arguments> admittedBounds() {
            return Stream.of(RefreshKey.values()).flatMap(key -> Stream.of(
                    Arguments.of(key, key.minimum),
                    Arguments.of(key, key.maximum)));
        }

        @ParameterizedTest(name = "refuses {0} = {1}")
        @MethodSource("refusedValues")
        void refusesTheValueOneOutsideEachBound(RefreshKey key, int value) {
            String name = issuerName();

            List<ConfigError> errors = refreshErrors(httpIssuer(name, key, value));

            assertSingleRefusal(errors, key, name);
            assertTrue(errors.getFirst().message().contains(" " + value + " "),
                    () -> "the offending value must be stated: " + errors.getFirst().message());
        }

        @ParameterizedTest(name = "admits {0} = {1}")
        @MethodSource("admittedBounds")
        void admitsEachBoundItself(RefreshKey key, int value) {
            assertEquals(List.of(), refreshErrors(httpIssuer(issuerName(), key, value)));
        }

        @Test
        @DisplayName("an issuer omitting all three keys is admitted")
        void admitsOmittedKeys() {
            IssuerConfig omitted = issuer(issuerName(),
                    IssuerConfig.Jwks.builder().source("http").url(JWKS_URL).build());

            assertEquals(List.of(), refreshErrors(omitted));
        }
    }

    @Nested
    @DisplayName("a file source has no refresh, so each key is refused there")
    class FileSource {

        @ParameterizedTest(name = "refuses {0} on a file issuer")
        @EnumSource(RefreshKey.class)
        void refusesTheKeyOnAFileIssuer(RefreshKey key) {
            String name = issuerName();

            List<ConfigError> errors = refreshErrors(fileIssuer(name, key, key.minimum));

            assertSingleRefusal(errors, key, name);
        }

        @ParameterizedTest(name = "admits the same {0} on an http issuer (matched control)")
        @EnumSource(RefreshKey.class)
        void admitsTheSameKeyOnAnHttpIssuer(RefreshKey key) {
            assertEquals(List.of(), refreshErrors(httpIssuer(issuerName(), key, key.minimum)));
        }

        @Test
        @DisplayName("a file issuer declaring none of the keys is admitted")
        void admitsAFileIssuerWithoutTheKeys() {
            IssuerConfig plain = issuer(issuerName(),
                    IssuerConfig.Jwks.builder().source("file").file("/etc/sheriff/jwks.json").build());

            assertEquals(List.of(), refreshErrors(plain));
        }
    }

    @Nested
    @DisplayName("scope — every violation of every issuer in one pass, message hygiene")
    class Scope {

        @Test
        @DisplayName("violations across two issuers are all reported in one pass")
        void collectsEveryViolationAcrossIssuers() {
            IssuerConfig first = issuer(issuerName(), IssuerConfig.Jwks.builder().source("http").url(JWKS_URL)
                    .refreshIntervalSeconds(59).keyRotationGracePeriodSeconds(300)
                    .unknownKidRefreshWindowSeconds(4).build());
            IssuerConfig second = issuer(issuerName(), IssuerConfig.Jwks.builder().source("file")
                    .file("/etc/sheriff/jwks.json").keyRotationGracePeriodSeconds(300).build());

            List<ConfigError> errors = refreshErrors(first, second);

            assertEquals(List.of(RefreshKey.REFRESH_INTERVAL.pointer(0), RefreshKey.UNKNOWN_KID_WINDOW.pointer(0),
                            RefreshKey.GRACE_PERIOD.pointer(1)),
                    errors.stream().map(ConfigError::pointer).toList(), errors::toString);
        }

        @Test
        @DisplayName("an issuer without a jwks block and an absent token_validation block are skipped")
        void missingBlocksAreSkipped() {
            IssuerConfig withoutJwks = IssuerConfig.builder().name(issuerName()).issuer("https://idp.example").build();

            assertAll(
                    () -> assertEquals(List.of(), refreshErrors(withoutJwks)),
                    () -> assertEquals(List.of(), refreshErrors(GatewayConfig.builder().version(1).build())));
        }

        @Test
        @DisplayName("a CR/LF-bearing issuer name is echoed escaped, never raw")
        void echoesControlCharactersInTheIssuerNameEscaped() {
            IssuerConfig forged = IssuerConfig.builder().name("idp\r\nforged").issuer("https://idp.example")
                    .jwks(IssuerConfig.Jwks.builder().source("http").url(JWKS_URL).refreshIntervalSeconds(59).build())
                    .build();

            List<ConfigError> errors = refreshErrors(forged);

            assertEquals(1, errors.size(), errors::toString);
            String message = errors.getFirst().message();
            assertAll(
                    () -> assertTrue(message.contains("idp\\u000D\\u000Aforged"),
                            () -> "the name must be echoed with CR and LF escaped: " + message),
                    () -> assertFalse(message.contains("\r"), "no raw CR may reach the boot log"),
                    () -> assertFalse(message.contains("\n"), "no raw LF may reach the boot log"));
        }
    }
}
