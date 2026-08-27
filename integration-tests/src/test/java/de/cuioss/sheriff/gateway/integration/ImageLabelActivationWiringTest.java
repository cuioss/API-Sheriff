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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed Compose descriptor actually
 * <strong>activates</strong> the build args the image's OCI provenance labels are emitted from.
 * <p>
 * This pins a hop the image itself cannot show. {@code ImageMetadataIT} reads the labels off the built
 * image and proves the values arrived, but it can only run where a native image has been built and
 * Docker is available. This test covers the one link in the chain that is pure declaration: the
 * release lane performs <em>no</em> {@code docker build} of its own — the harness Compose build is the
 * single image build — so the {@code api-sheriff} service's {@code build.args} is the ONLY channel
 * reaching the Dockerfile's {@code ARG APP_VERSION} / {@code ARG APP_REVISION}. Drop an entry here and
 * the Dockerfile's declared default silently wins: the released image would label itself {@code dev}
 * while every step still succeeds.
 * <p>
 * The asserted form is the passthrough literal {@code ${VAR:-dev}} rather than merely "the key is
 * present". The default half is load-bearing in both directions: it is what keeps a local or PR build
 * honestly labelled {@code dev}, and it is what {@code ImageMetadataIT} resolves its expected values
 * against, so the two tests agree on one rule instead of two.
 * <p>
 * The arg set is <strong>derived from {@code Dockerfile.native} at run time</strong>, never mirrored in
 * a constant here. A hardcoded list cannot notice the one change that matters — growth. Add
 * {@code ARG APP_CREATED} plus {@code LABEL org.opencontainers.image.created="${APP_CREATED}"} to the
 * Dockerfile and forget the Compose {@code build.args} entry, and a mirrored list would still pass
 * green while the published image silently took the Dockerfile's {@code dev} default. Deriving the
 * expectation from the file that defines it makes that omission a failure instead.
 * <p>
 * The asserted direction is "every label-backed {@code ARG} is forwarded". The converse — a
 * {@code build.args} entry no label consumes — is deliberately not asserted: it is inert, whereas the
 * missing direction is the silent-{@code dev} defect this guard exists for.
 * <p>
 * It parses the committed descriptor and the committed Dockerfile only — it starts no container and
 * reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class ImageLabelActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The service whose build supplies the published image. */
    private static final String IMAGE_SERVICE = "api-sheriff";

    /**
     * The Dockerfile that DEFINES the provenance-label arg set. The expected set is derived from this
     * file at run time rather than mirrored in a constant here — see the class Javadoc.
     */
    private static final Path DOCKERFILE =
            MODULE.resolve("../api-sheriff/src/main/docker/Dockerfile.native").normalize();

    /** Matches {@code ARG NAME} / {@code ARG NAME=default}, capturing the declared arg name. */
    private static final Pattern ARG_DECLARATION =
            Pattern.compile("^\\s*ARG\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Matches a {@code LABEL} instruction line. */
    private static final Pattern LABEL_DECLARATION = Pattern.compile("^\\s*LABEL\\s");

    /** Matches a {@code ${VAR}} reference, capturing the variable name. */
    private static final Pattern VARIABLE_REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)[^}]*}");

    @Test
    @DisplayName("the api-sheriff build declares every provenance-label arg in the ${VAR:-dev} form")
    void imageBuildDeclaresEveryProvenanceLabelArg() throws Exception {
        Set<String> labelArgs = labelBackedArgs();
        Map<String, Object> args = declaredBuildArgs();

        // Vacuity guard on the DERIVED side: a parser that found nothing would make the loop below
        // assert nothing at all, and the test would pass while checking no wiring whatsoever.
        assertFalse(labelArgs.isEmpty(),
                () -> "no label-backed ARG was derived from " + DOCKERFILE + " — either the parser broke"
                        + " or the Dockerfile moved. Until this resolves, the passthrough wiring below is"
                        + " unchecked, so this is a failure rather than a pass.");

        // The vacuity guard: an empty map would satisfy the per-key loop below by never running it.
        assertFalse(args.isEmpty(),
                "the '" + IMAGE_SERVICE + "' service must declare build.args — without them the"
                        + " Dockerfile's `dev` defaults win and the published image labels itself `dev`");

        for (String arg : labelArgs) {
            Object declared = args.get(arg);
            assertNotNull(declared, () -> "the '" + IMAGE_SERVICE + "' service must declare build.args."
                    + arg + ": the release lane runs no docker build of its own, so build.args is the"
                    + " only channel reaching the Dockerfile ARG");
            assertEquals("${" + arg + ":-dev}", String.valueOf(declared),
                    () -> "build.args." + arg + " must use the ${" + arg + ":-dev} passthrough form so an"
                            + " unset or empty variable falls back to `dev` — a local or PR image must"
                            + " never carry a release-shaped value");
        }
    }

    /**
     * Derives the provenance-label arg set from {@link #DOCKERFILE}: the variables its {@code LABEL}
     * instructions interpolate, intersected with the {@code ARG}s it declares.
     * <p>
     * The intersection is what makes the set mean "label-backed build arg" rather than merely "some
     * variable a label mentions" — a {@code LABEL} referencing an undeclared variable is not something
     * Compose can forward, and an {@code ARG} no label consumes is not part of the provenance chain.
     *
     * @return the derived arg names, in declaration order
     * @throws IOException when the Dockerfile cannot be read
     */
    private static Set<String> labelBackedArgs() throws IOException {
        assertTrue(Files.isRegularFile(DOCKERFILE),
                () -> "cannot derive the provenance-label arg set: " + DOCKERFILE + " does not exist."
                        + " This test resolves it relative to the module root (" + MODULE + "); if the"
                        + " Dockerfile moved, point DOCKERFILE at its new location rather than falling"
                        + " back to a hardcoded arg list.");

        Set<String> declaredArgs = new LinkedHashSet<>();
        Set<String> referencedByLabels = new LinkedHashSet<>();
        for (String line : Files.readAllLines(DOCKERFILE)) {
            Matcher arg = ARG_DECLARATION.matcher(line);
            if (arg.find()) {
                declaredArgs.add(arg.group(1));
            } else if (LABEL_DECLARATION.matcher(line).find()) {
                Matcher reference = VARIABLE_REFERENCE.matcher(line);
                while (reference.find()) {
                    referencedByLabels.add(reference.group(1));
                }
            }
        }
        referencedByLabels.retainAll(declaredArgs);
        return referencedByLabels;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaredBuildArgs() throws IOException {
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(MODULE.resolve("docker-compose.yml"))) {
            doc = new Yaml().loadAs(in, Map.class);
        }
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        Object service = ((Map<String, Object>) services).get(IMAGE_SERVICE);
        assertNotNull(service, "docker-compose.yml must declare the '" + IMAGE_SERVICE + "' service");
        Object build = ((Map<String, Object>) service).get("build");
        assertInstanceOf(Map.class, build,
                "the '" + IMAGE_SERVICE + "' service must declare a build section");
        Object args = ((Map<String, Object>) build).get("args");
        assertInstanceOf(Map.class, args,
                "the '" + IMAGE_SERVICE + "' service build.args must be a mapping, not a list — the mapping"
                        + " form is what lets a value carry the ${VAR:-dev} passthrough default");
        return (Map<String, Object>) args;
    }
}
