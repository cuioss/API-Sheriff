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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.stream.Stream;


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
 * Two contracts are covered. {@link HealthProbe#probe()} derives liveness purely from
 * whether {@code 127.0.0.1:9000} accepts a TCP connection, so it is exercised against a real
 * {@link ServerSocket} and against a genuinely closed port rather than against a double: a stubbed
 * socket would prove nothing about the accept-versus-refuse distinction the exit code encodes. Both
 * port-dependent tests fail loudly when the port is not in the state they need, because a probe test
 * that quietly passes without a real listener asserts nothing at all.
 * <p>
 * {@link HealthProbe#isProbe(String[])} matches one exact token. The command lines below
 * are spelled as literals because the accepted spelling <em>is</em> the contract — the image's
 * {@code HEALTHCHECK} and this flag have to agree character for character, so generated data could
 * not express what is being asserted.
 */
@EnableGeneratorController
class HealthProbeTest {

    /**
     * The management port the probe measures. Restated here rather than read from the production
     * constant so that a change to the compiled-in port has to be made deliberately in both places.
     */
    private static final int PROBE_PORT = 9000;

    private static final String LOOPBACK = "127.0.0.1";

    @Nested
    @DisplayName("probe() reports what the management port did")
    class ProbeExitCode {

        @Test
        @DisplayName("Should return 0 when the management port accepts the connection")
        void shouldReturnZeroWhenPortAccepts() throws Exception {
            try (ServerSocket listener = openListenerOrFail()) {
                int exitCode = HealthProbe.probe();

                assertEquals(0, exitCode,
                        "A port that accepted the connection should be reported healthy");
                assertFalse(listener.isClosed(),
                        "The listener should still be open, proving the probe measured a live port");
            }
        }

        @Test
        @DisplayName("Should return 1 when nothing is listening on the management port")
        void shouldReturnOneWhenPortRefusesConnection() {
            requirePortFree();

            int exitCode = HealthProbe.probe();

            assertEquals(1, exitCode,
                    "A port with no listener should be reported unhealthy — this is the matched"
                            + " negative control for the accepting-port case");
        }

        /**
         * Binds the real management port, failing the test rather than skipping when it is taken:
         * passing without a listener would assert nothing about the accepting-port branch.
         */
        private ServerSocket openListenerOrFail() {
            try {
                return new ServerSocket(PROBE_PORT, 1, InetAddress.getByName(LOOPBACK));
            } catch (IOException e) {
                return fail(LOOPBACK + ":" + PROBE_PORT + " is already in use, so no listener could be"
                        + " opened for the accepting-port case. Free the port and re-run.", e);
            }
        }

        /**
         * Establishes that the port is genuinely closed before the refusal case runs, so a foreign
         * listener is reported as the setup failure it is instead of as a probe defect.
         */
        private void requirePortFree() {
            try (ServerSocket unused = new ServerSocket(PROBE_PORT, 1, InetAddress.getByName(LOOPBACK))) {
                assertFalse(unused.isClosed(), "The probe port should have been bindable");
            } catch (IOException e) {
                fail(LOOPBACK + ":" + PROBE_PORT + " is already in use by another process, so the"
                        + " refused-connection control cannot be established. Free the port and re-run.", e);
            }
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
