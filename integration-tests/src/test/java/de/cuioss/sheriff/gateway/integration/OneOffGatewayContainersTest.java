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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p>
 * <strong>The documented host ports.</strong> {@link DocumentedHostPorts} reads the host-port table of
 * {@code doc/development/integration-test-topology.adoc} out of the note at runtime and compares it
 * with the ports the same sources assign. It holds no copy of either side: the ports come from the
 * sources' own declarations and the expectation from the note, so the table cannot fall behind a
 * suite that adds or moves a port. It compares what is declared; whether a port is free on a host is
 * not something a source scan can say.
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

    /**
     * The integration-test sources: where a raw docker call would bypass the harness, and where a
     * suite assigns its gateway a host port.
     */
    private static final Path SOURCES = MODULE.resolve("src/test/java/de/cuioss/sheriff/gateway/integration");

    private static final String JAVA_SUFFIX = ".java";

    /** The Java sources directly below {@link #SOURCES}; fails when there are too few to be the real set. */
    private static List<Path> sources() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(SOURCES)) {
            sources = files.filter(file -> file.getFileName().toString().endsWith(JAVA_SUFFIX)).sorted().toList();
        }
        assertTrue(sources.size() > 10, () -> "expected the integration-test sources below " + SOURCES
                + ", found " + sources.size() + " files — a scan that reads nothing proves nothing");
        return sources;
    }

    /** The file names of the integration-test sources whose text carries {@code token}. */
    private static List<String> sourcesNaming(String token) throws IOException {
        List<String> naming = new ArrayList<>();
        for (Path source : sources()) {
            if (Files.readString(source).contains(token)) {
                naming.add(source.getFileName().toString());
            }
        }
        return naming;
    }

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
        void bearerOnlyGatewayHasOneLoopbackPublication() throws Exception {
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
        void bffGatewayJoinsOneNetwork() throws Exception {
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
        void publishOptionsAreSpelledWhereTheHarnessSeesThem() throws Exception {
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
        void rawNetworkConnectsAreSpelledWhereTheyAreKnown() throws Exception {
            Set<String> expected = Set.of("OneOffGatewayContainers.java", "JwksLateIdpReadinessIT.java");

            Set<String> naming = new TreeSet<>(sourcesNaming("\"network\", \"connect\""));

            assertEquals(new TreeSet<>(expected), naming, "a container a test starts joins a second network through "
                    + "OneOffGatewayContainers.startAuxiliaryContainerOnNetworks, which refuses a loopback "
                    + "publication on it. The late provider is attached by a raw call and publishes nothing, "
                    + "which the publish-option scan holds");
        }

        @Test
        @DisplayName("the harness's network attachment is called by the two rigs that borrow the compose echo container")
        void networkAttachmentIsCalledByTheRigsThatBorrowAComposeContainer() throws Exception {
            Set<String> expected = Set.of("OneOffGatewayContainers.java", "OneOffGatewayContainersTest.java",
                    "StubIdentityProviderRig.java", "BffTlsRelaxationIT.java");

            Set<String> naming = new TreeSet<>(sourcesNaming("connectToNetwork("));

            assertEquals(new TreeSet<>(expected), naming, "connectToNetwork attaches a container of the compose "
                    + "stack to a rig's network. A further caller has to be checked: attaching a container a "
                    + "test started, which publishes a port on loopback, leaves that port number dead");
        }

    }

    @Nested
    @DisplayName("The documented host ports")
    class DocumentedHostPorts {

        /** The note whose host-port table this class reads, relative to the repository root. */
        private static final String NOTE = "doc/development/integration-test-topology.adoc";

        /** The note's tagged block that holds the table. */
        private static final String TAG = "test-started-host-ports";

        private static final String TABLE_DELIMITER = "|===";
        private static final String CELL_SEPARATOR = "|";
        private static final List<String> HEADER = List.of("Host port", "Gateway container", "Suite");

        /** Backtick-delimited span — the table writes every port and every class name as monospace. */
        private static final Pattern MONOSPACE_SPAN = Pattern.compile("`([^`]+)`");
        private static final Pattern PORT_NUMBER = Pattern.compile("\\d+");
        private static final Pattern CLASS_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");

        /**
         * The declaration by which a source assigns a test-started gateway its fixed host port: an
         * {@code int} constant whose name is, or ends in, {@code APPLICATION_PORT}, initialised with a
         * literal. It is matched against a whole source line from its start, so a port number a
         * comment or a Javadoc names is not an assignment, and neither is a literal a call passes.
         */
        private static final Pattern ASSIGNMENT = Pattern.compile(
                "^\\s*(?:private\\s+)?static\\s+final\\s+int\\s+(?:[A-Z][A-Z0-9]*_)*APPLICATION_PORT\\s*=\\s*(\\d+)\\s*;");

        /** How a source starts a gateway through the harness, or describes one for it to start. */
        private static final List<String> GATEWAY_STARTS =
                List.of("startGateway(", "startBffGateway(", "new BffGateway(");

        /** The harness and this test: they define and exercise the start methods and start no gateway. */
        private static final Set<String> NOT_A_SUITE =
                Set.of("OneOffGatewayContainers.java", "OneOffGatewayContainersTest.java");

        @Test
        @DisplayName("the table lists exactly the host ports the sources assign to a test-started gateway")
        void tableListsExactlyTheAssignedPorts() throws Exception {
            Set<Integer> assigned = new TreeSet<>();
            for (Assignment assignment : assignments()) {
                assigned.add(assignment.port());
            }

            Set<Integer> documented = new TreeSet<>(documentedPorts());

            assertEquals(assigned, documented, () -> "the host-port table in the '" + TAG + "' block of "
                    + NOTE + " (actual) no longer lists the ports the integration-test sources assign "
                    + "(expected). A contributor reads that table to pick a free port: add, move or remove "
                    + "the row in the change that adds, moves or removes the port");
        }

        @Test
        @DisplayName("each assigned port stands in a row that names the suite, or the rig, that assigns it")
        void eachPortIsAssignedWhereItsRowSays() throws Exception {
            List<Row> rows = rows();

            List<String> misattributed = new ArrayList<>();
            for (Assignment assignment : assignments()) {
                boolean named = rows.stream().anyMatch(row -> row.ports().contains(assignment.port())
                        && row.classes().contains(assignment.className()));
                if (!named) {
                    misattributed.add(assignment.port() + " is assigned in " + assignment.className());
                }
            }

            assertEquals(List.of(), misattributed, () -> "every port must stand in a row of the '" + TAG
                    + "' block of " + NOTE + " whose suite cell names the class that assigns it");
        }

        @Test
        @DisplayName("no host port stands in the table twice")
        void tablePortsAreDistinct() throws Exception {
            List<Integer> ports = documentedPorts();

            List<Integer> repeated = ports.stream()
                    .filter(port -> ports.indexOf(port) != ports.lastIndexOf(port)).distinct().toList();

            assertEquals(List.of(), repeated, () -> "a host port is listed more than once in the '" + TAG
                    + "' block of " + NOTE + "; two gateways cannot publish the same port");
        }

        @Test
        @DisplayName("every source that starts a gateway through the harness declares its port in the recognised form")
        void everyGatewayStartingSourceDeclaresItsPort() throws Exception {
            Set<String> declaring = new TreeSet<>();
            for (Assignment assignment : assignments()) {
                declaring.add(assignment.className() + ".java");
            }

            Set<String> starting = new TreeSet<>();
            for (String start : GATEWAY_STARTS) {
                starting.addAll(sourcesNaming(start));
            }
            starting.removeAll(NOT_A_SUITE);

            assertFalse(starting.isEmpty(), () -> "no integration-test source carries any of " + GATEWAY_STARTS
                    + " — the start methods were renamed, and this check would hold for no file at all");
            assertEquals(starting, declaring, () -> "a source that starts a gateway through the harness assigns "
                    + "it a fixed host port, and the table check sees that port only as a constant whose name "
                    + "is or ends in APPLICATION_PORT. A source in one set and not the other either passes "
                    + "its port in a form the scan does not recognise, or declares a port it never starts a "
                    + "gateway on");
        }

        /** The ports of every row, in table order, repetitions kept. */
        private static List<Integer> documentedPorts() throws IOException {
            List<Integer> ports = new ArrayList<>();
            for (Row row : rows()) {
                ports.addAll(row.ports());
            }
            return ports;
        }

        /**
         * The rows of the note's host-port table: the ports of the first cell and the class names of
         * the third. Fails when the note or its tagged block is missing, when the block holds no
         * table of the expected three columns, and when a row gives no port or names no class.
         */
        private static List<Row> rows() throws IOException {
            Path note = note();
            List<String> block = taggedBlock(note);
            int from = block.indexOf(TABLE_DELIMITER);
            int to = block.lastIndexOf(TABLE_DELIMITER);
            assertTrue(from >= 0 && to > from, () -> "the '" + TAG + "' block of " + note
                    + " holds no table delimited by two '" + TABLE_DELIMITER + "' lines");

            List<String> cells = new ArrayList<>();
            for (String line : block.subList(from + 1, to)) {
                if (line.startsWith(CELL_SEPARATOR)) {
                    for (String cell : line.substring(1).split(Pattern.quote(CELL_SEPARATOR))) {
                        cells.add(cell.strip());
                    }
                } else if (!line.isBlank()) {
                    assertFalse(cells.isEmpty(), () -> "the table in " + note + " starts with a line that "
                            + "opens no cell: " + line);
                    cells.set(cells.size() - 1, cells.getLast() + " " + line.strip());
                }
            }
            int columns = HEADER.size();
            assertTrue(cells.size() > columns && cells.size() % columns == 0, () -> "the table in the '" + TAG
                    + "' block of " + note + " must hold a header and at least one row of " + columns
                    + " cells each; found " + cells.size() + " cells");
            assertEquals(HEADER, cells.subList(0, columns), () -> "the table in the '" + TAG + "' block of "
                    + note + " no longer has the columns this check reads the ports and the suites from");

            List<Row> rows = new ArrayList<>();
            for (int index = columns; index < cells.size(); index += columns) {
                String portCell = cells.get(index);
                String suiteCell = cells.get(index + columns - 1);
                List<Integer> ports = spans(portCell, PORT_NUMBER).stream().map(Integer::valueOf).toList();
                Set<String> classes = new TreeSet<>(spans(suiteCell, CLASS_NAME));
                assertFalse(ports.isEmpty(), () -> "a row of the table in " + note
                        + " gives no host port in its first cell: " + portCell);
                assertFalse(classes.isEmpty(), () -> "a row of the table in " + note
                        + " names no class in its suite cell: " + suiteCell);
                rows.add(new Row(ports, classes));
            }
            return rows;
        }

        /** The monospace spans of a cell that match {@code shape} as a whole. */
        private static List<String> spans(String cell, Pattern shape) {
            List<String> spans = new ArrayList<>();
            Matcher matcher = MONOSPACE_SPAN.matcher(cell);
            while (matcher.find()) {
                String span = matcher.group(1).strip();
                if (shape.matcher(span).matches()) {
                    spans.add(span);
                }
            }
            return spans;
        }

        /**
         * The note, resolved from the module working directory: it lives at the repository root, the
         * parent of the module directory.
         */
        private static Path note() {
            Path repositoryRoot = MODULE.getParent();
            assertNotNull(repositoryRoot, () -> "the module directory " + MODULE + " has no parent, so "
                    + NOTE + " cannot be resolved");
            Path note = repositoryRoot.resolve(NOTE);
            assertTrue(Files.isRegularFile(note), () -> "the note whose host-port table this check reads "
                    + "was not found at " + note + "; point the check at its new location rather than "
                    + "leaving it to read nothing");
            return note;
        }

        /** The lines strictly between the {@code tag::} and {@code end::} markers of {@link #TAG}. */
        private static List<String> taggedBlock(Path note) throws IOException {
            List<String> lines = Files.readAllLines(note);
            String open = "// tag::" + TAG + "[]";
            String close = "// end::" + TAG + "[]";
            int from = lines.indexOf(open);
            int to = lines.indexOf(close);
            assertTrue(from >= 0, () -> "the marker '" + open + "' is absent from " + note
                    + "; it must stand alone on its own line, unindented");
            assertTrue(to > from, () -> "the marker '" + close + "' is absent from " + note
                    + " or precedes its opening marker");
            List<String> block = lines.subList(from + 1, to);
            assertTrue(block.stream().anyMatch(line -> !line.isBlank()),
                    () -> "the '" + TAG + "' block of " + note + " is empty");
            return block;
        }

        /**
         * Every host port an integration-test source assigns to a test-started gateway, with the class
         * that assigns it. Fails when the scan recognises no assignment at all.
         */
        private static List<Assignment> assignments() throws IOException {
            List<Path> sources = sources();
            List<Assignment> assignments = new ArrayList<>();
            for (Path source : sources) {
                String fileName = source.getFileName().toString();
                String className = fileName.substring(0, fileName.length() - JAVA_SUFFIX.length());
                for (String line : Files.readAllLines(source)) {
                    Matcher matcher = ASSIGNMENT.matcher(line);
                    if (matcher.find()) {
                        assignments.add(new Assignment(className, Integer.parseInt(matcher.group(1))));
                    }
                }
            }
            assertFalse(assignments.isEmpty(), () -> "none of the " + sources.size() + " sources below " + SOURCES
                    + " declares an int constant named APPLICATION_PORT or ending in _APPLICATION_PORT — the "
                    + "declaration this check recognises a port assignment by is gone, and it would compare "
                    + "the table with nothing");
            return assignments;
        }

        /** A row of the host-port table: the ports of its first cell, the class names of its last. */
        private record Row(List<Integer> ports, Set<String> classes) {
        }

        /** A fixed host port and the class whose source assigns it. */
        private record Assignment(String className, int port) {
        }
    }

    @Nested
    @DisplayName("Assembling a configuration directory")
    class ConfigurationAssembly {

        @Test
        @DisplayName("copies the shared configuration, writes the descriptor and adds the extra endpoint file")
        void shouldAssembleACompleteConfigurationDirectory() throws Exception {
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
        void shouldClearAPreviousAssembly() throws Exception {
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
        void shouldRefuseADestinationOutsideTheBuildDirectory(String destination) throws Exception {
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
        void shouldRefuseAnExtraMountInsideAHarnessMount(String containerPath) throws Exception {
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
        void shouldPublishTheApplicationPortOnEveryInterface() throws Exception {
            Path descriptor = writeReadableByOthers("published/descriptor.yaml", DESCRIPTOR_CONTENT);

            List<String> arguments = OneOffGatewayContainers.bffGatewayRunArguments(
                    gatewayOver(descriptor), List.of(), List.of());

            assertEquals(List.of("10499:8443", "127.0.0.1::9000"), published(arguments),
                    "the application port carries no host address, so it is not bound to loopback alone; "
                            + "the management port stays on a loopback port docker assigns");
        }

        @Test
        @DisplayName("mounts an overlay descriptor over the shared configuration directory")
        void shouldMountAnOverlayDescriptorOverTheSharedDirectory() throws Exception {
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
        void shouldMountAnAssembledDirectoryWhole() throws Exception {
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
        void shouldAppendExtraMountsAndProcessArguments() throws Exception {
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
