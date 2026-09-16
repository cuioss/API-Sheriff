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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural guard over {@link TokenClientDslJsonReflection}'s {@link RegisterForReflection}
 * target list.
 * <p>
 * <strong>Why this test exists.</strong> DSL-JSON finds a generated
 * {@code _<Type>_DslJsonConverter} by class name through a reflective {@code loadClass}. On the
 * JVM that lookup always succeeds, so a converter missing from the target list is invisible to
 * every JVM gate. In the native image the same lookup fails, and for the token endpoint error
 * response the failure is silent: the engine maps an unreadable {@code 4xx} body to "no error
 * code", so an {@code invalid_grant} refusal is reported as a transport failure and a refresh-token
 * rejection is misclassified as {@code PRE_REDEMPTION}. That exact regression reached the native
 * integration suite when {@code token-sheriff-client} 0.9.5 introduced
 * {@code _TokenErrorResponse_DslJsonConverter}.
 * <p>
 * This test moves the signal to the JVM: it enumerates every generated converter class shipped in
 * the {@code token-sheriff-client} jar and asserts each one is a registration target, so the next
 * engine DTO added without a registration fails here instead of in the native run.
 */
class TokenClientDslJsonReflectionTest {

    /** Jar-entry path of a top-level generated DSL-JSON converter inside the client engine. */
    private static final Pattern CONVERTER_ENTRY =
            Pattern.compile("de/cuioss/sheriff/token/client/(?:[^/]+/)*_[^/$]+_DslJsonConverter\\.class");

    /** The converter whose absence caused the native credential-rejection misclassification. */
    private static final String TOKEN_ERROR_RESPONSE_CONVERTER =
            "de.cuioss.sheriff.token.client.token._TokenErrorResponse_DslJsonConverter";

    @Test
    @DisplayName("Should register every generated DSL-JSON converter shipped in the client engine jar")
    void shouldRegisterEveryEngineDslJsonConverter() throws IOException, URISyntaxException {
        // Arrange
        Set<String> registered = registeredTargetNames();
        Set<String> converters = engineConverterClassNames();

        // Act
        Set<String> missing = new TreeSet<>(converters);
        missing.removeAll(registered);

        // Assert
        assertTrue(missing.isEmpty(),
                """
                        %d generated DSL-JSON converter(s) of token-sheriff-client are absent from \
                        TokenClientDslJsonReflection's @RegisterForReflection targets: %s. \
                        DSL-JSON resolves these by reflection; on the JVM that works, in the native \
                        image the response type becomes unreadable. Add each one to the targets array.\
                        """.formatted(missing.size(), missing));
    }

    @Test
    @DisplayName("Should enumerate a non-empty converter set that includes the token error response converter")
    void shouldEnumerateTheEngineConvertersRatherThanNothing() throws IOException, URISyntaxException {
        // Arrange / Act
        Set<String> converters = engineConverterClassNames();

        // Assert — an enumeration that found nothing would make the registration guard vacuous
        assertFalse(converters.isEmpty(), "the client engine jar must yield at least one generated converter");
        assertTrue(converters.contains(TOKEN_ERROR_RESPONSE_CONVERTER),
                "the enumeration must find " + TOKEN_ERROR_RESPONSE_CONVERTER + ", found: " + converters);
    }

    /** Reads the target list off the holder class; requires the annotation's RUNTIME retention. */
    private static Set<String> registeredTargetNames() {
        RegisterForReflection annotation =
                TokenClientDslJsonReflection.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "TokenClientDslJsonReflection must carry @RegisterForReflection");
        return Stream.of(annotation.targets()).map(Class::getName).collect(Collectors.toSet());
    }

    /**
     * Lists the fully qualified names of every top-level {@code _*_DslJsonConverter} class in the
     * code source that ships the client engine, skipping the nested {@code $ObjectFormatConverter}
     * classes.
     */
    private static Set<String> engineConverterClassNames() throws IOException, URISyntaxException {
        CodeSource codeSource = TokenEndpointClient.class.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, "the client engine must be loaded from a locatable code source");
        Path location = Path.of(codeSource.getLocation().toURI());
        if (Files.isDirectory(location)) {
            return converterNamesUnder(location);
        }
        try (FileSystem jar = FileSystems.newFileSystem(location)) {
            return converterNamesUnder(jar.getPath("/"));
        }
    }

    private static Set<String> converterNamesUnder(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            return entries
                    .map(entry -> root.relativize(entry).toString().replace('\\', '/'))
                    .filter(entry -> CONVERTER_ENTRY.matcher(entry).matches())
                    .map(entry -> entry.substring(0, entry.length() - ".class".length()).replace('/', '.'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
