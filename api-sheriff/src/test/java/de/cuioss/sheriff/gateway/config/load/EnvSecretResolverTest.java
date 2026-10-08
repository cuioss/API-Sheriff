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
package de.cuioss.sheriff.gateway.config.load;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver.MalformedPlaceholderException;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver.MissingVariableException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnvSecretResolver}, the single D4 substitution engine: reference
 * detection, bare-reference classification for the secrets rule, required
 * {@code ${NAME}} and optional {@code ${NAME:-default}} substitution, multiple
 * placeholders per scalar, the loud missing-variable failure naming every unset
 * variable of a scalar, the malformed-placeholder boot failure, the name-only
 * reporting of applied in-file defaults, and secret-leak safety of the failure
 * messages.
 */
@EnableGeneratorController
class EnvSecretResolverTest {

    /** The consumer of a test that asserts the substituted value or the failure, not the defaulted names. */
    private static final Consumer<String> IGNORE_DEFAULTED = name -> {
        // Defaulted names are not what these tests assert.
    };

    private static EnvSecretResolver resolverWith(Map<String, String> environment) {
        return new EnvSecretResolver(environment::get);
    }

    @Test
    void detectsPresenceOfReference() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertTrue(resolver.hasReference("prefix-${TOKEN}-suffix"));
        assertFalse(resolver.hasReference("no-reference-here"));
    }

    @Test
    void resolvesSingleReference() {
        EnvSecretResolver resolver = resolverWith(Map.of("SECRET", "s3cr3t"));
        assertEquals("s3cr3t", resolver.resolve("${SECRET}", IGNORE_DEFAULTED));
    }

    @Test
    void resolvesEmbeddedReference() {
        EnvSecretResolver resolver = resolverWith(Map.of("HOST", "example.com"));
        assertEquals("https://example.com/callback", resolver.resolve("https://${HOST}/callback", IGNORE_DEFAULTED));
    }

    @Test
    void resolvesMultipleReferencesInOneScalar() {
        EnvSecretResolver resolver = resolverWith(Map.of("USER", "sheriff", "PASS", "pw"));
        assertEquals("sheriff:pw", resolver.resolve("${USER}:${PASS}", IGNORE_DEFAULTED));
    }

    @Test
    void passesPlainValueThroughUnchanged() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("plain-value", resolver.resolve("plain-value", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("An optional ${NAME:-default} applies its literal default when the variable is unset")
    void appliesLiteralDefaultWhenVariableUnset() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("http://localhost:8080", resolver.resolve("${BASE:-http://localhost:8080}", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("An environment value wins over the ${NAME:-default} literal when the variable is set")
    void environmentValueWinsOverDefaultWhenSet() {
        EnvSecretResolver resolver = resolverWith(Map.of("BASE", "https://prod.internal"));
        assertEquals("https://prod.internal", resolver.resolve("${BASE:-http://localhost:8080}", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("An empty default (${NAME:-}) resolves to the empty string when the variable is unset")
    void appliesEmptyDefaultWhenVariableUnset() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("prefix-", resolver.resolve("prefix-${TAIL:-}", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("Required and optional placeholders mix within a single scalar")
    void mixesRequiredAndOptionalPlaceholdersInOneScalar() {
        EnvSecretResolver resolver = resolverWith(Map.of("HOST", "orders.internal"));
        assertEquals("https://orders.internal:9000",
                resolver.resolve("https://${HOST}:${PORT:-9000}", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("A required ${NAME} that names an unset variable fails the boot")
    void throwsNamingTheMissingVariable() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${ABSENT}", IGNORE_DEFAULTED));
        assertEquals(List.of("ABSENT"), exception.variableNames());
        assertEquals("Unresolved environment variable: ABSENT", exception.getMessage(),
                "the single-variable message keeps its established wording");
    }

    @Test
    @DisplayName("A scalar with two unset required variables names both, in occurrence order")
    void throwsNamingEveryMissingVariableInOrder() {
        EnvSecretResolver resolver = resolverWith(Map.of());

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${FIRST}-${SECOND}", IGNORE_DEFAULTED));

        assertEquals(List.of("FIRST", "SECOND"), exception.variableNames());
        assertEquals("Unresolved environment variable: FIRST, SECOND", exception.getMessage());
    }

    @Test
    @DisplayName("A missing variable referenced twice in one scalar is named once")
    void namesRepeatedMissingVariableOnce() {
        EnvSecretResolver resolver = resolverWith(Map.of());

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${TWICE}/${TWICE}", IGNORE_DEFAULTED));

        assertEquals(List.of("TWICE"), exception.variableNames());
    }

    @Test
    @DisplayName("With one unset and one set required variable, only the unset one is named")
    void namesOnlyTheUnsetVariable() {
        String setValue = Generators.letterStrings(8, 16).next();
        EnvSecretResolver resolver = resolverWith(Map.of("PRESENT", setValue));

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${PRESENT}:${ABSENT}", IGNORE_DEFAULTED));

        assertEquals(List.of("ABSENT"), exception.variableNames());
        assertFalse(exception.getMessage().contains(setValue), "the set variable's value is never echoed");
    }

    @Test
    @DisplayName("An unset variable with a default never contributes to a missing-variable failure")
    void unsetVariableWithDefaultDoesNotThrow() {
        EnvSecretResolver resolver = resolverWith(Map.of());

        String resolved = assertDoesNotThrow(() -> resolver.resolve("${OPTIONAL:-fallback}", IGNORE_DEFAULTED));

        assertEquals("fallback", resolved);
    }

    @Test
    @DisplayName("resolve reports an unset ${A:-x} by its variable name")
    void trackingReportsDefaultedVariable() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        List<String> defaulted = new ArrayList<>();

        String resolved = resolver.resolve("${A:-x}", defaulted::add);

        assertEquals("x", resolved);
        assertEquals(List.of("A"), defaulted);
    }

    @Test
    @DisplayName("resolve reports nothing when the defaulted variable is set")
    void trackingReportsNothingForSetVariable() {
        EnvSecretResolver resolver = resolverWith(Map.of("A", Generators.letterStrings(4, 10).next()));
        List<String> defaulted = new ArrayList<>();

        resolver.resolve("${A:-x}", defaulted::add);

        assertTrue(defaulted.isEmpty(), () -> "a set variable applied no default, but got " + defaulted);
    }

    @Test
    @DisplayName("resolve reports a variable defaulted twice in one scalar once")
    void trackingReportsRepeatedDefaultOnce() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        List<String> defaulted = new ArrayList<>();

        String resolved = resolver.resolve("${A:-x}${A:-y}", defaulted::add);

        assertEquals("xy", resolved);
        assertEquals(List.of("A"), defaulted);
    }

    @Test
    @DisplayName("resolve reports defaults in first-occurrence order")
    void trackingReportsDefaultsInOccurrenceOrder() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        List<String> defaulted = new ArrayList<>();

        resolver.resolve("${SECOND:-2}${FIRST:-1}${SECOND:-3}", defaulted::add);

        assertEquals(List.of("SECOND", "FIRST"), defaulted);
    }

    @Test
    @DisplayName("A default applied before an unset bare variable is reported, then the scalar fails naming the bare one")
    void trackingReportsDefaultEvenWhenScalarLaterFails() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        List<String> defaulted = new ArrayList<>();

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${A:-x}${B}", defaulted::add));

        assertAll("default reported, bare variable named",
                () -> assertEquals(List.of("A"), defaulted),
                () -> assertEquals(List.of("B"), exception.variableNames()));
    }

    @Test
    @DisplayName("The tracking consumer never receives the default literal")
    void trackingNeverReceivesDefaultLiteral() {
        String sentinelDefault = "sentinel" + Generators.letterStrings(8, 16).next();
        EnvSecretResolver resolver = resolverWith(Map.of());
        List<String> defaulted = new ArrayList<>();

        String resolved = resolver.resolve("${SECRET_FALLBACK:-" + sentinelDefault + "}", defaulted::add);

        assertEquals(sentinelDefault, resolved);
        assertEquals(List.of("SECRET_FALLBACK"), defaulted);
        assertTrue(defaulted.stream().noneMatch(name -> name.contains(sentinelDefault)),
                () -> "the default literal must never reach the consumer: " + defaulted);
    }

    @Test
    @DisplayName("The substituted value is the same whether the consumer records the defaulted names or ignores them")
    void substitutionResultIsIndependentOfTheConsumer() {
        EnvSecretResolver resolver = resolverWith(Map.of("HOST", "orders.internal"));
        String raw = "https://${HOST}:${PORT:-9000}";
        List<String> defaulted = new ArrayList<>();

        String ignoring = resolver.resolve(raw, IGNORE_DEFAULTED);
        String recording = resolver.resolve(raw, defaulted::add);

        assertAll("same value, and the recording consumer saw the fallback",
                () -> assertEquals("https://orders.internal:9000", ignoring),
                () -> assertEquals(ignoring, recording),
                () -> assertEquals(List.of("PORT"), defaulted));
    }

    @Test
    @DisplayName("A scalar carrying a ${ that is not a well-formed placeholder fails the boot")
    void throwsOnMalformedPlaceholder() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertThrows(MalformedPlaceholderException.class,
                () -> resolver.resolve("unterminated ${OPEN", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("A ${ with an invalid variable name is malformed, not a silent literal")
    void throwsOnPlaceholderWithInvalidName() {
        EnvSecretResolver resolver = resolverWith(Map.of("VALID", "ok"));
        assertThrows(MalformedPlaceholderException.class,
                () -> resolver.resolve("${VALID}-${1INVALID}", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("A ${ nested inside a ${NAME:-default} default is malformed, never a silently resolved literal")
    void throwsOnNestedPlaceholderInsideDefault() {
        EnvSecretResolver resolver = resolverWith(Map.of("B", "inner-value"));
        assertThrows(MalformedPlaceholderException.class,
                () -> resolver.resolve("${A:-${B}}", IGNORE_DEFAULTED));
    }

    @Test
    void defaultConstructorResolvesPlainValueWithoutTouchingEnvironment() {
        assertEquals("plain", new EnvSecretResolver().resolve("plain", IGNORE_DEFAULTED));
    }

    @Test
    @DisplayName("isBareReference is true only for a single, un-defaulted ${VAR} covering the whole value")
    void classifiesBareReferences() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertTrue(resolver.isBareReference("${OIDC_CLIENT_SECRET}"), "a whole-value ${VAR} is a bare reference");
        assertFalse(resolver.isBareReference("literal-secret"), "a literal is not a bare reference");
        assertFalse(resolver.isBareReference("${VAR:-fallback}"), "a defaulted placeholder is not a bare reference");
        assertFalse(resolver.isBareReference("prefix-${VAR}"), "an embedded reference is not a bare reference");
        assertFalse(resolver.isBareReference("${A}${B}"), "two references are not a single bare reference");
    }

    @Test
    @DisplayName("Missing-variable failure names the variable but leaks no resolved secret value")
    void exceptionMessageLeaksNoResolvedSecretValue() {
        String userSecret = Generators.letterStrings(12, 20).next();
        String passSecret = Generators.letterStrings(12, 20).next();
        EnvSecretResolver resolver = resolverWith(Map.of("USER", userSecret, "PASS", passSecret));

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${USER}:${PASS}@${ABSENT}", IGNORE_DEFAULTED));

        String message = exception.getMessage();
        assertEquals(List.of("ABSENT"), exception.variableNames());
        assertTrue(message.contains("ABSENT"), "message should name the missing variable");
        assertFalse(message.contains(userSecret), () -> "message must not leak the resolved USER secret: " + message);
        assertFalse(message.contains(passSecret), () -> "message must not leak the resolved PASS secret: " + message);
    }

    @Test
    @DisplayName("Failure after a metacharacter-bearing secret still leaks no resolved value")
    void exceptionMessageLeaksNoSecretContainingReplacementMetacharacters() {
        String trickySecret = "p$ss\\w0rd-" + Generators.letterStrings(6, 12).next();
        EnvSecretResolver resolver = resolverWith(Map.of("SECRET", trickySecret));

        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${SECRET}/${MISSING}", IGNORE_DEFAULTED));

        assertEquals(List.of("MISSING"), exception.variableNames());
        assertFalse(exception.getMessage().contains(trickySecret),
                () -> "message must not leak the resolved secret: " + exception.getMessage());
    }
}
