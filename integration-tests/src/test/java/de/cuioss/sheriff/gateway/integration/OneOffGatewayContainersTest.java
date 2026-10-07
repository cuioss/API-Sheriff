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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.ReadOnlyMount;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fast, no-Docker <em>surefire</em> test of the parts of {@link OneOffGatewayContainers} that decide
 * what a one-off BFF gateway mounts: the refusal of an extra mount reaching into a path the harness
 * mounts itself, the assembly of a configuration directory below {@code target/}, and the
 * {@code docker run} argument list both feed.
 * <p>
 * Every case goes through the argument builder or a helper it calls, never through a start method, so
 * no docker daemon is needed and none is contacted. The committed {@code sheriff-config} directory is
 * read only; its file listing is compared before and after an assembly.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("One-off gateway harness — extra mounts and the assembled configuration directory")
class OneOffGatewayContainersTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path COMMITTED_CONFIGURATION = MODULE.resolve("src/main/docker/sheriff-config");

    /** Everything this test writes lives here. */
    private static final Path WORK = MODULE.resolve("target/one-off-gateway-harness-test");

    private static final String CONFIGURATION_MOUNT = "/app/sheriff-config";
    private static final String DESCRIPTOR_CONTENT = "# descriptor written by OneOffGatewayContainersTest\n";
    private static final String EXTRA_ENDPOINT = "harness-test-extra.yaml";

    private static Path writeReadableByOthers(String relative, String content) throws IOException {
        Path file = WORK.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        permissions.addAll(Files.getPosixFilePermissions(file));
        permissions.add(PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(file, permissions);
        return file;
    }

    private static List<String> listing(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            return entries.map(entry -> root.relativize(entry).toString()).sorted().toList();
        }
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    private static BffGateway gatewayOver(Path descriptorOrDirectory) {
        return new BffGateway("harness-test-gateway", "harness-test-network", "harness-test-alias", 10499,
                descriptorOrDirectory, "localhost.crt", "localhost.key", List.of("HARNESS_TEST=1"));
    }

    @Nested
    @DisplayName("Assembling a configuration directory")
    class ConfigurationAssembly {

        @Test
        @DisplayName("copies the shared configuration, writes the descriptor and adds the extra endpoint file")
        void shouldAssembleACompleteConfigurationDirectory() throws IOException {
            Path descriptor = writeReadableByOthers("complete/input/descriptor.yaml", DESCRIPTOR_CONTENT);
            Path extra = writeReadableByOthers("complete/input/" + EXTRA_ENDPOINT, "# extra endpoint\n");
            List<String> committedBefore = listing(COMMITTED_CONFIGURATION);
            List<String> expectedEndpoints = new ArrayList<>(fileNames(COMMITTED_CONFIGURATION.resolve("endpoints")));
            expectedEndpoints.add(EXTRA_ENDPOINT);

            Path assembled = OneOffGatewayContainers.assembleConfigurationDirectory(
                    WORK.resolve("complete/assembled"), descriptor, List.of(extra));

            List<Path> entries;
            try (Stream<Path> walk = Files.walk(assembled)) {
                entries = walk.toList();
            }
            assertFalse(committedBefore.isEmpty(), "the committed configuration must not be empty");
            assertAll("assembled configuration directory",
                    () -> assertEquals(expectedEndpoints.stream().sorted().toList(),
                            fileNames(assembled.resolve("endpoints")),
                            "endpoints/ must hold every committed endpoint file plus the extra one"),
                    () -> assertEquals(DESCRIPTOR_CONTENT, Files.readString(assembled.resolve("gateway.yaml")),
                            "gateway.yaml must carry the descriptor's content"),
                    () -> assertTrue(entries.stream().allMatch(entry -> permissionsOf(entry)
                            .contains(PosixFilePermission.OTHERS_READ)),
                            "every assembled entry must be readable by others"),
                    () -> assertTrue(entries.stream().filter(Files::isDirectory).allMatch(entry -> permissionsOf(entry)
                            .contains(PosixFilePermission.OTHERS_EXECUTE)),
                            "every assembled directory must be traversable by others"),
                    () -> assertEquals(committedBefore, listing(COMMITTED_CONFIGURATION),
                            "assembling must leave the committed sheriff-config listing unchanged"));
        }

        @Test
        @DisplayName("clears a previous assembly at the same destination")
        void shouldClearAPreviousAssembly() throws IOException {
            Path descriptor = writeReadableByOthers("cleared/input/descriptor.yaml", DESCRIPTOR_CONTENT);
            writeReadableByOthers("cleared/assembled/endpoints/stale.yaml", "# left by an earlier run\n");

            Path assembled = OneOffGatewayContainers.assembleConfigurationDirectory(
                    WORK.resolve("cleared/assembled"), descriptor, List.of());

            assertFalse(Files.exists(assembled.resolve("endpoints/stale.yaml")),
                    "a file of an earlier assembly must not survive");
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"target", "target/../one-off-gateway-harness-escape"})
        @DisplayName("refuses a destination that is not strictly below target/")
        void shouldRefuseADestinationOutsideTheBuildDirectory(String destination) throws IOException {
            Path descriptor = writeReadableByOthers("refused/input/descriptor.yaml", DESCRIPTOR_CONTENT);
            Path refused = MODULE.resolve(destination);
            List<Path> noExtraFiles = List.of();

            AssertionError refusal = assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assembleConfigurationDirectory(refused, descriptor, noExtraFiles));

            assertTrue(refusal.getMessage().contains(refused.toAbsolutePath().normalize().toString()),
                    () -> "the refusal must name the destination: " + refusal.getMessage());
        }

        private static Set<PosixFilePermission> permissionsOf(Path entry) {
            return assertDoesNotThrow(() -> Files.getPosixFilePermissions(entry));
        }
    }

    @Nested
    @DisplayName("Checking an extra mount")
    class ExtraMountCheck {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"/app/sheriff-config", "/app/sheriff-config/endpoints/extra.yaml", "/app/certificates",
                "/app/assets/more", "/app/demo", "/app/data/../sheriff-config/endpoints/extra.yaml"})
        @DisplayName("refuses a container path at or below a path the harness mounts, naming it")
        void shouldRefuseAPathInsideAHarnessMount(String containerPath) {
            AssertionError refusal = assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assertOutsideTheHarnessMounts(containerPath));

            assertTrue(refusal.getMessage().contains("'" + containerPath + "'"),
                    () -> "the refusal must name the container path: " + refusal.getMessage());
        }

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {"/app/signing-keys", "/app/assets-extra", "/app/sheriff-config-other/gateway.yaml"})
        @DisplayName("accepts a container path outside every harness mount")
        void shouldAcceptAPathOutsideTheHarnessMounts(String containerPath) {
            assertDoesNotThrow(() -> OneOffGatewayContainers.assertOutsideTheHarnessMounts(containerPath));
        }

        @Test
        @DisplayName("refuses a relative container path")
        void shouldRefuseARelativePath() {
            assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assertOutsideTheHarnessMounts("signing-keys"));
        }
    }

    @Nested
    @DisplayName("Building the docker run arguments")
    class RunArguments {

        @ParameterizedTest(name = "refuses an extra mount at {0}")
        @ValueSource(strings = {"/app/sheriff-config/endpoints/extra.yaml", "/app/certificates"})
        @DisplayName("refuses an extra mount inside a harness mount before any argument is produced")
        void shouldRefuseAnExtraMountInsideAHarnessMount(String containerPath) throws IOException {
            Path descriptor = writeReadableByOthers("refused-mount/descriptor.yaml", DESCRIPTOR_CONTENT);
            BffGateway gateway = gatewayOver(descriptor);
            List<ReadOnlyMount> mounts = List.of(new ReadOnlyMount(WORK, containerPath));
            List<String> noProcessArguments = List.of();

            AssertionError refusal = assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.bffGatewayRunArguments(gateway, mounts, noProcessArguments));

            assertTrue(refusal.getMessage().contains("'" + containerPath + "'"),
                    () -> "the refusal must name the container path: " + refusal.getMessage());
        }

        @Test
        @DisplayName("mounts an overlay descriptor over the shared configuration directory")
        void shouldMountAnOverlayDescriptorOverTheSharedDirectory() throws IOException {
            Path descriptor = writeReadableByOthers("overlay/descriptor.yaml", DESCRIPTOR_CONTENT);

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(
                    gatewayOver(descriptor), List.of(), List.of());

            assertAll("overlay mounts",
                    () -> assertTrue(arguments.contains(
                            COMMITTED_CONFIGURATION.toAbsolutePath() + ":" + CONFIGURATION_MOUNT + ":ro"),
                            () -> "the shared directory must be mounted: " + arguments),
                    () -> assertTrue(arguments.contains(
                            descriptor.toAbsolutePath() + ":" + CONFIGURATION_MOUNT + "/gateway.yaml:ro"),
                            () -> "the descriptor must be mounted as the overlay: " + arguments),
                    () -> assertEquals(OneOffGatewayContainers.IMAGE, arguments.getLast(),
                            "without process arguments the image ends the command"));
        }

        @Test
        @DisplayName("mounts an assembled directory whole, in place of the shared one and its overlay")
        void shouldMountAnAssembledDirectoryWhole() throws IOException {
            Path descriptor = writeReadableByOthers("whole/input/descriptor.yaml", DESCRIPTOR_CONTENT);
            Path assembled = OneOffGatewayContainers.assembleConfigurationDirectory(
                    WORK.resolve("whole/assembled"), descriptor, List.of());

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(
                    gatewayOver(assembled), List.of(), List.of());

            assertAll("assembled directory mount",
                    () -> assertTrue(arguments.contains(assembled + ":" + CONFIGURATION_MOUNT + ":ro"),
                            () -> "the assembled directory must be mounted whole: " + arguments),
                    () -> assertTrue(arguments.stream().noneMatch(
                            argument -> argument.contains(":" + CONFIGURATION_MOUNT + "/")),
                            () -> "nothing may be mounted inside the configuration directory: " + arguments),
                    () -> assertTrue(arguments.stream().noneMatch(
                            argument -> argument.startsWith(COMMITTED_CONFIGURATION.toAbsolutePath() + ":")),
                            () -> "the committed directory must not be a mount source: " + arguments));
        }

        @Test
        @DisplayName("appends extra mounts read-only and the process arguments verbatim after the image")
        void shouldAppendExtraMountsAndProcessArguments() throws IOException {
            Path descriptor = writeReadableByOthers("extras/descriptor.yaml", DESCRIPTOR_CONTENT);
            Path keys = Files.createDirectories(WORK.resolve("extras/signing-keys"));
            List<String> processArguments = List.of("-Dharness.test.first=a b", "--second");

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(gatewayOver(descriptor),
                    List.of(new ReadOnlyMount(keys, "/app/signing-keys")), processArguments);

            int image = arguments.indexOf(OneOffGatewayContainers.IMAGE);
            assertAll("extra mount and process arguments",
                    () -> assertTrue(image > 0, () -> "the image must be named: " + arguments),
                    () -> assertTrue(arguments.subList(0, image).contains(
                            keys.toAbsolutePath() + ":/app/signing-keys:ro"),
                            () -> "the extra mount must precede the image, read-only: " + arguments),
                    () -> assertEquals(processArguments, arguments.subList(image + 1, arguments.size()),
                            "the process arguments must follow the image verbatim"));
        }
    }
}
