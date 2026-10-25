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
package de.cuioss.sheriff.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.testsupport.UnreachablePort;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the pre-boot container health probe on {@link HealthProbe} — the branch the
 * distroless image's {@code HEALTHCHECK} invokes, which has to answer before Quarkus is started.
 * <p>
 * Three contracts are covered. {@link HealthProbe#probe(int)} derives liveness purely from whether
 * {@code 127.0.0.1:port} accepts a TCP connection, so it is exercised against a real
 * {@link ServerSocket} on an ephemeral loopback port and against a loopback port that refuses every
 * dial ({@link UnreachablePort}) rather than against a double: a stubbed socket would prove nothing
 * about the accept-versus-refuse distinction the exit code encodes. Neither case needs a fixed port
 * to be free, so neither depends on what else runs on the host.
 * <p>
 * The no-arg {@link HealthProbe#probe()} the entry point calls probes the compiled-in management port.
 * That port is pinned as a literal (ADR-0039), so a change to it has to be made deliberately in both
 * places, and the no-arg path itself is executed and checked against {@code probe(9000)} and its own
 * diagnostic line — without binding {@code 9000}, so the check holds whatever the host runs there.
 * <p>
 * {@link HealthProbe#isProbe(String[])} matches one exact token. The command lines below
 * are spelled as literals because the accepted spelling <em>is</em> the contract — the image's
 * {@code HEALTHCHECK} and this flag have to agree character for character, so generated data could
 * not express what is being asserted.
 */
@EnableGeneratorController
class HealthProbeTest {

    /**
     * The management port the no-arg probe measures. Restated here rather than read from the production
     * constant so that a change to the compiled-in port has to be made deliberately in both places.
     */
    private static final int MANAGEMENT_PORT = 9000;

    /** The loopback address the probe dials; the probe's own literal, so the listener binds the same one. */
    private static final String LOOPBACK = "127.0.0.1";

    @Nested
    @DisplayName("probe() and probe(port) report what the probed port did")
    class ProbeExitCode {

        @Test
        @DisplayName("Should return 0 when the probed port accepts the connection")
        void shouldReturnZeroWhenPortAccepts() throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
                int exitCode = HealthProbe.probe(listener.getLocalPort());

                assertEquals(0, exitCode,
                        "A port that accepted the connection should be reported healthy");
                assertFalse(listener.isClosed(),
                        "The listener should still be open, proving the probe measured a live port");
            }
        }

        @Test
        @DisplayName("Should return 1 when nothing is listening on the probed port")
        void shouldReturnOneWhenPortRefusesConnection() throws Exception {
            int exitCode = HealthProbe.probe(UnreachablePort.pick());

            assertEquals(1, exitCode,
                    "A port with no listener should be reported unhealthy — this is the matched"
                            + " negative control for the accepting-port case");
        }

        @Test
        @DisplayName("Should probe the compiled-in management port 9000 on the no-arg path")
        void shouldProbeTheCompiledInManagementPort() {
            assertEquals(MANAGEMENT_PORT, HealthProbe.PROBE_PORT,
                    "The baked HEALTHCHECK measures the Quarkus management-port default (ADR-0039)");
        }

        /**
         * Executes the no-arg {@link HealthProbe#probe()} — the path the entry point and the baked
         * {@code HEALTHCHECK} take — and checks that it measured the management port rather than
         * some other port or none at all.
         * <p>
         * The test binds nothing, so what {@code 127.0.0.1:9000} does is up to the host. Two
         * host-independent observations carry the assertion: the no-arg result has to agree with
         * {@code probe(9000)}, and its diagnostic line has to name {@code 127.0.0.1:9000} exactly
         * when it reports a refusal. A delegate that probes another port is caught by the
         * diagnostic when both ports refuse and by the disagreement when only one of them accepts;
         * a delegate that skips the dial and reports healthy is caught by the disagreement whenever
         * {@code 9000} refuses. On a host where {@code 9000} accepts, that second delegate is
         * indistinguishable from the real one without binding the port, which this test
         * deliberately does not do.
         */
        @Test
        @DisplayName("Should measure the management port when called without a port")
        void shouldMeasureTheManagementPortOnTheNoArgPath() {
            var diagnostic = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            int noArgExitCode;
            System.setErr(new PrintStream(diagnostic, true, StandardCharsets.UTF_8));
            try {
                noArgExitCode = HealthProbe.probe();
            } finally {
                System.setErr(originalErr);
            }
            String diagnosticText = diagnostic.toString(StandardCharsets.UTF_8);

            int managementPortExitCode = HealthProbe.probe(MANAGEMENT_PORT);

            assertAll("no-arg probe",
                    () -> assertEquals(managementPortExitCode, noArgExitCode,
                            "The no-arg probe should report what 127.0.0.1:" + MANAGEMENT_PORT + " did"),
                    () -> assertEquals(noArgExitCode == 1,
                            diagnosticText.startsWith(
                                    "health-probe: " + LOOPBACK + ":" + MANAGEMENT_PORT + " not accepting: "),
                            () -> "A refusal should be reported against " + LOOPBACK + ":" + MANAGEMENT_PORT
                                    + " and success should write nothing, but exit code " + noArgExitCode
                                    + " came with diagnostic '" + diagnosticText.strip() + "'"));
        }
    }

    @Nested
    @DisplayName("isProbe() matches the exact token only")
    class ProbeFlagMatching {

        /**
         * The accepted spelling is fixed by the image's {@code HEALTHCHECK}, so every command line
         * here is a spec-defined literal rather than generated data. The negative rows are the ones
         * that carry the weight: they pin the match as whole-token equality rather than a prefix or
         * substring test.
         */
        static Stream<Arguments> commandLines() {
            return Stream.of(
                    Arguments.of("the flag alone", new String[]{"--health-probe"}, true),
                    Arguments.of("the flag alongside other arguments",
                            new String[]{"-Dquarkus.http.host=0.0.0.0", "--health-probe", "--verbose"}, true),
                    Arguments.of("an empty command line", new String[]{}, false),
                    Arguments.of("an unrelated system property",
                            new String[]{"-Dquarkus.http.host=0.0.0.0"}, false),
                    Arguments.of("a prefix of the flag", new String[]{"--health"}, false),
                    Arguments.of("the flag without its leading dashes", new String[]{"health-probe"}, false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("commandLines")
        @DisplayName("Should recognise a probe request from whole-token equality alone")
        void shouldMatchExactTokenOnly(String label, String[] args, boolean expected) {
            assertEquals(expected, HealthProbe.isProbe(args),
                    () -> "A command line carrying " + label + " should "
                            + (expected ? "" : "not ") + "be treated as a probe request");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 1, maxSize = 20, count = 5)
        @DisplayName("Should never treat an arbitrary letter-only argument as a probe request")
        void shouldRejectArbitraryArgument(String argument) {
            assertFalse(HealthProbe.isProbe(new String[]{argument}),
                    () -> "Only the exact flag should match, but '" + argument + "' did");
        }
    }
}
