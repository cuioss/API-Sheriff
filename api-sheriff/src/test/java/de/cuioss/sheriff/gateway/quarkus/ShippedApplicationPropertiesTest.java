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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Machine-asserts one half of the artifact-purity invariant: <strong>the packaged
 * {@code application.properties} declares no {@code %}-profile key</strong>. The shipped artifact
 * describes the product, not the harness that tests it — a {@code %it.} or {@code %dev.} branch puts
 * test material (in this repository's history, an integration-test truststore path <em>and its
 * password literal</em>) inside the file every production deployment receives, and it means the
 * configuration the tests exercise is not the configuration production runs.
 * <p>
 * <strong>Two details in this guard are load-bearing and must survive any refactoring.</strong>
 * <ul>
 * <li>It resolves the <em>packaged</em> file by path — {@code target/classes/application.properties}
 * — deliberately <em>not</em> through {@code getResourceAsStream("/application.properties")}. On the
 * Surefire classpath {@code target/test-classes} precedes {@code target/classes}, so the resource
 * call returns {@code src/test/resources/application.properties} instead: the guard would assert
 * against the test-profile file and pass green while the shipped artifact regressed. The
 * regular-file precondition below is the paired safeguard — a build-layout change fails loudly
 * rather than letting the guard pass over a file that is not there.</li>
 * <li>It keys on the <em>key</em>, never on "the line contains {@code %}".
 * {@code quarkus.log.file.format} legitimately carries {@code %d}, {@code %-5p} and {@code %s%e%n}
 * in its <em>value</em>, so a naive contains-check would false-positive on it forever and the guard
 * would be disabled within a week.</li>
 * </ul>
 * <p>
 * <strong>This guard is necessary but not sufficient, and a green result must not be over-read.</strong>
 * It sees {@code %}-prefixed keys in one file and nothing else. An <em>unprefixed</em> but
 * test-shaped key passes it while breaching the same intent — a {@code quarkus.tls.benchmark-idp.*}
 * bucket naming a benchmark-specific trust profile, or an IT-only issuer on the token extension's
 * namespace, are keys no production deployment has any use for, yet neither starts with {@code %}.
 * That is precisely why the {@code benchmark-idp} bucket was <em>relocated to the deployment that
 * names it</em> rather than merely un-prefixed: un-prefixing would have satisfied this guard while
 * leaving the test material in the shipped file. The second clause of the invariant — nothing
 * test-shaped in the shipped file, in any spelling — remains enforced by review. See
 * {@code doc/development/README.adoc}, section "Artifact Purity: No Test-Shaped Configuration in the
 * Shipped File".
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Shipped application.properties: artifact purity")
class ShippedApplicationPropertiesTest {

    /**
     * The PACKAGED file, resolved relative to the module basedir — Surefire's working directory. Not
     * {@code src/main/resources/...}: the packaged copy is what the artifact actually carries, so a
     * resource-filtering or profile-driven build step that reintroduced a branch would still be
     * caught here.
     */
    private static final Path PACKAGED_APPLICATION_PROPERTIES = Path.of("target/classes/application.properties");

    @Test
    @DisplayName("The packaged file declares no %-profile key")
    void packagedFileDeclaresNoProfileKey() throws Exception {
        assertTrue(Files.isRegularFile(PACKAGED_APPLICATION_PROPERTIES),
                () -> "expected the packaged properties file at " + PACKAGED_APPLICATION_PROPERTIES.toAbsolutePath()
                        + " (module basedir is Surefire's working directory). It is missing, so this guard would "
                        + "otherwise assert over nothing — fix the build layout rather than relaxing the guard, and "
                        + "do NOT reach for getResourceAsStream(\"/application.properties\"): target/test-classes "
                        + "precedes target/classes on the test classpath, so that call resolves the TEST properties "
                        + "file and the guard would pass green against the wrong artifact.");

        List<String> offendingKeys = profileKeysIn(Files.readAllLines(PACKAGED_APPLICATION_PROPERTIES));

        assertTrue(offendingKeys.isEmpty(),
                () -> "the shipped artifact declares " + offendingKeys.size() + " %-profile key(s): " + offendingKeys
                        + ". The shipped application.properties describes the product, not the harness that tests "
                        + "it — a profile branch ships test material to every production deployment and means the "
                        + "configuration the tests exercise is not the one production runs. Remove each key by the "
                        + "shape that fits: delete it outright when the product never reads that surface, relocate "
                        + "it to the deployment that names it when the material is genuinely needed, or flip the "
                        + "shipped default when the branch existed only to correct a default that was wrong "
                        + "unconfigured. Do NOT delete a branch while leaving the default it was correcting. See "
                        + "doc/development/README.adoc, section \"Artifact Purity: No Test-Shaped Configuration in "
                        + "the Shipped File\".");
    }

    /**
     * Matched positive/negative control over the detector itself. Without it the primary assertion
     * above is unfalsifiable-by-inspection: it is green today because the file is clean, and it would
     * be equally green if the key extraction were broken and flagged nothing at all.
     */
    @Test
    @DisplayName("The detector flags a %-profile KEY and ignores % inside a value")
    void detectorFlagsProfileKeysAndIgnoresPercentInsideValues() {
        List<String> lines = List.of(
                "# a comment naming %it.sheriff.token.issuers.it-static.url",
                "! a bang comment naming %dev.quarkus.log.file.enabled",
                "",
                "quarkus.log.file.format=%d{yyyy-MM-dd HH:mm:ss,SSS z} %-5p [%c{3.}] (%t) %s%e%n",
                "quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*",
                "quarkus.log.file.path=${LOG_FILE_PATH:/quarkus.log}",
                "%it.sheriff.token.issuers.it-static.url=https://keycloak:8443/realms/it",
                "%dev.quarkus.log.file.enabled:false");

        List<String> offendingKeys = profileKeysIn(lines);

        assertEquals(
                List.of("%it.sheriff.token.issuers.it-static.url", "%dev.quarkus.log.file.enabled"), offendingKeys,
                "the detector must flag a %-profile key whichever separator declares it, must not be fooled by a "
                        + "% inside a VALUE (quarkus.log.file.format) or inside a comment, and must not truncate a "
                        + "key at a colon that belongs to the value (quarkus.log.file.path)");
    }

    /**
     * The {@code %}-profile-prefixed keys among the supplied property lines, in file order. Blank
     * lines and both comment forms the properties format accepts ({@code #} and {@code !}) are
     * skipped.
     */
    private static List<String> profileKeysIn(List<String> lines) {
        return lines.stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
                .map(ShippedApplicationPropertiesTest::keyOf)
                .filter(key -> key.startsWith("%"))
                .toList();
    }

    /**
     * The key of a property line: everything before the first UNESCAPED {@code =} or {@code :}, or
     * the whole line when it carries no separator. A backslash escapes the following character, so a
     * key may legitimately contain {@code \=} or {@code \:}.
     */
    private static String keyOf(String line) {
        int index = 0;
        while (index < line.length()) {
            char current = line.charAt(index);
            if (current == '\\') {
                index += 2;
            } else if (current == '=' || current == ':') {
                return line.substring(0, index).strip();
            } else {
                index++;
            }
        }
        return line.strip();
    }
}
