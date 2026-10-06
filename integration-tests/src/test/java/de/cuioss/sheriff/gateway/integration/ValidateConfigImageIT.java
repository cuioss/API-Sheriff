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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the offline configuration check through the <strong>real native distroless image</strong> and
 * asserts its exit-code contract: {@code 0} for a valid set, {@code 1} for a set carrying a violation,
 * {@code 2} for a missing directory argument.
 * <p>
 * The check is a pre-boot branch of the native executable, answered before Quarkus starts. Unit tests
 * prove the branch on the JVM; only the built image proves that the native executable reaches it, that
 * the loader, schema and binding registrations the boot relies on suffice before {@code Quarkus.run},
 * and that nothing is bound on the way. Every case therefore runs the image with
 * {@code --validate-config} appended to its entrypoint, with no network attached, and asserts that the
 * container output carries no listener announcement and that the container exits within a short
 * bound.
 * <p>
 * The valid case mounts the integration-test {@code sheriff-config} set with exactly the placeholder
 * variables the compose {@code api-sheriff} service supplies. The invalid case writes a world-readable
 * set carrying one validator violation, and its expected {@code INVALID} line is computed by running
 * the same pipeline seam in this JVM, so the native report is held to the JVM verdict line for line.
 * <p>
 * Each case uses a unique container name and removes its container and temporary fixture afterwards.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Offline configuration check on the native image")
class ValidateConfigImageIT {

    /** The configuration directory every compose gateway mounts, relative to the module root. */
    private static final Path SHERIFF_CONFIG = OneOffGatewayContainers.DOCKER.resolve("sheriff-config");

    /** Where the image reads the configuration directory the check is pointed at. */
    private static final String CONTAINER_CONFIG_DIR = "/app/sheriff-config";

    /**
     * The placeholder variables the compose {@code api-sheriff} service supplies to the
     * {@code sheriff-config} set — the DPoP key path its {@code gateway.yaml} names, and the portal
     * catalog toggle its {@code portal-hidden} endpoint reads.
     */
    private static final Map<String, String> COMPOSE_PLACEHOLDER_VARIABLES = Map.of(
            "OIDC_DPOP_KEY_FILE", "/app/signing-keys/dpop-ec.pem",
            "ENDPOINT_PORTAL_HIDDEN_ENABLED", "false");

    /** The bound within which a pre-boot branch that binds nothing must have exited. */
    private static final Duration EXIT_BOUND = Duration.ofSeconds(30);

    private static final List<String> LISTENER_ANNOUNCEMENTS = List.of("Listening on",
            "Management interface listening on");

    private static final String INVALID_GATEWAY = """
            version: 1
            anchors:
              portal:
                path_prefix: /portal
                type: bff
                access: public
            """;

    private final List<String> containers = new ArrayList<>();
    private final List<Path> fixtures = new ArrayList<>();

    /** The outcome of one container run: its exit code, merged output and wall-clock duration. */
    private record CheckRun(int exitCode, String output, Duration elapsed) {
    }

    @AfterEach
    void removeContainersAndFixtures() throws IOException {
        for (String container : containers) {
            OneOffGatewayContainers.dockerQuietly("rm", "-f", container);
        }
        for (Path fixture : fixtures) {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * Runs the image once with the given configuration mount, environment and check arguments, waits
     * for it to exit and reads its output.
     *
     * @param configMount the host directory mounted read-only at {@value #CONTAINER_CONFIG_DIR}, or
     *                    {@code null} to mount nothing
     * @param environment the environment handed to the container
     * @param checkArgs   the arguments appended to the image entrypoint
     * @return the exit code, merged output and duration of the run
     */
    private CheckRun runCheck(Path configMount, Map<String, String> environment, String... checkArgs) {
        String container = "api-sheriff-validate-config-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
        containers.add(container);
        List<String> arguments = new ArrayList<>(List.of("run", "-d", "--name", container,
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true"));
        environment.forEach((name, value) -> {
            arguments.add("-e");
            arguments.add(name + "=" + value);
        });
        if (configMount != null) {
            arguments.add("-v");
            arguments.add(configMount.toAbsolutePath() + ":" + CONTAINER_CONFIG_DIR + ":ro");
        }
        arguments.add(OneOffGatewayContainers.IMAGE);
        arguments.addAll(List.of(checkArgs));

        long started = System.nanoTime();
        OneOffGatewayContainers.docker("start the offline check " + container, arguments.toArray(String[]::new));
        String exitCode = OneOffGatewayContainers.docker("wait for the offline check " + container, "wait", container);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        String output = OneOffGatewayContainers.docker("read the offline check output " + container, "logs",
                container);
        return new CheckRun(Integer.parseInt(exitCode.strip()), output, elapsed);
    }

    private static void assertBoundNothing(CheckRun run) {
        assertAll("the pre-boot branch binds nothing",
                () -> assertTrue(run.elapsed().compareTo(EXIT_BOUND) < 0,
                        () -> "the check took " + run.elapsed() + ", beyond " + EXIT_BOUND + ": " + run.output()),
                () -> LISTENER_ANNOUNCEMENTS.forEach(announcement -> assertFalse(run.output().contains(announcement),
                        () -> "the check announced a listener ('" + announcement + "'): " + run.output())));
    }

    private Path writeWorldReadableSet(String gatewayYaml) throws IOException {
        Path dir = Files.createTempDirectory("api-sheriff-validate-config-");
        fixtures.add(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path gateway = Files.writeString(dir.resolve("gateway.yaml"), gatewayYaml);
        Files.setPosixFilePermissions(gateway, PosixFilePermissions.fromString("rw-r--r--"));
        return dir;
    }

    @Test
    @DisplayName("the integration-test configuration set exits 0 and prints the result line")
    void validSetExitsZero() {
        CheckRun run = runCheck(SHERIFF_CONFIG, COMPOSE_PLACEHOLDER_VARIABLES, "--validate-config",
                CONTAINER_CONFIG_DIR);

        assertAll("valid set",
                () -> assertEquals(0, run.exitCode(), () -> "the shipped set must validate: " + run.output()),
                () -> assertTrue(run.output().lines().anyMatch(line -> line.startsWith("RESULT: violations=0 ")),
                        () -> "the report must end in a clean RESULT line: " + run.output()),
                () -> assertTrue(run.output().lines().noneMatch(line -> line.startsWith("INVALID ")),
                        () -> "no violation is printed for the shipped set: " + run.output()));
        assertBoundNothing(run);
    }

    @Test
    @DisplayName("a set carrying a validator violation exits 1 and prints the JVM pipeline's INVALID line")
    void invalidSetExitsOne() throws Exception {
        Path invalidSet = writeWorldReadableSet(INVALID_GATEWAY);
        List<String> expected = new ConfigBootPipeline(new EnvSecretResolver(Map.<String, String>of()::get))
                .run(invalidSet, null).violations().stream()
                .map(ValidateConfigImageIT::invalidLine).toList();

        CheckRun run = runCheck(invalidSet, Map.of(), "--validate-config", CONTAINER_CONFIG_DIR);

        List<String> printed = run.output().lines().filter(line -> line.startsWith("INVALID ")).toList();
        assertAll("invalid set",
                () -> assertEquals(1, run.exitCode(), () -> "a violation must exit 1: " + run.output()),
                () -> assertFalse(expected.isEmpty(), "the fixture must violate a validator rule"),
                () -> assertEquals(expected, printed, "the native report must match the JVM pipeline verdict"),
                () -> assertTrue(printed.stream()
                                .anyMatch(line -> line.contains("is type 'bff' and must declare access: authenticated")),
                        () -> "the access/auth matrix violation is reported: " + printed));
        assertBoundNothing(run);
    }

    @Test
    @DisplayName("the flag without a directory argument exits 2")
    void missingDirectoryExitsTwo() {
        CheckRun run = runCheck(null, Map.of(), "--validate-config");

        assertAll("usage error",
                () -> assertEquals(2, run.exitCode(), () -> "a usage error must exit 2: " + run.output()),
                () -> assertTrue(run.output().contains("missing configuration directory"),
                        () -> "the usage error names the problem: " + run.output()));
        assertBoundNothing(run);
    }

    private static String invalidLine(ConfigError violation) {
        return "INVALID " + violation.file() + " [" + violation.pointer() + "]: " + violation.message();
    }
}
