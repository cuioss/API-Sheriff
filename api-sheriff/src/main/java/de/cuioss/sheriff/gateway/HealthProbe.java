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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * The pre-boot container health probe the distroless image's {@code HEALTHCHECK} invokes.
 * <p>
 * The distroless production image carries no shell and no {@code curl}, so its {@code HEALTHCHECK}
 * can only invoke the application executable itself. {@link ApiSheriffApplication#main(String[])}
 * therefore recognises a single token, {@value #PROBE_FLAG}, and answers it <em>before</em> Quarkus
 * is started: {@link #probe()} opens a TCP connection to {@code 127.0.0.1:9000}, closes it again,
 * and yields {@code 0} when the port accepted and {@code 1} when it did not. The probe is
 * deliberately protocol-blind — the management interface's scheme is deployment-bound (ADR-0025),
 * so a probe that spoke HTTP would have to know which scheme is configured.
 * <p>
 * {@code 9000} is the compiled-in Quarkus management-port default that {@code application.properties}
 * names. It is a constant here rather than a configuration lookup so that the probe path reads no
 * gateway configuration at all. <strong>A deployment that overrides {@code quarkus.management.port}
 * makes the baked check fail closed — never healthy — and the only remedy is an image rebuilt
 * against the new port</strong>. Overriding the image's {@code HEALTHCHECK} does not reach it: the
 * override still names the one executable the image carries, and that binary probes the constant it
 * was built with (ADR-0039).
 * <p>
 * The probe never binds a port, never writes a file, never reads gateway configuration and never
 * mutates the running instance — it only connects to an already-listening socket and closes it.
 * <p>
 * <strong>Why this is its own class rather than a branch on the entry point.</strong> An entry-point
 * class is inherently untestable — {@code main} either terminates the JVM or hands control to
 * Quarkus — which is why {@code ApiSheriffApplication} is excluded from coverage measurement. The
 * probe is the opposite: it is pure, fast and exhaustively testable. Keeping the two apart is what
 * lets the untestable shim stay excluded while this logic is genuinely measured.
 * <p>
 * <strong>Thread safety:</strong> this class holds no state, and the container runtime invokes the
 * probe in its own short-lived process rather than inside the process serving traffic. It therefore
 * never runs concurrently with, and shares nothing with, the application instance it measures.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class HealthProbe {

    /**
     * The single token {@link ApiSheriffApplication#main(String[])} answers as a container
     * health-probe request.
     */
    static final String PROBE_FLAG = "--health-probe";

    /**
     * Compiled-in Quarkus management-port default — the port the baked {@code HEALTHCHECK} measures.
     */
    private static final int PROBE_PORT = 9000;

    /**
     * Connect timeout for the probe, in milliseconds. Kept well below the image's
     * {@code HEALTHCHECK --timeout} so that a dead port surfaces as a reported refusal rather than
     * as the container runtime killing the probe process.
     */
    private static final int PROBE_CONNECT_TIMEOUT_MILLIS = 2000;

    private HealthProbe() {
        // utility
    }

    /**
     * Reports whether the command line requests a health probe.
     * <p>
     * The match is exact against a whole token: a prefix, a substring, or a differently-spelled flag
     * does not match, so an ordinary application argument can never be mistaken for a probe request.
     *
     * @param args the raw command line
     * @return {@code true} when {@code args} contains the token {@value #PROBE_FLAG}
     * @since 1.0
     */
    static boolean isProbe(String[] args) {
        return Arrays.stream(args).anyMatch(PROBE_FLAG::equals);
    }

    /**
     * Probes the management listener by opening a TCP connection and immediately closing it.
     * <p>
     * A successful accept is the whole signal: nothing is written to or read from the socket, so the
     * result is independent of whether the management interface speaks HTTP or HTTPS.
     * <p>
     * On failure exactly one line is written to {@code System.err}. The logging manager is not
     * installed on this path, so {@code CuiLogger} is unavailable; the container runtime captures
     * health-check output into the container's health log, which is the only diagnostic sink a
     * pre-boot branch has.
     *
     * @return {@code 0} when the port accepted the connection, {@code 1} on refusal, timeout, or any
     *         other I/O failure
     * @since 1.0
     */
    static int probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PROBE_PORT), PROBE_CONNECT_TIMEOUT_MILLIS);
            return 0;
        } catch (IOException e) {
            // cui-rewrite:disable CuiLoggerStandardsRecipe
            System.err.println("health-probe: 127.0.0.1:" + PROBE_PORT + " not accepting: " + e); // NOSONAR java:S106 pre-boot probe: no logging manager yet; the healthcheck log is the only sink
            return 1;
        }
    }
}
