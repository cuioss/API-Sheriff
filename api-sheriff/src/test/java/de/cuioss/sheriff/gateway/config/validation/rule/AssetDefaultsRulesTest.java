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

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.topologyWith;
import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.validGateway;
import static de.cuioss.sheriff.gateway.testsupport.ValidationAsserts.assertHasError;
import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AssetDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code asset_defaults.content_types} rules the {@link ConfigValidator} runs through
 * {@link AssetDefaultsRules}: the add-only refusal of an entry naming a built-in extension, and the
 * well-formedness refusal of an entry whose value is not a {@code type/subtype} media type.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — asset defaults")
class AssetDefaultsRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Nested
    @DisplayName("The add-only 'asset_defaults.content_types' boot refusal")
    class AssetContentTypesAddOnlyRefusal {

        private static final String POINTER = "/asset_defaults/content_types";
        private static final String REFUSAL_MESSAGE = "the built-in content-type mappings are immutable";

        private static GatewayConfig gatewayWithContentTypes(Map<String, String> contentTypes) {
            return validGateway()
                    .assetDefaults(new AssetDefaultsConfig(contentTypes))
                    .build();
        }

        @Test
        @DisplayName("Should accept an entry for an extension the gateway does not map")
        void shouldAcceptUnmappedExtension() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("webmanifest", "application/manifest+json"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an unmapped extension is exactly what the block exists for, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an absent asset_defaults block")
        void shouldAcceptAbsentBlock() {
            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an omitted block is never refused, got: " + errors);
        }

        @Test
        @DisplayName("Should accept an empty content_types map")
        void shouldAcceptEmptyMap() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of());

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.stream().noneMatch(error -> error.pointer().contains(POINTER)),
                    () -> "an empty block is the add-only no-op, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse an entry naming a built-in extension, naming that extension")
        void shouldRefuseBuiltInExtension() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("png", "image/jpeg"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE),
                    () -> assertHasError(errors, POINTER, "png"));
        }

        @Test
        @DisplayName("Should refuse the stored-XSS lever 'svg: text/html' the add-only ruling exists to remove")
        void shouldRefuseSvgRemappedToHtml() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("svg", "text/html; charset=utf-8"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE),
                    () -> assertHasError(errors, POINTER, "svg"));
        }

        @Test
        @DisplayName("Should refuse a built-in extension declared in upper case")
        void shouldRefuseBuiltInExtensionRegardlessOfCase() {
            GatewayConfig gateway = gatewayWithContentTypes(Map.of("SVG", "text/html; charset=utf-8"));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, POINTER, "svg");
        }

        @Test
        @DisplayName("Should collect every offending entry in one pass rather than failing on the first")
        void shouldCollectEveryOffendingEntry() {
            Map<String, String> contentTypes = new LinkedHashMap<>();
            contentTypes.put("svg", "text/html; charset=utf-8");
            contentTypes.put("png", "image/jpeg");
            contentTypes.put("webmanifest", "application/manifest+json");
            GatewayConfig gateway = gatewayWithContentTypes(contentTypes);

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertEquals(2, errors.stream().filter(e -> e.pointer().contains(POINTER)).count(),
                            "both built-in collisions are reported, the legal addition is not"),
                    () -> assertHasError(errors, POINTER, "svg"),
                    () -> assertHasError(errors, POINTER, "png"));
        }
    }

    /**
     * The value half of {@code asset_defaults.content_types}. The declared value is served verbatim
     * as the asset's {@code Content-Type} response header, so a value that is not a well-formed
     * media type — a CR/LF-bearing one above all — must be refused at boot rather than misbehaving
     * per request inside the response write. This is a well-formedness gate only: it ranks no media
     * type as safe or unsafe, because the add-only key rule already fences the built-in,
     * script-executable extensions.
     */
    @Nested
    @DisplayName("The 'asset_defaults.content_types' value well-formedness refusal")
    class AssetContentTypeValueRefusal {

        private static final String POINTER = "/asset_defaults/content_types";
        private static final String REFUSAL_MESSAGE = "is not a well-formed media type";
        private static final String EXTENSION = "webmanifest";

        private static GatewayConfig gatewayWithValue(String value) {
            return validGateway()
                    .assetDefaults(new AssetDefaultsConfig(Map.of(EXTENSION, value)))
                    .build();
        }

        private static List<ConfigError> valueErrors(List<ConfigError> errors) {
            return errors.stream()
                    .filter(error -> error.pointer().contains(POINTER) && error.message().contains(REFUSAL_MESSAGE))
                    .toList();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "application/manifest+json",
                "text/plain; charset=utf-8",
                "application/vnd.api+json",
                "font/collection",
                "application/x-7z-compressed",
                "multipart/form-data; boundary=abc123; charset=utf-8"})
        @DisplayName("Should accept a well-formed media type, with or without parameters")
        void shouldAcceptWellFormedMediaType(String value) {
            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertTrue(valueErrors(errors).isEmpty(),
                    () -> "'" + value + "' is a well-formed media type, got: " + errors);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "notamediatype",
                "",
                "   ",
                "application/",
                "/json",
                "text/plain; charset",
                "text/plain extra",
                "text/plain\n"})
        @DisplayName("Should refuse a value that is not a 'type/subtype' media type")
        void shouldRefuseMalformedValue(String value) {
            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "'" + value + "' is not a media type and must be refused at boot, got: " + errors);
        }

        @Test
        @DisplayName("Should name the offending extension and value, so the operator can find the typo")
        void shouldNameExtensionAndValue() {
            List<ConfigError> errors = validator.validate(gatewayWithValue("notamediatype"), List.of(),
                    topologyWith());

            assertAll(
                    () -> assertHasError(errors, POINTER, EXTENSION),
                    () -> assertHasError(errors, POINTER, "notamediatype"),
                    () -> assertHasError(errors, POINTER, REFUSAL_MESSAGE));
        }

        @Test
        @DisplayName("Should escape the echoed value, so the refusal cannot forge a line in the boot ERROR log")
        void shouldNotEchoRawControlCharacters() {
            String injected = "text/plain\r\nX-Injected: 1";

            List<ConfigError> errors = validator.validate(gatewayWithValue(injected), List.of(), topologyWith());

            List<ConfigError> refusals = valueErrors(errors);
            assertEquals(1, refusals.size(), () -> "a CR/LF-bearing value never reaches a response header, got: " + errors);
            ConfigError refusal = refusals.getFirst();
            assertAll(
                    () -> assertFalse(refusal.message().contains("\r"),
                            () -> "a raw CR would forge a log line: " + refusal.message()),
                    () -> assertFalse(refusal.message().contains("\n"),
                            () -> "a raw LF would forge a log line: " + refusal.message()),
                    () -> assertTrue(refusal.message().contains("\\u000D\\u000A"),
                            () -> "the operator still sees what they typed, escaped: " + refusal.message()));
        }

        /**
         * Guards the possessive quantifiers in {@code AssetDefaultsRules.MEDIA_TYPE}. With the greedy
         * form this pattern previously carried, the parameter-list repetition made the regex engine
         * recurse once per iteration and retain a backtracking position for each, so an input with
         * enough parameters exhausted the stack ({@code StackOverflowError}) instead of matching.
         * The possessive form scans flat, so the same input is simply accepted.
         * <p>
         * The input is deliberately <em>well-formed</em>: the assertion is that a long value is
         * decided normally, and any stack exhaustion surfaces as a thrown error failing this test
         * rather than as a boot-time crash on an operator's config.
         */
        @Test
        @DisplayName("Should decide a value carrying very many parameters without exhausting the stack")
        void shouldNotRecurseOnLongParameterList() {
            String value = "text/plain" + "; a=b".repeat(50_000);

            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertTrue(valueErrors(errors).isEmpty(),
                    () -> "a long but well-formed parameter list is accepted, got: " + errors.size() + " error(s)");
        }

        /**
         * The malformed counterpart: a long parameter list terminated by a character outside the
         * token set must be refused, again without the engine recursing per parameter.
         */
        @Test
        @DisplayName("Should refuse a long malformed value without exhausting the stack")
        void shouldRefuseLongMalformedValueWithoutRecursing() {
            String value = "text/plain" + "; a=b".repeat(50_000) + "\r\nX-Injected: 1";

            List<ConfigError> errors = validator.validate(gatewayWithValue(value), List.of(), topologyWith());

            assertEquals(1, valueErrors(errors).size(),
                    () -> "a long CR/LF-bearing value is refused, got: " + errors.size() + " error(s)");
        }

        @Test
        @DisplayName("Should collect every offending value in one pass rather than failing on the first")
        void shouldCollectEveryOffendingValue() {
            Map<String, String> contentTypes = new LinkedHashMap<>();
            contentTypes.put("webmanifest", "application/manifest+json");
            contentTypes.put("avifs", "notamediatype");
            contentTypes.put("jxl", "image/jxl\r\nX-Injected: 1");
            GatewayConfig gateway = validGateway()
                    .assetDefaults(new AssetDefaultsConfig(contentTypes))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll(
                    () -> assertEquals(2, valueErrors(errors).size(),
                            "both malformed values are reported, the well-formed one is not"),
                    () -> assertHasError(errors, POINTER, "avifs"),
                    () -> assertHasError(errors, POINTER, "jxl"));
        }
    }
}
