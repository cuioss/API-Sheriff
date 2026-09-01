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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the three context-path keys the shipped {@code application.properties} declares — the
 * application root, the non-application root, and the management root — as an explicit
 * <strong>declaration in the packaged artifact</strong>, paired with the effective value the running
 * container resolves for each.
 * <p>
 * <strong>Why a value-only assertion would be worthless here.</strong> All three keys are declared at
 * their current effective Quarkus defaults, so asserting only the resolved value is unfalsifiable by
 * deletion: remove {@code quarkus.http.root-path} from the shipped file and the container still
 * answers {@code /}, because the framework's own {@code @WithDefault} supplies the identical value.
 * The test would stay green while the declaration it exists to protect had silently evaporated. The
 * declaration assertion below is what makes deleting any one of the three keys turn this class RED.
 *
 * <h2>Do not reach for {@code ConfigValue.getSourceName()} here — it cannot tell the two apart</h2>
 *
 * The obvious-looking approach is to resolve each key through {@code ConfigProvider} and assert the
 * acting config source names the shipped {@code application.properties} rather than the
 * {@code @WithDefault} fallback. <strong>That does not work, and the reason is structural rather than
 * incidental.</strong> All three keys sit on Quarkus {@code *BuildTimeConfig} interfaces, so each is
 * fixed at <em>augmentation</em> and its value is replayed at runtime from the synthetic
 * {@code BuildTime RunTime Fixed} config source, which carries {@link Integer#MAX_VALUE} ordinal and
 * therefore wins over every properties file. Every one of the three consequently reports
 * {@code getSourceName() == "BuildTime RunTime Fixed"} at runtime — <em>whether the value was read
 * from the shipped file or fell through to the framework default</em>, because augmentation resolves
 * both into the same recorded source. A source-name assertion is thus green in exactly the case it
 * was added to catch. This was observed, not assumed: the first form of this class asserted on the
 * source name and all three cases failed against that literal.
 * <p>
 * The packaged file is therefore read directly, the same technique — and for the same "assert the
 * artifact, not the harness" reason — that {@link ShippedApplicationPropertiesTest} uses.
 *
 * <h2>Why the keys are declared at all, given they change no behaviour</h2>
 *
 * Being augmentation-fixed also means the matching environment variables are <em>inert</em> against an
 * already-built image: setting {@code QUARKUS_HTTP_ROOT_PATH} on a container started from a published
 * artifact does nothing. Changing a context path always costs a rebuild, driven through the Maven
 * build seam in {@code api-sheriff/pom.xml} ({@code -Dsheriff.context-path},
 * {@code -Dsheriff.management-context-path}). Declaring the keys makes the acting values explicit at
 * the one place an operator reads them; see the comment block above the declarations in
 * {@code src/main/resources/application.properties} and {@code doc/user/context-path.adoc}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@DisplayName("Context paths: declared in the packaged artifact, and effective at runtime")
class ContextPathDefaultsTest {

    /**
     * The PACKAGED file, resolved relative to the module basedir — Surefire's working directory. Not
     * {@code src/main/resources/...}, and deliberately not
     * {@code getResourceAsStream("/application.properties")}: on the Surefire classpath
     * {@code target/test-classes} precedes {@code target/classes}, so the resource call returns the
     * TEST properties file and every assertion below would pin the harness instead of the artifact.
     * The regular-file precondition is the paired safeguard.
     */
    private static final Path PACKAGED_APPLICATION_PROPERTIES = Path.of("target/classes/application.properties");

    @ParameterizedTest(name = "{0} is declared as {1}")
    @CsvSource({
            "quarkus.http.root-path, /",
            "quarkus.http.non-application-root-path, q",
            "quarkus.management.root-path, /q"
    })
    @DisplayName("Each context-path key is declared in the packaged artifact AND effective at runtime")
    void shouldDeclareContextPathInPackagedArtifactAndResolveItAtRuntime(String key, String expectedValue)
            throws IOException {
        assertTrue(Files.isRegularFile(PACKAGED_APPLICATION_PROPERTIES),
                () -> "expected the packaged properties file at "
                        + PACKAGED_APPLICATION_PROPERTIES.toAbsolutePath()
                        + " (module basedir is Surefire's working directory). It is missing, so the declaration "
                        + "assertion below would assert over nothing — fix the build layout rather than "
                        + "relaxing the guard");

        Properties packaged = packagedProperties();

        assertAll("declaration and effective value of " + key,
                () -> assertEquals(expectedValue, packaged.getProperty(key),
                        () -> key + " must be DECLARED in the packaged application.properties with the value '"
                                + expectedValue + "'; found " + rendered(packaged.getProperty(key)) + ". This is "
                                + "the assertion that makes deleting the key turn this test red — the runtime "
                                + "value cannot do it, because the framework default supplies the same string. "
                                + "Restore the declaration rather than relaxing the assertion"),
                () -> assertEquals(expectedValue, ConfigProvider.getConfig().getValue(key, String.class),
                        () -> key + " is declared as '" + expectedValue + "' but the running container resolves "
                                + "a different effective value. Some higher-ordinal source is overriding the "
                                + "shipped declaration, so the declared value is not the acting one"));
    }

    /**
     * Matched positive/negative control over the declaration lookup the primary assertion depends on.
     * Without it that assertion is unfalsifiable by inspection: it is green today because the keys are
     * present, and it would be equally green if the lookup were broken and reported the expected value
     * for everything. The control pins both directions — a declared key is found with its exact value,
     * and an absent key reads as absent rather than as some default.
     */
    @Test
    @DisplayName("Control: the declaration lookup finds a declared key and reports an absent one as absent")
    void shouldDistinguishDeclaredKeyFromAbsentKey() throws IOException {
        Properties probe = new Properties();
        try (Reader reader = new StringReader("""
                # a comment naming quarkus.management.root-path
                quarkus.http.root-path=/
                """)) {
            probe.load(reader);
        }

        assertAll("declaration lookup control",
                () -> assertEquals("/", probe.getProperty("quarkus.http.root-path"),
                        "a declared key must be found with its exact value, or the primary assertion cannot "
                                + "tell a present declaration from an absent one"),
                () -> assertNull(probe.getProperty("quarkus.management.root-path"),
                        "a key that appears only inside a COMMENT must read as absent; if it did not, deleting "
                                + "a real declaration while leaving its documentation behind would keep the "
                                + "primary assertion green"));
    }

    /** @return the packaged {@code application.properties}, parsed with the properties-format rules */
    private static Properties packagedProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(PACKAGED_APPLICATION_PROPERTIES)) {
            properties.load(reader);
        }
        return properties;
    }

    /** @return the value quoted, or an explicit marker when the key is not declared at all */
    private static String rendered(String value) {
        return value == null ? "no declaration at all" : "'" + value + "'";
    }
}
