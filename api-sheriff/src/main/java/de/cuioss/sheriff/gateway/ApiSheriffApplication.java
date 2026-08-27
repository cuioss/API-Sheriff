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

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main entry point for the API Sheriff gateway application.
 * <p>
 * This application provides the security-focused API Gateway with REST endpoints,
 * health checks, and metrics in a containerized environment.
 *
 * <h2>Pre-boot container health probe</h2>
 * <p>
 * The distroless production image carries no shell and no {@code curl}, so its {@code HEALTHCHECK}
 * can only invoke the application executable itself. {@link #main(String[])} therefore recognises a
 * single token, {@value #PROBE_FLAG}, and answers it <em>before</em> Quarkus is started: it opens a
 * TCP connection to {@code 127.0.0.1:9000}, closes it again, and terminates the process with
 * {@code 0} when the port accepted and {@code 1} when it did not. The probe is deliberately
 * protocol-blind — the management interface's scheme is deployment-bound (ADR-0025), so a probe
 * that spoke HTTP would have to know which scheme is configured.
 * <p>
 * {@code 9000} is the compiled-in Quarkus management-port default that {@code application.properties}
 * names. It is a constant here rather than a configuration lookup so that the probe path reads no
 * gateway configuration at all. <strong>A deployment that overrides {@code quarkus.management.port}
 * must override the image's {@code HEALTHCHECK} to match</strong>; otherwise the probe measures a
 * port nothing is bound to and the container never reports healthy.
 * <p>
 * The probe never binds a port, never writes a file, never reads gateway configuration and never
 * mutates the running instance — it only connects to an already-listening socket and closes it.
 * <p>
 * <strong>Thread safety:</strong> the probe path holds no state, and the container runtime invokes
 * it in its own short-lived process rather than inside the process serving traffic. It therefore
 * never runs concurrently with, and shares nothing with, the application instance it measures.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusMain
public class ApiSheriffApplication implements QuarkusApplication {

    /**
     * The single token {@link #main(String[])} answers as a container health-probe request.
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

    /**
     * Application entry point, and the branch point for the pre-boot container health probe.
     * <p>
     * When {@code args} carries {@value #PROBE_FLAG} the process terminates with the probe's exit
     * code and Quarkus is never touched; otherwise control falls through to Quarkus unchanged.
     * <p>
     * Quarkus honours an explicit {@code main} on the {@link QuarkusMain}-annotated class, which is
     * what makes this the only surface able to carry the branch: {@link #run(String...)} is reached
     * only once the listeners are bound, so a branch there could never answer while the application
     * is still starting — which is precisely the window a container health check has to cover.
     *
     * @param args the raw command line; scanned for {@value #PROBE_FLAG} and otherwise handed to
     *             Quarkus unchanged
     * @since 1.0
     */
    public static void main(String[] args) {
        if (isProbe(args)) {
            System.exit(probe());
        }
        Quarkus.run(ApiSheriffApplication.class, args);
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

    /**
     * Runs the started application until the container asks it to exit.
     * <p>
     * This method is reached only after Quarkus has bound its listeners, which is why the health
     * probe branches in {@link #main(String[])} instead.
     *
     * @param args the command line, as handed on by Quarkus
     * @return {@code 0} once the application has been asked to shut down
     * @throws Exception if the application fails while waiting for exit
     * @since 1.0
     */
    @Override
    public int run(String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }
}
