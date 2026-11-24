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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * JVM-lane guard over the native-image metadata the {@code token-sheriff-client} jar ships for itself.
 * <p>
 * <strong>Why this test exists.</strong> The gateway carries no reflection holder and no runtime-init
 * entry for engine classes: from token-sheriff 0.9.10 the client jar brings both under
 * {@code META-INF/native-image/}, and GraalVM applies them from the classpath. Two engine needs depend
 * on that metadata, and neither is visible on the JVM:
 * <ul>
 *   <li>DSL-JSON finds a generated {@code _<Type>_DslJsonConverter} by class name through a reflective
 *       {@code loadClass}. On the JVM the lookup always succeeds; in the native image it fails unless the
 *       converter is in {@code reflect-config.json}. Two of those failures are silent and fail open: an
 *       unreadable token-endpoint error body turns an {@code invalid_grant} refusal into a transport
 *       failure, so the BFF keeps a session the identity provider refused, and an unreadable access-token
 *       payload makes the DPoP issuance check skip its {@code cnf.jkt} comparison.</li>
 *   <li>A class holding a {@code static final SecureRandom} must be initialized at run time, or GraalVM
 *       either fails the build or bakes a seeded generator into the image heap. The engine's own
 *       {@code native-image.properties} carries that list.</li>
 * </ul>
 * <p>
 * This test reads the real jar on the test classpath and asserts the metadata covers both: every
 * generated converter the jar ships (enumerated from the jar, not frozen here) is named in its
 * {@code reflect-config.json}, the two fail-open converters are named explicitly, and every engine class
 * with a {@code static final SecureRandom} is covered by an {@code --initialize-at-run-time} entry of its
 * {@code native-image.properties}. A non-vacuity check proves each input resolves to something, and the
 * negative controls prove each matcher fails on a name the list lacks. The native integration tests
 * remain the proof in the image; this test moves the signal for an engine bump to the JVM lane.
 */
class TokenClientNativeMetadataTest {

    /** The package tree of the client engine. */
    private static final String ENGINE_PACKAGE = "de.cuioss.sheriff.token.client";

    /**
     * Jar-entry path of a generated DSL-JSON converter inside the client engine, nested-type converters
     * ({@code _Outer$Inner_DslJsonConverter}) included. The converters' own nested
     * {@code $ObjectFormatConverter} classes end in another suffix and never match.
     */
    private static final Pattern CONVERTER_ENTRY =
            Pattern.compile("de/cuioss/sheriff/token/client/(?:[^/]+/)*_[^/]+_DslJsonConverter\\.class");

    /** The directory GraalVM reads embedded native-image metadata from. */
    private static final String METADATA_ROOT = "META-INF/native-image/";

    private static final String REFLECT_CONFIG = "reflect-config.json";

    private static final String NATIVE_IMAGE_PROPERTIES = "native-image.properties";

    /** One {@code "name": "<fqcn>"} member of a reflect-config.json entry. */
    private static final Pattern REFLECTION_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private static final String RUNTIME_INIT_PREFIX = "--initialize-at-run-time=";

    /**
     * Fails open when unregistered: the engine's DPoP issuance check reads the access token through it,
     * and an unreadable payload is treated as "not a JWT", which skips the {@code cnf.jkt} comparison.
     */
    private static final String NESTED_PROBE_CONVERTER =
            "de.cuioss.sheriff.token.client.internal._AccessTokenConfirmation$JsonObjectProbe_DslJsonConverter";

    /**
     * Fails open when unregistered: an unreadable {@code 4xx} token-endpoint body carries no error code, so
     * an {@code invalid_grant} refusal is reported as a transport failure.
     */
    private static final String TOKEN_ERROR_RESPONSE_CONVERTER =
            "de.cuioss.sheriff.token.client.token._TokenErrorResponse_DslJsonConverter";

    @Test
    @DisplayName("Should name every generated DSL-JSON converter of the engine jar in its reflect-config.json")
    void shouldRegisterEveryEngineConverterForReflection() throws Exception {
        // Arrange
        EngineNativeMetadata metadata = readEngineJar();

        // Act
        Set<String> missing = missingConverters(metadata.converters(), metadata.reflectionNames());

        // Assert
        assertTrue(missing.isEmpty(),
                """
                        %d generated DSL-JSON converter(s) of token-sheriff-client are absent from the jar's \
                        own reflect-config.json (%s): %s. DSL-JSON resolves these by reflection; on the JVM \
                        that works, in the native image the response type becomes unreadable. This is an \
                        engine defect: report it upstream rather than restoring a gateway-side registration.\
                        """.formatted(missing.size(), metadata.metadataFiles(), missing));
    }

    @Test
    @DisplayName("Should name the two fail-open converters in the engine reflect-config.json")
    void shouldRegisterTheFailOpenConverters() throws Exception {
        // Arrange
        EngineNativeMetadata metadata = readEngineJar();

        // Act / Assert
        assertAll("fail-open converters registered by the engine",
                () -> assertTrue(metadata.reflectionNames().contains(NESTED_PROBE_CONVERTER),
                        NESTED_PROBE_CONVERTER + " is not in the engine reflect-config.json — without it a native "
                                + "image skips the DPoP cnf.jkt binding check"),
                () -> assertTrue(metadata.reflectionNames().contains(TOKEN_ERROR_RESPONSE_CONVERTER),
                        TOKEN_ERROR_RESPONSE_CONVERTER + " is not in the engine reflect-config.json — without it "
                                + "a native image reads every invalid_grant refusal as a transport failure"));
    }

    @Test
    @DisplayName("Should initialize every engine class holding a static final SecureRandom at run time")
    void shouldInitializeEveryEngineSecureRandomHolderAtRunTime() throws Exception {
        // Arrange
        EngineNativeMetadata metadata = readEngineJar();
        Set<String> holders = engineStaticSecureRandomHolders();

        // Act
        Set<String> uncovered = uncoveredHolders(holders, metadata.runtimeInitEntries());

        // Assert
        assertTrue(uncovered.isEmpty(),
                """
                        %d engine class(es) hold a static final SecureRandom but no %s entry of the jar's \
                        native-image.properties (%s) covers them: %s. GraalVM would initialize them at build \
                        time and bake a seeded generator into the image heap. Declared entries: %s.\
                        """.formatted(uncovered.size(), RUNTIME_INIT_PREFIX, metadata.metadataFiles(), uncovered,
                        metadata.runtimeInitEntries()));
    }

    /**
     * Guards the three tests above: any input that resolved to nothing would leave them green while
     * checking nothing — a jar without metadata files, a converter enumeration that found no class, a
     * reflect-config.json the parser read no name from, or a SecureRandom scan that matched no field.
     */
    @Test
    @DisplayName("Should read a non-empty converter set, metadata files and SecureRandom holders from the jar")
    void shouldReadNonEmptyEngineMetadata() throws Exception {
        // Arrange / Act
        EngineNativeMetadata metadata = readEngineJar();
        Set<String> holders = engineStaticSecureRandomHolders();

        // Assert
        assertAll("engine native-image metadata is non-vacuous",
                () -> assertFalse(metadata.metadataFiles().isEmpty(),
                        "the client engine jar carries no " + REFLECT_CONFIG + " or " + NATIVE_IMAGE_PROPERTIES
                                + " under " + METADATA_ROOT),
                () -> assertFalse(metadata.converters().isEmpty(),
                        "the client engine jar must yield at least one generated converter"),
                () -> assertTrue(metadata.converters().contains(TOKEN_ERROR_RESPONSE_CONVERTER),
                        "the enumeration must find " + TOKEN_ERROR_RESPONSE_CONVERTER + ", found: "
                                + metadata.converters()),
                () -> assertTrue(metadata.converters().contains(NESTED_PROBE_CONVERTER),
                        "the enumeration must reach nested-type converters, found: " + metadata.converters()),
                () -> assertFalse(metadata.reflectionNames().isEmpty(),
                        "no name was parsed out of the engine " + REFLECT_CONFIG),
                () -> assertFalse(metadata.runtimeInitEntries().isEmpty(),
                        "no " + RUNTIME_INIT_PREFIX + " entry was parsed out of the engine " + NATIVE_IMAGE_PROPERTIES),
                () -> assertFalse(holders.isEmpty(),
                        "no engine class with a static final SecureRandom was found — the scan matched nothing"));
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        @Test
        @DisplayName("Converter matcher reports a converter missing from the list (negative control)")
        void converterMatcherFailsOnMissingConverter() throws Exception {
            // Arrange
            Set<String> converters = readEngineJar().converters();
            Set<String> listed = new TreeSet<>(converters);
            listed.remove(TOKEN_ERROR_RESPONSE_CONVERTER);
            Set<String> parsed = parseReflectionNames(reflectConfigNaming(listed));

            // Act
            Set<String> missing = missingConverters(converters, parsed);

            // Assert
            assertEquals(Set.of(TOKEN_ERROR_RESPONSE_CONVERTER), missing,
                    "the matcher must report exactly the converter left out of the list");
        }

        @Test
        @DisplayName("Converter matcher accepts a list naming every converter (positive control)")
        void converterMatcherAcceptsCompleteList() throws Exception {
            // Arrange
            Set<String> converters = readEngineJar().converters();
            Set<String> parsed = parseReflectionNames(reflectConfigNaming(converters));

            // Act
            Set<String> missing = missingConverters(converters, parsed);

            // Assert
            assertTrue(missing.isEmpty(), "a list naming every converter must leave nothing missing: " + missing);
        }

        @Test
        @DisplayName("Runtime-init matcher reports a holder no entry covers (negative control)")
        void runtimeInitMatcherFailsOnUncoveredHolder() throws Exception {
            // Arrange
            Set<String> holders = engineStaticSecureRandomHolders();
            Set<String> entries = parseRuntimeInitEntries(
                    "Args=" + RUNTIME_INIT_PREFIX + "de.cuioss.sheriff.token.client.unrelated.Type");

            // Act
            Set<String> uncovered = uncoveredHolders(holders, entries);

            // Assert
            assertEquals(holders, uncovered, "an entry naming an unrelated class must cover no holder");
        }

        @Test
        @DisplayName("Runtime-init matcher accepts entries naming every holder (positive control)")
        void runtimeInitMatcherAcceptsCompleteList() throws Exception {
            // Arrange
            Set<String> holders = engineStaticSecureRandomHolders();
            Set<String> entries = parseRuntimeInitEntries("Args=" + RUNTIME_INIT_PREFIX + String.join(",", holders));

            // Act
            Set<String> uncovered = uncoveredHolders(holders, entries);

            // Assert
            assertTrue(uncovered.isEmpty(), "entries naming every holder must leave nothing uncovered: " + uncovered);
        }
    }

    /** What one walk over the engine jar yields. */
    private record EngineNativeMetadata(Set<String> converters, Set<String> reflectionNames,
    Set<String> runtimeInitEntries, List<String> metadataFiles) {
    }

    private static Set<String> missingConverters(Set<String> converters, Set<String> registered) {
        Set<String> missing = new TreeSet<>(converters);
        missing.removeAll(registered);
        return missing;
    }

    /**
     * A holder is covered when an entry names it exactly, or names a package containing it — GraalVM reads a
     * bare package name as a prefix over that package.
     */
    private static Set<String> uncoveredHolders(Set<String> holders, Set<String> entries) {
        return holders.stream()
                .filter(holder -> entries.stream().noneMatch(entry -> isWithin(holder, entry)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> parseReflectionNames(String reflectConfig) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = REFLECTION_NAME.matcher(reflectConfig);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Reads the {@code Args} value of a native-image.properties file and keeps the class and package names
     * of its {@code --initialize-at-run-time=} arguments. Arguments are whitespace-separated, and one
     * argument may carry a comma-separated list.
     */
    private static Set<String> parseRuntimeInitEntries(String nativeImageProperties) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(nativeImageProperties));
        String args = properties.getProperty("Args", "");
        return Arrays.stream(args.trim().split("\\s+"))
                .filter(argument -> argument.startsWith(RUNTIME_INIT_PREFIX))
                .flatMap(argument -> Arrays.stream(argument.substring(RUNTIME_INIT_PREFIX.length()).split(",")))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Renders names in the shape the engine's reflect-config.json uses, for the matcher controls. */
    private static String reflectConfigNaming(Set<String> names) {
        return names.stream()
                .map(name -> "  {\n    \"name\": \"" + name + "\",\n    \"allDeclaredConstructors\": true\n  }")
                .collect(Collectors.joining(",\n", "[\n", "\n]"));
    }

    private static EngineNativeMetadata readEngineJar() throws IOException, URISyntaxException {
        Path location = engineLocation();
        if (Files.isDirectory(location)) {
            return readUnder(location);
        }
        try (FileSystem jar = FileSystems.newFileSystem(location)) {
            return readUnder(jar.getPath("/"));
        }
    }

    private static EngineNativeMetadata readUnder(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> entries = Files.walk(root)) {
            files = entries.filter(Files::isRegularFile).toList();
        }
        Set<String> converters = new TreeSet<>();
        Set<String> reflectionNames = new TreeSet<>();
        Set<String> runtimeInitEntries = new TreeSet<>();
        List<String> metadataFiles = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String fileName = file.getFileName().toString();
            if (CONVERTER_ENTRY.matcher(relative).matches()) {
                converters.add(relative.substring(0, relative.length() - ".class".length()).replace('/', '.'));
            } else if (relative.startsWith(METADATA_ROOT) && REFLECT_CONFIG.equals(fileName)) {
                reflectionNames.addAll(parseReflectionNames(Files.readString(file)));
                metadataFiles.add(relative);
            } else if (relative.startsWith(METADATA_ROOT) && NATIVE_IMAGE_PROPERTIES.equals(fileName)) {
                runtimeInitEntries.addAll(parseRuntimeInitEntries(Files.readString(file)));
                metadataFiles.add(relative);
            }
        }
        return new EngineNativeMetadata(converters, reflectionNames, runtimeInitEntries, List.copyOf(metadataFiles));
    }

    /**
     * The engine classes declaring a {@code static final SecureRandom}, read from bytecode so that no
     * engine class is loaded or initialized by the scan itself.
     */
    private static Set<String> engineStaticSecureRandomHolders() throws IOException, URISyntaxException {
        Path location = engineLocation();
        JavaClasses classes;
        if (Files.isDirectory(location)) {
            classes = new ClassFileImporter().importPath(location);
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                classes = new ClassFileImporter().importJar(jar);
            }
        }
        return classes.stream()
                .filter(javaClass -> isWithin(javaClass.getPackageName(), ENGINE_PACKAGE))
                .flatMap(javaClass -> javaClass.getFields().stream())
                .filter(TokenClientNativeMetadataTest::isStaticFinalSecureRandom)
                .map(field -> field.getOwner().getFullName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isStaticFinalSecureRandom(JavaField field) {
        return field.getRawType().isEquivalentTo(SecureRandom.class)
                && field.getModifiers().contains(JavaModifier.STATIC)
                && field.getModifiers().contains(JavaModifier.FINAL);
    }

    /** The code source the client engine is loaded from: its jar, or a class directory in an IDE run. */
    private static Path engineLocation() throws URISyntaxException {
        CodeSource codeSource = TokenEndpointClient.class.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, "the client engine must be loaded from a locatable code source");
        return Path.of(codeSource.getLocation().toURI());
    }

    /** {@code candidate} is {@code prefix}, or sits beneath it in the dotted namespace. */
    private static boolean isWithin(String candidate, String prefix) {
        return candidate.equals(prefix) || candidate.startsWith(prefix + ".");
    }
}
