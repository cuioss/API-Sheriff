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
import java.util.TreeSet;
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
 * what a one-off gateway mounts and publishes: the refusal of an extra mount reaching into a path the
 * harness mounts itself, the assembly of a configuration directory below {@code target/}, the
 * {@code docker run} argument list both feed, and the publication rule — no container a test starts
 * carries two loopback publications, or one loopback publication on two networks.
 * <p>
 * Every case goes through an argument builder or a helper it calls, never through a start method, so
 * no docker daemon is needed and none is contacted. The committed {@code sheriff-config} directory is
 * read only; its file listing is compared before and after an assembly.
 * <p>
 * <strong>How far the publication rule is held.</strong> The harness refuses both shapes in every
 * argument builder, and {@link PublicationRule} exercises each builder with the options of the
 * containers the suites start. A container started by a raw docker call would bypass the builders, so
 * the same class scans the integration-test sources: a publish option, a raw {@code network connect}
 * and a call of the harness's own network attachment each appear in a named set of files and in no
 * other. A file joining one of those sets fails here and has to be looked at.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("One-off gateway harness — mounts, the assembled configuration directory and the publication rule")
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

    /** The value of every {@code -p} option of a docker argument list, in order. */
    private static List<String> published(List<String> arguments) {
        List<String> published = new ArrayList<>();
        for (int index = 0; index < arguments.size() - 1; index++) {
            if ("-p".equals(arguments.get(index))) {
                published.add(arguments.get(index + 1));
            }
        }
        return published;
    }

    @Nested
    @DisplayName("The publication rule")
    class PublicationRule {

        private static final String CONTAINER = "harness-test-container";
        private static final String FIRST_LOOPBACK = "127.0.0.1::8443";
        private static final String SECOND_LOOPBACK = "127.0.0.1::9000";

        /** The integration-test sources, where a raw docker call would bypass the harness. */
        private static final Path SOURCES =
                MODULE.resolve("src/test/java/de/cuioss/sheriff/gateway/integration");

        @Test
        @DisplayName("refuses two loopback publications, naming the container and both publications")
        void shouldRefuseTwoLoopbackPublications() {
            List<String> options = List.of("--network", "one", "-p", FIRST_LOOPBACK, "-p", SECOND_LOOPBACK);

            AssertionError refusal = assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assertLeavesNoDeadHostPort(CONTAINER, options, 0));

            assertAll("the refusal",
                    () -> assertTrue(refusal.getMessage().contains(CONTAINER), refusal::getMessage),
                    () -> assertTrue(refusal.getMessage().contains(FIRST_LOOPBACK), refusal::getMessage),
                    () -> assertTrue(refusal.getMessage().contains(SECOND_LOOPBACK), refusal::getMessage));
        }

        @Test
        @DisplayName("refuses one loopback publication on a container attached to a further network")
        void shouldRefuseALoopbackPublicationOnAFurtherNetwork() {
            List<String> options = List.of("--network", "one", "-p", FIRST_LOOPBACK);

            AssertionError refusal = assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assertLeavesNoDeadHostPort(CONTAINER, options, 1));

            assertTrue(refusal.getMessage().contains(CONTAINER) && refusal.getMessage().contains(FIRST_LOOPBACK),
                    refusal::getMessage);
        }

        @Test
        @DisplayName("refuses one loopback publication on a container whose options name two networks")
        void shouldRefuseALoopbackPublicationOnTwoNamedNetworks() {
            List<String> options = List.of("--network", "one", "--network", "two", "--publish", FIRST_LOOPBACK);

            assertThrows(AssertionError.class,
                    () -> OneOffGatewayContainers.assertLeavesNoDeadHostPort(CONTAINER, options, 0));
        }

        @ParameterizedTest(name = "recognises {0}")
        @ValueSource(strings = {"127.0.0.1::9000", "127.0.0.1:10499:8443", "localhost::9000", "[::1]::9000"})
        @DisplayName("counts a publication on any loopback host address")
        void shouldRecogniseALoopbackPublication(String publication) {
            List<String> loopback = OneOffGatewayContainers.loopbackPublications(List.of("-p", publication));

            assertEquals(List.of(publication), loopback);
        }

        @Test
        @DisplayName("admits one loopback publication beside publications on every interface, on one network")
        void shouldAdmitOneLoopbackPublicationOnOneNetwork() {
            List<String> options = List.of("--network", "one", "-p", "10499:8443", "-p", "10498:8080",
                    "-p", SECOND_LOOPBACK);

            assertAll("one loopback publication, one network",
                    () -> assertEquals(List.of(SECOND_LOOPBACK), OneOffGatewayContainers.loopbackPublications(options)),
                    () -> assertDoesNotThrow(
                            () -> OneOffGatewayContainers.assertLeavesNoDeadHostPort(CONTAINER, options, 0)));
        }

        @Test
        @DisplayName("admits a container on two networks that publishes nothing on loopback")
        void shouldAdmitTwoNetworksWithoutALoopbackPublication() {
            List<String> options = List.of("--network", "one", "-p", "10499:8443");

            assertDoesNotThrow(() -> OneOffGatewayContainers.assertLeavesNoDeadHostPort(CONTAINER, options, 1));
        }

        @Test
        @DisplayName("a bearer-only gateway publishes its application port on every interface and one port on loopback")
        void bearerOnlyGatewayHasOneLoopbackPublication() throws IOException {
            Path descriptor = writeReadableByOthers("bearer-only/descriptor.yaml", DESCRIPTOR_CONTENT);

            List<String> arguments = OneOffGatewayContainers.gatewayRunArguments("harness-test-bearer-gateway",
                    "harness-test-network", descriptor, 10498);

            assertAll("bearer-only gateway",
                    () -> assertEquals(List.of("10498:8443", SECOND_LOOPBACK), published(arguments)),
                    () -> assertEquals(1, arguments.stream().filter("--network"::equals).count(),
                            "the gateway joins exactly one network"),
                    () -> assertEquals(OneOffGatewayContainers.IMAGE, arguments.getLast()));
        }

        @Test
        @DisplayName("a BFF gateway joins exactly one network")
        void bffGatewayJoinsOneNetwork() throws IOException {
            Path descriptor = writeReadableByOthers("one-network/descriptor.yaml", DESCRIPTOR_CONTENT);

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(
                    gatewayOver(descriptor), List.of(), List.of());

            assertEquals(1, arguments.stream().filter("--network"::equals).count());
        }

        @Test
        @DisplayName("the stub identity provider publishes exactly one port, on loopback, on one network")
        void stubIdentityProviderHasOneLoopbackPublication() {
            List<String> options = StubIdentityProviderRig.stubRunOptions();

            List<String> arguments = OneOffGatewayContainers.auxiliaryContainerArguments(List.of("run", "-d"),
                    CONTAINER, StubIdentityProviderRig.IMAGE, options, List.of(), 0);

            assertAll("stub identity provider",
                    () -> assertEquals(1, published(arguments).size(), () -> "published: " + published(arguments)),
                    () -> assertEquals(published(arguments), OneOffGatewayContainers.loopbackPublications(arguments)),
                    () -> assertEquals(1, arguments.stream().filter("--network"::equals).count()));
        }

        @Test
        @DisplayName("the TLS-relaxation proxy, a container on two networks, publishes no port at all")
        void tlsRelaxationProxyPublishesNothing() {
            List<String> options = BffTlsRelaxationIT.proxyRunOptions("harness-test-certificate", "harness-test-upstream");

            List<String> arguments = OneOffGatewayContainers.auxiliaryContainerArguments(List.of("create"),
                    CONTAINER, "harness-test-image", options, List.of(), 1);

            assertAll("TLS-relaxation proxy",
                    () -> assertEquals(List.of(), published(arguments)),
                    () -> assertTrue(arguments.stream().noneMatch(argument -> argument.startsWith("--publish")),
                            () -> "no publish option in any spelling: " + arguments));
        }

        @Test
        @DisplayName("an auxiliary container is refused before any argument is produced when its options break the rule")
        void auxiliaryContainerIsHeldToTheRule() {
            List<String> verb = List.of("create");
            List<String> options = List.of("--network", "one", "-p", FIRST_LOOPBACK);
            List<String> noCommand = List.of();

            assertThrows(AssertionError.class, () -> OneOffGatewayContainers.auxiliaryContainerArguments(verb,
                    CONTAINER, "harness-test-image", options, noCommand, 1));
        }

        @Test
        @DisplayName("a publish option is spelled in the harness, its test and the stub rig, and nowhere else")
        void publishOptionsAreSpelledWhereTheHarnessSeesThem() throws IOException {
            Set<String> expected = Set.of("OneOffGatewayContainers.java", "OneOffGatewayContainersTest.java",
                    "StubIdentityProviderRig.java");

            Set<String> naming = new TreeSet<>(sourcesNaming("\"-p\""));
            naming.addAll(sourcesNaming("--publish"));

            assertEquals(new TreeSet<>(expected), naming, "a port is published through the harness, whose builders "
                    + "hold every publication to the rule; the stub rig hands its options to such a builder. A "
                    + "further file that spells a publish option starts a container the rule cannot see");
        }

        @Test
        @DisplayName("a raw network connect is spelled in the harness and the late-provider suite, and nowhere else")
        void rawNetworkConnectsAreSpelledWhereTheyAreKnown() throws IOException {
            Set<String> expected = Set.of("OneOffGatewayContainers.java", "JwksLateIdpReadinessIT.java");

            Set<String> naming = new TreeSet<>(sourcesNaming("\"network\", \"connect\""));

            assertEquals(new TreeSet<>(expected), naming, "a container a test starts joins a second network through "
                    + "OneOffGatewayContainers.startAuxiliaryContainerOnNetworks, which refuses a loopback "
                    + "publication on it. The late provider is attached by a raw call and publishes nothing, "
                    + "which the publish-option scan holds");
        }

        @Test
        @DisplayName("the harness's network attachment is called by the two rigs that borrow the compose echo container")
        void networkAttachmentIsCalledByTheRigsThatBorrowAComposeContainer() throws IOException {
            Set<String> expected = Set.of("OneOffGatewayContainers.java", "OneOffGatewayContainersTest.java",
                    "StubIdentityProviderRig.java", "BffTlsRelaxationIT.java");

            Set<String> naming = new TreeSet<>(sourcesNaming("connectToNetwork("));

            assertEquals(new TreeSet<>(expected), naming, "connectToNetwork attaches a container of the compose "
                    + "stack to a rig's network. A further caller has to be checked: attaching a container a "
                    + "test started, which publishes a port on loopback, leaves that port number dead");
        }

        /** The file names of the integration-test sources whose text carries {@code token}. */
        private static List<String> sourcesNaming(String token) throws IOException {
            List<Path> sources;
            try (Stream<Path> files = Files.list(SOURCES)) {
                sources = files.filter(file -> file.getFileName().toString().endsWith(".java")).sorted().toList();
            }
            assertTrue(sources.size() > 10, () -> "expected the integration-test sources below " + SOURCES
                    + ", found " + sources.size() + " files — a scan that reads nothing proves nothing");
            List<String> naming = new ArrayList<>();
            for (Path source : sources) {
                if (Files.readString(source).contains(token)) {
                    naming.add(source.getFileName().toString());
                }
            }
            return naming;
        }
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
        @DisplayName("publishes the application port on every interface and the management port on loopback only")
        void shouldPublishTheApplicationPortOnEveryInterface() throws IOException {
            Path descriptor = writeReadableByOthers("published/descriptor.yaml", DESCRIPTOR_CONTENT);

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(
                    gatewayOver(descriptor), List.of(), List.of());

            assertEquals(List.of("10499:8443", "127.0.0.1::9000"), published(arguments),
                    "the application port carries no host address, so it is not bound to loopback alone; "
                            + "the management port stays on a loopback port docker assigns");
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
