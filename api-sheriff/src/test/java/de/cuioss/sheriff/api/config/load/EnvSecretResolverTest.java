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


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.cuioss.sheriff.api.config.load.EnvSecretResolver.MissingVariableException;
import de.cuioss.test.generator.Generators;

/**
 * Tests for {@link EnvSecretResolver}: reference detection, single / embedded /
 * multiple substitution, pass-through of plain values, and the missing-variable
 * failure.
 */
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
    void resolvesMultipleReferences() {
        EnvSecretResolver resolver = resolverWith(Map.of("USER", "sheriff", "PASS", "pw"));
        assertEquals("sheriff:pw", resolver.resolve("${USER}:${PASS}"));
    }

    @Test
    void passesPlainValueThroughUnchanged() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        assertEquals("plain-value", resolver.resolve("plain-value"));
    }

    @Test
    void throwsNamingTheMissingVariable() {
        EnvSecretResolver resolver = resolverWith(Map.of());
        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${ABSENT}"));
        assertEquals("ABSENT", exception.variableName());
    }

    @Test
    void defaultConstructorResolvesPlainValueWithoutTouchingEnvironment() {
        assertEquals("plain", new EnvSecretResolver().resolve("plain"));
    }

    @Test
    @DisplayName("Missing-variable failure names the variable but leaks no resolved secret value")
    void exceptionMessageLeaksNoResolvedSecretValue() {
        // Arrange: two present secrets that resolve before an absent reference triggers the failure
        String userSecret = Generators.letterStrings(12, 20).next();
        String passSecret = Generators.letterStrings(12, 20).next();
        EnvSecretResolver resolver = resolverWith(Map.of("USER", userSecret, "PASS", passSecret));

        // Act
        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${USER}:${PASS}@${ABSENT}"));

        // Assert: the failure names only the missing variable, never the resolved secrets
        String message = exception.getMessage();
        assertEquals("ABSENT", exception.variableName());
        assertTrue(message.contains("ABSENT"), "message should name the missing variable");
        assertFalse(message.contains(userSecret), () -> "message must not leak the resolved USER secret: " + message);
        assertFalse(message.contains(passSecret), () -> "message must not leak the resolved PASS secret: " + message);
    }

    @Test
    @DisplayName("Failure after a metacharacter-bearing secret still leaks no resolved value")
    void exceptionMessageLeaksNoSecretContainingReplacementMetacharacters() {
        // Arrange: a resolved secret carrying regex-replacement metacharacters ($ and \)
        String trickySecret = "p$ss\\w0rd-" + Generators.letterStrings(6, 12).next();
        EnvSecretResolver resolver = resolverWith(Map.of("SECRET", trickySecret));

        // Act
        MissingVariableException exception = assertThrows(MissingVariableException.class,
                () -> resolver.resolve("${SECRET}/${MISSING}"));

        // Assert
        assertEquals("MISSING", exception.variableName());
        assertFalse(exception.getMessage().contains(trickySecret),
                () -> "message must not leak the resolved secret: " + exception.getMessage());
    }
}
