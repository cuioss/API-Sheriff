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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed deployment descriptors actually
 * <strong>activate</strong> the framework body-size floor the gateway's per-route
 * {@code max_body_bytes} caps sit under.
 * <p>
 * This closes a unit-green / integration-red blind spot. {@code ConfigProducer}'s fail-closed
 * framework-limit check and both body-cap raise sites are unit-covered and correct in isolation, but
 * the floor itself is a <em>property</em>: {@code quarkus.http.limits.max-body-size} in
 * {@code application.properties}. Remove it and Vert.x silently reverts to its 10 MiB default,
 * clipping every larger declared cap before the gateway pipeline ever runs; raise a declared cap
 * past it and the boot check aborts that container. Neither failure is visible to a per-component
 * unit test — only to a descriptor assertion like this one, or to the expensive container suite.
 * <p>
 * The coverage is deliberately <strong>all committed descriptors, not just the base one</strong>:
 * the compose stack boots five native gateway instances over four {@code sheriff-config*} gateway
 * descriptors ({@code api-sheriff}, {@code api-sheriff-mtls}, {@code api-sheriff-cookie},
 * {@code api-sheriff-cookie-2} and {@code api-sheriff-ws-admission}), so a cap raised in any sibling
 * descriptor pushes that instance into a boot abort. The descriptors are discovered by glob rather
 * than hard-coded, so a new {@code sheriff-config-*} directory comes under the assertion
 * automatically — and a glob that matches fewer than the four present today fails rather than
 * passing vacuously.
 * <p>
 * It parses the committed descriptors only (YAML / properties text) and asserts the activation is
 * present — it starts no container and reaches no network. The containerised sibling that exercises
 * the resulting 413 end-to-end is {@code LargeBodyIT}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BodyLimitActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path APPLICATION_PROPERTIES =
            MODULE.getParent().resolve("api-sheriff/src/main/resources/application.properties");

    private static final String FRAMEWORK_LIMIT_KEY = "quarkus.http.limits.max-body-size";
    private static final String CONTAINER_OVERRIDE_KEY = "QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE";
    private static final String DECLARED_CAP_KEY = "max_body_bytes";

    /**
     * The descriptor count committed today. The glob must match at least this many, so an empty or
     * mis-rooted glob fails loudly instead of satisfying the per-descriptor loop vacuously.
     */
    private static final int COMMITTED_DESCRIPTOR_COUNT = 4;

    /** {@code LargeBodyIT}'s negative-case body size — the container override must exceed it. */
    private static final long NEGATIVE_CASE_BODY_BYTES = 71303168L;

    @Test
    @DisplayName("application.properties declares the framework body-size floor")
    void applicationPropertiesDeclaresTheFrameworkLimit() throws Exception {
        // Arrange
        Properties properties = applicationProperties();

        // Act
        String declared = properties.getProperty(FRAMEWORK_LIMIT_KEY);

        // Assert — its absence is the exact defect this plan fixes: Vert.x then applies its 10 MiB
        // default and silently clips every larger declared max_body_bytes.
        assertNotNull(declared, FRAMEWORK_LIMIT_KEY
                + " must be declared in application.properties, otherwise Vert.x applies its 10 MiB default"
                + " and clips every larger declared max_body_bytes before the gateway pipeline runs");
        assertTrue(parseMemorySize(declared) > 0,
                FRAMEWORK_LIMIT_KEY + " must parse to a positive byte count, was: " + declared);
    }

    @Test
    @DisplayName("the framework floor covers the largest declared cap in every committed descriptor")
    void frameworkLimitCoversEveryCommittedDescriptor() throws Exception {
        // Arrange
        long floor = parseMemorySize(applicationProperties().getProperty(FRAMEWORK_LIMIT_KEY));
        List<Path> descriptors = committedGatewayDescriptors();

        // Act + Assert — the glob must actually find the committed descriptors before the loop below
        // can mean anything.
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);

        // The same strict inequality ConfigProducer's boot check enforces per booted instance —
        // caught here, before a native image is ever built.
        for (Path descriptor : descriptors) {
            long declared = maxDeclaredBodyCap(loadYaml(descriptor));
            assertTrue(declared <= floor,
                    descriptor + " declares max_body_bytes " + declared + ", which exceeds the framework floor "
                            + floor + " (" + FRAMEWORK_LIMIT_KEY
                            + "). That instance would abort its boot on the fail-closed check —"
                            + " raise the floor or lower the declared cap.");
        }
    }

    @Test
    @DisplayName("the IT container override exceeds LargeBodyIT's negative-case body size")
    void containerOverrideExceedsTheNegativeCaseBody() throws Exception {
        // Arrange
        List<String> environment = environment(composeServices(), "api-sheriff");

        // Act
        String override = environment.stream()
                .filter(entry -> entry.startsWith(CONTAINER_OVERRIDE_KEY + "="))
                .map(entry -> entry.substring(CONTAINER_OVERRIDE_KEY.length() + 1))
                .findFirst()
                .orElse(null);

        // Assert — this coupling is what keeps LargeBodyIT honest. Dropping or lowering the override
        // would let the Quarkus root handler answer the oversize request with a bare 413 before the
        // gateway pipeline runs, silently turning the IT into an assertion about the framework
        // rather than about the gateway's RFC 9457 envelope.
        assertNotNull(override, "the api-sheriff service must set " + CONTAINER_OVERRIDE_KEY
                + " so the gateway — not the Quarkus root handler — rejects LargeBodyIT's oversize body");
        long overrideBytes = parseMemorySize(override);
        assertTrue(overrideBytes > NEGATIVE_CASE_BODY_BYTES,
                CONTAINER_OVERRIDE_KEY + " is " + overrideBytes + " but must be strictly greater than "
                        + NEGATIVE_CASE_BODY_BYTES + " (LargeBodyIT's negative-case body size), otherwise the"
                        + " framework pre-check shadows the gateway's own 413 envelope");
    }

    /**
     * Every committed gateway descriptor under a {@code sheriff-config} directory, discovered by glob
     * so a new instance directory is covered automatically.
     *
     * @return the descriptor paths, in directory-stream order
     * @throws IOException when the docker directory cannot be listed
     */
    private static List<Path> committedGatewayDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve("gateway.yaml");
                if (Files.isRegularFile(descriptor)) {
                    descriptors.add(descriptor);
                }
            }
        }
        return descriptors;
    }

    /**
     * The largest {@code max_body_bytes} declared anywhere in a descriptor. The whole document tree is
     * walked rather than only the {@code anchors} block, so a cap declared at any nesting depth —
     * including a future route-level one — is covered.
     *
     * @param node the parsed YAML node
     * @return the maximum declared cap in bytes, or {@code 0} when none is declared
     */
    private static long maxDeclaredBodyCap(Object node) {
        long max = 0L;
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (DECLARED_CAP_KEY.equals(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof Number cap) {
                    max = Math.max(max, cap.longValue());
                } else {
                    max = Math.max(max, maxDeclaredBodyCap(entry.getValue()));
                }
            }
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                max = Math.max(max, maxDeclaredBodyCap(item));
            }
        }
        return max;
    }

    /**
     * Parses a Quarkus {@code MemorySize} literal — a plain byte count, or a number with a binary
     * {@code K} / {@code M} / {@code G} suffix (optionally {@code Ki} / {@code Mi} / {@code Gi}).
     *
     * @param value the declared literal
     * @return the value in bytes
     */
    private static long parseMemorySize(String value) {
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1L;
        int end = trimmed.length();
        if (trimmed.endsWith("I")) {
            end--;
        }
        if (end > 0) {
            multiplier = switch (trimmed.charAt(end - 1)) {
                case 'K' -> 1024L;
                case 'M' -> 1024L * 1024L;
                case 'G' -> 1024L * 1024L * 1024L;
                default -> 1L;
            };
        }
        String digits = multiplier == 1L ? trimmed.substring(0, end) : trimmed.substring(0, end - 1);
        return Long.parseLong(digits.trim()) * multiplier;
    }

    private static Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(APPLICATION_PROPERTIES)) {
            properties.load(reader);
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> doc = loadYaml(MODULE.resolve("docker-compose.yml"));
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    @SuppressWarnings("unchecked")
    private static List<String> environment(Map<String, Object> services, String service) {
        Object node = services.get(service);
        assertNotNull(node, "docker-compose.yml must declare the '" + service + "' service");
        Map<String, Object> serviceMap = (Map<String, Object>) node;
        Object env = serviceMap.get("environment");
        assertInstanceOf(List.class, env, "the '" + service + "' service environment must be a list");
        List<String> entries = ((List<?>) env).stream().map(String::valueOf).toList();
        assertFalse(entries.isEmpty(), "the '" + service + "' service environment must not be empty");
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
