/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;


import de.cuioss.sheriff.api.config.load.EnvSecretResolver.MalformedPlaceholderException;
import de.cuioss.sheriff.api.config.load.EnvSecretResolver.MissingVariableException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnvSecretResolver}, the single D4 substitution engine: reference
 * detection, bare-reference classification for the secrets rule, required
 * {@code ${NAME}} and optional {@code ${NAME:-default}} substitution, multiple
 * placeholders per scalar, the loud missing-variable and malformed-placeholder
 * boot failures, and secret-leak safety of the failure messages.
 */
@EnableGeneratorController
class EnvSecretResolverTest {

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
        assertEquals("s3cr3t", resolver.resolve("${SECRET}"));
    }

    @Test
    void resolvesEmbeddedReference() {
        EnvSecretResolver resolver = resolverWith(Map.of("HOST", "example.com"));
        assertEquals("https://example.com/callback", resolver.resolve("https://${HOST}/callback"));
    }

    @Test
    void resolvesMultipleReferencesInOneScalar() {
        EnvSecretResolver resolver = resolverWith(Map.of("USER", "sheriff", "PASS", "pw"));
        assertEquals("sheriff:pw", resolver.resolve("${USER}:${PASS}"));
    }

    @Test
    void passesPlainValueThroughUnchanged() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("plain-value", resolver.resolve("plain-value"));
    }

    @Test
    @DisplayName("An optional ${NAME:-default} applies its literal default when the variable is unset")
    void appliesLiteralDefaultWhenVariableUnset() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("http://localhost:8080", resolver.resolve("${BASE:-http://localhost:8080}"));
    }

    @Test
    @DisplayName("An environment value wins over the ${NAME:-default} literal when the variable is set")
    void environmentValueWinsOverDefaultWhenSet() {
        EnvSecretResolver resolver = resolverWith(Map.of("BASE", "https://prod.internal"));
        assertEquals("https://prod.internal", resolver.resolve("${BASE:-http://localhost:8080}"));
    }

    @Test
    @DisplayName("An empty default (${NAME:-}) resolves to the empty string when the variable is unset")
    void appliesEmptyDefaultWhenVariableUnset() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("prefix-", resolver.resolve("prefix-${TAIL:-}"));
    }

    @Test
    @DisplayName("Required and optional placeholders mix within a single scalar")
    void mixesRequiredAndOptionalPlaceholdersInOneScalar() {
        EnvSecretResolver resolver = resolverWith(Map.of("HOST", "orders.internal"));
        assertEquals("https://orders.internal:9000", resolver.resolve("https://${HOST}:${PORT:-9000}"));
    }

    @Test
    @DisplayName("A required ${NAME} that names an unset variable fails the boot")
    void throwsNamingTheMissingVariable() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${ABSENT}"));
        assertEquals("ABSENT", exception.variableName());
    }

    @Test
    @DisplayName("A scalar carrying a ${ that is not a well-formed placeholder fails the boot")
    void throwsOnMalformedPlaceholder() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertThrows(MalformedPlaceholderException.class, () -> resolver.resolve("unterminated ${OPEN"));
    }

    @Test
    @DisplayName("A ${ with an invalid variable name is malformed, not a silent literal")
    void throwsOnPlaceholderWithInvalidName() {
        EnvSecretResolver resolver = resolverWith(Map.of("VALID", "ok"));
        assertThrows(MalformedPlaceholderException.class, () -> resolver.resolve("${VALID}-${1INVALID}"));
    }

    @Test
    @DisplayName("A ${ nested inside a ${NAME:-default} default is malformed, never a silently resolved literal")
    void throwsOnNestedPlaceholderInsideDefault() {
        EnvSecretResolver resolver = resolverWith(Map.of("B", "inner-value"));
        assertThrows(MalformedPlaceholderException.class, () -> resolver.resolve("${A:-${B}}"));
    }

    @Test
    void defaultConstructorResolvesPlainValueWithoutTouchingEnvironment() {
        assertEquals("plain", new EnvSecretResolver().resolve("plain"));
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
                () -> resolver.resolve("${USER}:${PASS}@${ABSENT}"));

        String message = exception.getMessage();
        assertEquals("ABSENT", exception.variableName());
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
                () -> resolver.resolve("${SECRET}/${MISSING}"));

        assertEquals("MISSING", exception.variableName());
        assertFalse(exception.getMessage().contains(trickySecret),
                () -> "message must not leak the resolved secret: " + exception.getMessage());
    }
}
