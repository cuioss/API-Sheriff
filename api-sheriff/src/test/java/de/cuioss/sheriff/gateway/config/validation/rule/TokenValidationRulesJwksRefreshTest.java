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

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.issuer;
import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.issuerName;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.sheriff.gateway.testsupport.RepositoryRoot;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the {@link TokenValidationRules} rule the {@link ConfigValidator} runs that bounds the four
 * JWKS refresh keys of
 * {@code token_validation.issuers[].jwks} — {@code refresh_interval_seconds},
 * {@code key_rotation_grace_period_seconds}, {@code unknown_kid_refresh_window_seconds} and
 * {@code max_staleness_seconds} — and refuses each of them on a {@code file} source. For
 * {@code max_staleness_seconds} the generated cases are 59 and 604801 refused, 60 and 604800 admitted.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. Errors are filtered to the four keys' pointers, because the
 * fixtures are deliberately minimal and other rules may report unrelated gaps in them. The bounds are
 * literals on purpose: each is the exact number the rule admits or refuses, so the literal is the
 * contract under test. Every refusal has a matched admission that differs from it only in the value
 * or only in the source, so the refusal is attributable to that one difference.
 * <p>
 * Because the keys are re-listed here rather than read from {@link TokenValidationRules.JwksRefreshKey},
 * a completeness guard ties the two lists together: the {@link RefreshKey} spellings must equal the
 * production spellings as a set, so a key production gains (which no case here would generate and
 * whose errors the pointer filter would drop) or loses turns this class red. A matched negative
 * control shows the guard's comparison reports such a key.
 * <p>
 * The {@code IssuerConfig.Jwks} Javadoc renders every range from the public bound constants of
 * {@link TokenValidationRules} rather than restating it, and {@link DocumentedRange} holds the contract
 * literal, the constant, the enforced bound and the Javadoc reference to one another, so a bound or its
 * documentation drifting alone turns this class red. Each key's references are looked for inside that
 * key's own {@code @param} section only, so two keys' references exchanged with each other are reported
 * although every reference still appears somewhere in the Javadoc.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — jwks refresh keys: ranges and file-source refusal")
class TokenValidationRulesJwksRefreshTest {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String POINTER_PREFIX = "/token_validation/issuers/";
    private static final String JWKS_URL = "https://idp.example/jwks";

    /**
     * One of the four keys under test: its spelling, the {@code IssuerConfig.Jwks} record component whose
     * {@code @param} section documents it, its admitted range as the contract literal, the stem of the
     * public bound constants that section renders, the values of those constants, and its builder setter.
     */
    enum RefreshKey {

        REFRESH_INTERVAL("refresh_interval_seconds", "refreshIntervalSeconds", 60, 86_400,
            TokenValidationRules.REFRESH_INTERVAL_MIN_SECONDS, TokenValidationRules.REFRESH_INTERVAL_MAX_SECONDS,
            IssuerConfig.Jwks.JwksBuilder::refreshIntervalSeconds),
        KEY_ROTATION_GRACE_PERIOD("key_rotation_grace_period_seconds", "keyRotationGracePeriodSeconds", 0, 3_600,
                TokenValidationRules.KEY_ROTATION_GRACE_PERIOD_MIN_SECONDS,
                TokenValidationRules.KEY_ROTATION_GRACE_PERIOD_MAX_SECONDS,
                IssuerConfig.Jwks.JwksBuilder::keyRotationGracePeriodSeconds),
        UNKNOWN_KID_REFRESH_WINDOW("unknown_kid_refresh_window_seconds", "unknownKidRefreshWindowSeconds", 5, 3_600,
                TokenValidationRules.UNKNOWN_KID_REFRESH_WINDOW_MIN_SECONDS,
                TokenValidationRules.UNKNOWN_KID_REFRESH_WINDOW_MAX_SECONDS,
                IssuerConfig.Jwks.JwksBuilder::unknownKidRefreshWindowSeconds),
        MAX_STALENESS("max_staleness_seconds", "maxStalenessSeconds", 60, 604_800,
                TokenValidationRules.MAX_STALENESS_MIN_SECONDS, TokenValidationRules.MAX_STALENESS_MAX_SECONDS,
                IssuerConfig.Jwks.JwksBuilder::maxStalenessSeconds);

        private final String key;
        private final String component;
        private final int minimum;
        private final int maximum;
        private final int documentedMinimum;
        private final int documentedMaximum;
        private final BiConsumer<IssuerConfig.Jwks.JwksBuilder, Integer> setter;

        RefreshKey(String key, String component, int minimum, int maximum,
                int documentedMinimum, int documentedMaximum,
                BiConsumer<IssuerConfig.Jwks.JwksBuilder, Integer> setter) {
            this.key = key;
            this.component = component;
            this.minimum = minimum;
            this.maximum = maximum;
            this.documentedMinimum = documentedMinimum;
            this.documentedMaximum = documentedMaximum;
            this.setter = setter;
        }

        /** The name of the public constant carrying the lower bound the Javadoc renders. */
        String minimumConstant() {
            return name() + "_MIN_SECONDS";
        }

        /** The name of the public constant carrying the upper bound the Javadoc renders. */
        String maximumConstant() {
            return name() + "_MAX_SECONDS";
        }

        /** The production key of the same spelling; the completeness guard proves there is exactly one. */
        TokenValidationRules.JwksRefreshKey production() {
            return Stream.of(TokenValidationRules.JwksRefreshKey.values())
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> fail("no production JwksRefreshKey is spelled " + key));
        }

        String pointer(int index) {
            return POINTER_PREFIX + index + "/jwks/" + key;
        }

        String range() {
            return minimum + " to " + maximum;
        }

        /**
         * The spellings present on one side only: in {@code productionKeys} but not among the
         * {@link RefreshKey} spellings, or among them but not in {@code productionKeys}.
         *
         * @param productionKeys the key spellings the production rule iterates
         * @return the symmetric difference, sorted; empty when both sides list the same keys
         */
        static Set<String> keysOnOneSideOnly(Set<String> productionKeys) {
            Set<String> covered = Stream.of(values()).map(refreshKey -> refreshKey.key).collect(Collectors.toSet());
            Set<String> oneSideOnly = new TreeSet<>(productionKeys);
            oneSideOnly.addAll(covered);
            oneSideOnly.removeIf(key -> productionKeys.contains(key) && covered.contains(key));
            return oneSideOnly;
        }
    }

    /** The spellings of the production rule's {@link TokenValidationRules.JwksRefreshKey} constants. */
    private static Set<String> productionKeys() {
        return Stream.of(TokenValidationRules.JwksRefreshKey.values())
                .map(TokenValidationRules.JwksRefreshKey::key)
                .collect(Collectors.toSet());
    }

    private final ConfigValidator validator = new ConfigValidator();

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
    @DisplayName("completeness — the keys under test are exactly the keys the rule bounds")
    class Completeness {

        private static final String INVENTED_KEY = "extra_refresh_seconds";

        @Test
        @DisplayName("the test's keys equal the production JwksRefreshKey spellings")
        void coversEveryProductionKey() {
            Set<String> production = productionKeys();

            Set<String> oneSideOnly = RefreshKey.keysOnOneSideOnly(production);

            assertEquals(Set.of(), oneSideOnly,
                    () -> "keys listed on one side only — extend RefreshKey to match JwksRefreshKey: " + oneSideOnly);
        }

        @Test
        @DisplayName("a key production gains is reported (matched negative control)")
        void reportsAKeyProductionGains() {
            Set<String> production = new HashSet<>(productionKeys());
            production.add(INVENTED_KEY);

            Set<String> oneSideOnly = RefreshKey.keysOnOneSideOnly(production);

            assertEquals(Set.of(INVENTED_KEY), oneSideOnly,
                    "a production key the test does not cover must be named, and nothing else");
        }

        @Test
        @DisplayName("a key production drops is reported (matched negative control)")
        void reportsAKeyProductionDrops() {
            Set<String> production = new HashSet<>(productionKeys());
            production.remove(RefreshKey.MAX_STALENESS.key);

            Set<String> oneSideOnly = RefreshKey.keysOnOneSideOnly(production);

            assertEquals(Set.of(RefreshKey.MAX_STALENESS.key), oneSideOnly,
                    "a test key production no longer bounds must be named, and nothing else");
        }
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
        @DisplayName("an issuer omitting all four keys is admitted")
        void admitsOmittedKeys() {
            IssuerConfig omitted = issuer(issuerName(),
                    IssuerConfig.Jwks.builder().source("http").url(JWKS_URL).build());

            assertEquals(List.of(), refreshErrors(omitted));
        }
    }

    /**
     * The {@code IssuerConfig.Jwks} Javadoc renders each admitted range from the public bound constants of
     * {@link TokenValidationRules} through {@code {@value}} instead of restating the numbers. These cases
     * tie the three places a bound lives together: the contract literal of {@link RefreshKey}, the public
     * constant the Javadoc renders, and the {@link TokenValidationRules.JwksRefreshKey} bound the rule
     * enforces — and they hold each key's own {@code @param} section of the Javadoc to referencing that
     * key's constants, so neither a bound nor its documentation can drift from the others, and no two keys'
     * references can be exchanged, without a red build.
     */
    @Nested
    @DisplayName("the IssuerConfig.Jwks Javadoc renders each enforced range instead of restating it")
    class DocumentedRange {

        private static final String ISSUER_CONFIG_SOURCE =
                "api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java";

        /** Opens the Javadoc of the nested {@code Jwks} record. */
        private static final String JWKS_JAVADOC_START = "Key material for signature verification.";

        /** Follows the Javadoc of the nested {@code Jwks} record. */
        private static final String JWKS_JAVADOC_END = "public record Jwks(";

        /** Closes a Javadoc comment. */
        private static final String JAVADOC_CLOSE = "*/";

        private static final String VALUE_REFERENCE =
                "{@value de.cuioss.sheriff.gateway.config.validation.rule.TokenValidationRules#%s}";

        /** A Javadoc line's leading asterisk and the single space after it. */
        private static final Pattern JAVADOC_LINE_PREFIX = Pattern.compile("(?m)^\\s*\\*\\s?");

        /** A range stated by hand: a digit following {@code range:}, whatever whitespace separates them. */
        private static final Pattern HAND_WRITTEN_RANGE = Pattern.compile("(?i)range:\\s*\\d[\\d_,]*");

        /** A block tag opening a line of a Javadoc text whose line prefixes are already stripped. */
        private static final Pattern BLOCK_TAG = Pattern.compile("(?m)^@\\w+");

        private static String jwksJavadoc() throws IOException {
            String source = Files.readString(RepositoryRoot.holding(ISSUER_CONFIG_SOURCE).resolve(ISSUER_CONFIG_SOURCE));
            int start = source.indexOf(JWKS_JAVADOC_START);
            assertTrue(start >= 0, () -> ISSUER_CONFIG_SOURCE + " no longer carries the anchor " + JWKS_JAVADOC_START);
            int end = source.indexOf(JWKS_JAVADOC_END, start);
            assertTrue(end > start,
                    () -> ISSUER_CONFIG_SOURCE + " carries no " + JWKS_JAVADOC_END + " after the Javadoc");
            int close = source.lastIndexOf(JAVADOC_CLOSE, end);
            assertTrue(close > start,
                    () -> ISSUER_CONFIG_SOURCE + " carries no " + JAVADOC_CLOSE + " closing the Javadoc");
            return JAVADOC_LINE_PREFIX.matcher(source.substring(start, close)).replaceAll("");
        }

        /**
         * The {@code @param} section of one record component: from its {@code @param} tag up to the next
         * block tag, or to the end of the Javadoc when no block tag follows.
         *
         * @param javadoc   the Javadoc text, its line prefixes already stripped
         * @param component the record component the section documents
         * @return the section, or empty when the Javadoc documents no such component
         */
        static Optional<String> paramSection(String javadoc, String component) {
            Matcher tag = Pattern.compile("(?m)^@param\\s+" + Pattern.quote(component) + "\\b").matcher(javadoc);
            if (!tag.find()) {
                return Optional.empty();
            }
            Matcher next = BLOCK_TAG.matcher(javadoc);
            int end = next.find(tag.end()) ? next.start() : javadoc.length();
            return Optional.of(javadoc.substring(tag.start(), end));
        }

        /**
         * The bound references of one key a Javadoc text fails to carry.
         *
         * @param text the text searched, a whole Javadoc or one {@code @param} section of it
         * @param key  the key whose two bound references are looked for
         * @return the missing references, lower bound first; empty when both are present
         */
        static List<String> missingBoundReferences(String text, RefreshKey key) {
            return Stream.of(key.minimumConstant(), key.maximumConstant())
                    .map(VALUE_REFERENCE::formatted)
                    .filter(reference -> !text.contains(reference))
                    .toList();
        }

        /**
         * The ranges a Javadoc text states by hand.
         *
         * @param javadoc the Javadoc text, its line prefixes already stripped
         * @return every hand-written range found, in order; empty when every range is rendered
         */
        static List<String> handWrittenRanges(String javadoc) {
            return HAND_WRITTEN_RANGE.matcher(javadoc).results().map(MatchResult::group).toList();
        }

        @ParameterizedTest(name = "{0}: the literal, the public constant and the enforced bound agree")
        @EnumSource(RefreshKey.class)
        void theContractLiteralThePublicConstantAndTheEnforcedBoundAgree(RefreshKey key) {
            TokenValidationRules.JwksRefreshKey production = key.production();

            assertAll(
                    () -> assertEquals(key.minimum, key.documentedMinimum,
                            () -> key.minimumConstant() + " drifted from the contract literal"),
                    () -> assertEquals(key.maximum, key.documentedMaximum,
                            () -> key.maximumConstant() + " drifted from the contract literal"),
                    () -> assertEquals(key.minimum, production.minimum(),
                            () -> "the enforced lower bound of " + production + " drifted from the contract literal"),
                    () -> assertEquals(key.maximum, production.maximum(),
                            () -> "the enforced upper bound of " + production + " drifted from the contract literal"));
        }

        @ParameterizedTest(name = "{0}: its own @param section renders both bounds from their constants")
        @EnumSource(RefreshKey.class)
        void theJavadocRendersBothBoundsFromTheirConstants(RefreshKey key) throws Exception {
            String javadoc = jwksJavadoc();

            String section = paramSection(javadoc, key.component).orElseGet(() -> fail(
                    "the IssuerConfig.Jwks Javadoc carries no @param " + key.component + " section documenting "
                            + key.key));

            List<String> missing = missingBoundReferences(section, key);
            assertEquals(List.of(), missing,
                    () -> "the @param " + key.component + " section of the IssuerConfig.Jwks Javadoc must render "
                            + "both bounds of " + key.key + " from their own constants; missing: " + missing);
        }

        @Test
        @DisplayName("two keys' exchanged references are reported per section, not by the whole Javadoc (matched negative control)")
        void reportsReferencesExchangedBetweenTwoKeys() {
            String exchanged = JAVADOC_LINE_PREFIX.matcher("""
                     * Key material for signature verification.
                     *
                     * @param refreshIntervalSeconds
                     *        the refresh interval. Admitted range:
                     *        %s
                     *        to
                     *        %s.
                     * @param maxStalenessSeconds
                     *        the staleness bound. Admitted range:
                     *        %s
                     *        to
                     *        %s.
                     * @since 1.0
                    """.formatted(
                    VALUE_REFERENCE.formatted(RefreshKey.MAX_STALENESS.minimumConstant()),
                    VALUE_REFERENCE.formatted(RefreshKey.MAX_STALENESS.maximumConstant()),
                    VALUE_REFERENCE.formatted(RefreshKey.REFRESH_INTERVAL.minimumConstant()),
                    VALUE_REFERENCE.formatted(RefreshKey.REFRESH_INTERVAL.maximumConstant()))).replaceAll("");
            List<String> refreshIntervalReferences = List.of(
                    VALUE_REFERENCE.formatted(RefreshKey.REFRESH_INTERVAL.minimumConstant()),
                    VALUE_REFERENCE.formatted(RefreshKey.REFRESH_INTERVAL.maximumConstant()));
            List<String> maxStalenessReferences = List.of(
                    VALUE_REFERENCE.formatted(RefreshKey.MAX_STALENESS.minimumConstant()),
                    VALUE_REFERENCE.formatted(RefreshKey.MAX_STALENESS.maximumConstant()));

            String refreshIntervalSection = paramSection(exchanged, RefreshKey.REFRESH_INTERVAL.component)
                    .orElseThrow();
            String maxStalenessSection = paramSection(exchanged, RefreshKey.MAX_STALENESS.component).orElseThrow();

            assertAll(
                    () -> assertEquals(List.of(), missingBoundReferences(exchanged, RefreshKey.REFRESH_INTERVAL),
                            "the whole-Javadoc search finds every exchanged reference and so reports nothing"),
                    () -> assertEquals(List.of(), missingBoundReferences(exchanged, RefreshKey.MAX_STALENESS),
                            "the whole-Javadoc search finds every exchanged reference and so reports nothing"),
                    () -> assertEquals(refreshIntervalReferences,
                            missingBoundReferences(refreshIntervalSection, RefreshKey.REFRESH_INTERVAL),
                            "the refresh-interval section must be reported for both of its own references"),
                    () -> assertEquals(maxStalenessReferences,
                            missingBoundReferences(maxStalenessSection, RefreshKey.MAX_STALENESS),
                            "the max-staleness section must be reported for both of its own references"),
                    () -> assertFalse(maxStalenessSection.contains("@since"),
                            "a section must end at the next block tag"));
        }

        @Test
        @DisplayName("a section ends at the end of the Javadoc, and an undocumented component has none")
        void endsASectionAtTheEndOfTheJavadocAndReportsAnAbsentOne() {
            String lastSection = JAVADOC_LINE_PREFIX.matcher("""
                     * @param maxStalenessSeconds
                     *        the staleness bound.
                    """).replaceAll("");

            Optional<String> present = paramSection(lastSection, RefreshKey.MAX_STALENESS.component);
            Optional<String> absent = paramSection(lastSection, RefreshKey.REFRESH_INTERVAL.component);

            assertAll(
                    () -> assertEquals(Optional.of(lastSection), present,
                            "a section without a following block tag must run to the end of the Javadoc"),
                    () -> assertEquals(Optional.empty(), absent,
                            "a component the Javadoc does not document must yield no section"));
        }

        @Test
        @DisplayName("the Javadoc states no range by hand")
        void theJavadocStatesNoRangeByHand() throws Exception {
            List<String> handWritten = handWrittenRanges(jwksJavadoc());

            assertEquals(List.of(), handWritten,
                    () -> "the IssuerConfig.Jwks Javadoc restates a range instead of rendering it: " + handWritten);
        }

        @Test
        @DisplayName("a hand-written range is reported, a rendered one is not (matched negative control)")
        void reportsAHandWrittenRangeAndNotARenderedOne() {
            String handWritten = JAVADOC_LINE_PREFIX.matcher("""
                     *        default of 600 seconds. Admitted
                     *        range: 60 to 86400. Declared on a
                    """).replaceAll("");
            String rendered = JAVADOC_LINE_PREFIX.matcher("""
                     *        default of 600 seconds. Admitted
                     *        range:
                     *        {@value de.cuioss.sheriff.gateway.config.validation.rule.TokenValidationRules#X}
                    """).replaceAll("");

            assertAll(
                    () -> assertEquals(List.of("range: 60"), handWrittenRanges(handWritten),
                            "a range written across a line break must still be found"),
                    () -> assertEquals(List.of(), handWrittenRanges(rendered),
                            "a range rendered through {@value} must not be reported"));
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

            assertEquals(List.of(RefreshKey.REFRESH_INTERVAL.pointer(0), RefreshKey.UNKNOWN_KID_REFRESH_WINDOW.pointer(0),
                            RefreshKey.KEY_ROTATION_GRACE_PERIOD.pointer(1)),
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
