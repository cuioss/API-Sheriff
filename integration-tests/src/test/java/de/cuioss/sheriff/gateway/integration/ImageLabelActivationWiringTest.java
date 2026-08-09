/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * It parses the committed descriptor only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class ImageLabelActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The service whose build supplies the published image. */
    private static final String IMAGE_SERVICE = "api-sheriff";

    /** The build args carrying the OCI provenance label values into the Dockerfile. */
    private static final List<String> LABEL_ARGS = List.of("APP_VERSION", "APP_REVISION");

    @Test
    @DisplayName("the api-sheriff build declares every provenance-label arg in the ${VAR:-dev} form")
    void imageBuildDeclaresEveryProvenanceLabelArg() throws Exception {
        Map<String, Object> args = buildArgs(IMAGE_SERVICE);

        // The vacuity guard: an empty map would satisfy the per-key loop below by never running it.
        assertFalse(args.isEmpty(),
                "the '" + IMAGE_SERVICE + "' service must declare build.args — without them the"
                        + " Dockerfile's `dev` defaults win and the published image labels itself `dev`");

        for (String arg : LABEL_ARGS) {
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
     * The declared {@code build.args} map of one Compose service.
     *
     * @param service the Compose service name
     * @return the declared build args, never {@code null}
     * @throws IOException when the descriptor cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildArgs(String service) throws IOException {
        Object node = composeServices().get(service);
        assertNotNull(node, "docker-compose.yml must declare the '" + service + "' service");
        Map<String, Object> serviceMap = (Map<String, Object>) node;
        Object build = serviceMap.get("build");
        assertInstanceOf(Map.class, build, "the '" + service + "' service must declare a build section");
        Object args = ((Map<String, Object>) build).get("args");
        assertInstanceOf(Map.class, args,
                "the '" + service + "' service build.args must be a mapping, not a list — the mapping form"
                        + " is what lets a value carry the ${VAR:-dev} passthrough default");
        return (Map<String, Object>) args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> doc = loadYaml(MODULE.resolve("docker-compose.yml"));
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
