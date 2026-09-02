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
 * <h2>Why the declared value and the effective value are separate columns</h2>
 *
 * The two configurable keys are declared as <em>expressions</em> over carrier keys —
 * {@code ${quarkus.sheriff-context-path:/}} and {@code ${quarkus.sheriff-mgmt-path:/q}} — rather than
 * as plain literals. That indirection is what makes the Maven build seam able to act at all:
 * forwarded build-system properties land at ordinal 100 while this file is ordinal 250 and wins, so a
 * seam that set {@code quarkus.http.root-path} directly would be overridden by the declaration it
 * means to change. Declaring the winning value as a reference inverts that. The consequence here is
 * that the raw declared string and the resolved runtime value are no longer the same text, so the
 * parameter set carries both: the first pins the indirection against being flattened back to a
 * literal, the second pins that an unconfigured build still serves the stock path.
 *
 * <h2>{@code quarkus.http.root-path} reads back as {@code //} here, and that is not a defect to fix</h2>
 *
 * Quarkus normalises {@code quarkus.http.root-path} by prepending a {@code /} when the value does not
 * already begin with one, and in the <em>{@code @QuarkusTest} augmentation path</em> it applies that
 * normalisation to the <strong>raw</strong> string, before the expression is expanded.
 * {@code ${quarkus.sheriff-context-path:/}} does not begin with {@code /}, so it becomes
 * {@code /${quarkus.sheriff-context-path:/}} and expands to {@code //}. The expectation below states
 * that measured value rather than the intuitive {@code /}.
 * <p>
 * <strong>The shipped artifact is unaffected</strong>, which is what keeps this an honest
 * characterisation rather than a rubber stamp over a bug. The {@code quarkus-maven-plugin}
 * augmentation expands first and normalises after: {@code BuildTimeRunTimeFixedConfigSourceBuilder}
 * inside {@code target/quarkus-app/quarkus/generated-bytecode.jar} records {@code /} for a default
 * build and {@code /gw} for {@code -Dsheriff.context-path=/gw}, and the Vert.x router is mounted at
 * {@code /} and {@code /q/} — byte-identical to the literal-valued declaration this replaced. Only
 * the test harness's own read-back of this one key diverges; {@code quarkus.management.root-path}
 * carries no such normaliser and reads back cleanly as {@code /q}.
 * <p>
 * <strong>If this assertion goes red, read the alternatives before changing it.</strong> Both were
 * built and measured, and both are worse. Flattening the value to a plain literal disables the build
 * seam outright — {@code -Dsheriff.context-path=/gw} then produces zero occurrences of {@code /gw} in
 * the generated bytecode, silently, with every build green. Emptying the default ({@code ${…:}}) does
 * clear the {@code //}, but the packaged artifact then records the empty string and the effective
 * {@code /} comes back from the framework's own {@code @WithDefault} — reintroducing exactly the
 * implicitness this class exists to remove. A red here most likely means Quarkus fixed its ordering,
 * in which case the expectation becomes {@code /} and this section can go.
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

    @ParameterizedTest(name = "{0} is declared as {1} and resolves to {2}")
    @CsvSource({
            "quarkus.http.root-path, '${quarkus.sheriff-context-path:/}', //",
            "quarkus.http.non-application-root-path, q, q",
            "quarkus.management.root-path, '${quarkus.sheriff-mgmt-path:/q}', /q"
    })
    @DisplayName("Each context-path key is declared in the packaged artifact AND effective at runtime")
    void shouldDeclareContextPathInPackagedArtifactAndResolveItAtRuntime(String key, String declaredValue,
            String effectiveValue) throws Exception {
        assertTrue(Files.isRegularFile(PACKAGED_APPLICATION_PROPERTIES),
                () -> "expected the packaged properties file at "
                        + PACKAGED_APPLICATION_PROPERTIES.toAbsolutePath()
                        + " (module basedir is Surefire's working directory). It is missing, so the declaration "
                        + "assertion below would assert over nothing — fix the build layout rather than "
                        + "relaxing the guard");

        Properties packaged = packagedProperties();

        assertAll("declaration and effective value of " + key,
                () -> assertEquals(declaredValue, packaged.getProperty(key),
                        () -> key + " must be DECLARED in the packaged application.properties with the RAW value '"
                                + declaredValue + "'; found " + rendered(packaged.getProperty(key)) + ". This is "
                                + "the assertion that makes deleting the key turn this test red — the runtime "
                                + "value cannot do it, because the framework default supplies the same string. "
                                + "It is also the assertion that pins the CARRIER-KEY INDIRECTION: the two "
                                + "configurable keys are declared as expressions over quarkus.sheriff-context-path "
                                + "/ quarkus.sheriff-mgmt-path precisely so the Maven build seam can act on them, "
                                + "and flattening either back to a plain literal silently disables "
                                + "-Dsheriff.context-path while leaving every build green. Restore the "
                                + "declaration rather than relaxing the assertion"),
                () -> assertEquals(effectiveValue, ConfigProvider.getConfig().getValue(key, String.class),
                        () -> key + " is declared as '" + declaredValue + "' but the running container resolves "
                                + "a different effective value than the expected '" + effectiveValue + "'. With no "
                                + "carrier supplied, the expression's own inline default is the acting value, so "
                                + "either a higher-ordinal source is overriding the shipped declaration or the "
                                + "inline default has drifted from the stock path. NOTE for "
                                + "quarkus.http.root-path specifically: the expected value here is '//' rather "
                                + "than '/', because the @QuarkusTest augmentation normalises this ONE key by "
                                + "prepending '/' to the RAW string before the expression is expanded. That is a "
                                + "harness-only artifact — the packaged artifact records '/' and the router mounts "
                                + "at '/' — and it is characterised deliberately. Read the class javadoc before "
                                + "changing this expectation: both obvious 'fixes' were measured and both are "
                                + "worse than the value being asserted"));
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
    void shouldDistinguishDeclaredKeyFromAbsentKey() throws Exception {
        Properties probe = new Properties();
        try (Reader reader = Reader.of("""
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
