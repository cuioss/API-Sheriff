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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the plain-HTTP contract end to end in the shipped distroless native image: a
 * certificate-less deployment serves cleartext <em>when it declares that it wants to</em>, and
 * refuses to boot when it does not.
 * <p>
 * <strong>Both halves are here on purpose, because either one alone is satisfiable by the wrong
 * implementation.</strong> A gateway that always serves plain HTTP without a certificate passes the
 * opt-in half — and is exactly the silent downgrade this contract forbids. A gateway that always
 * refuses passes the refusal half, and is useless behind a TLS-terminating ingress. Only the pair
 * pins "plain HTTP is declared, never inferred".
 * <p>
 * <strong>Why an integration test rather than a {@code @QuarkusTest}.</strong> The gate runs while
 * SmallRye is assembling the configuration, before CDI exists, and it is discovered through the real
 * {@code ServiceLoader}. Whether that survives native-image generation is precisely the question a
 * JVM-mode boot cannot answer. Nothing rebuilds anything for this test — the opt-in instance runs the
 * same {@code api-sheriff:distroless} image as every other gateway service, and the refusal leg runs
 * that same image once.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("No-certificate plain-HTTP opt-in")
class NoCertificatePlainHttpOptInIT {

    /** The plain HTTP port the opt-in instance serves on — compose publishes container 8080 here. */
    private static final String DEFAULT_PLAIN_PORT = "10453";

    /**
     * The terminated HTTPS port. Compose publishes it deliberately with <em>nothing</em> behind it:
     * a port that is never published cannot be dialled at all, and "I could not dial it" would then
     * be a property of the descriptor rather than of the gateway.
     */
    private static final String DEFAULT_HTTPS_PORT = "10454";

    private static final String LOG_FILE = "quarkus-no-certificate.log";

    /**
     * A directory-served asset on the shared {@code /assets/static} route, which the endpoint
     * descriptor declares {@code require: none}. It is served from a mounted file rather than
     * proxied, so the positive control depends on no upstream container and no token.
     */
    private static final String PUBLIC_ASSET = "/assets/static/app.css";

    /** The framework's own startup line — present only when boot completed. */
    private static final String STARTED_MARKER = "started in";

    /**
     * The framework's own refusal. It is the message this gate exists to pre-empt: it names
     * {@code enabled} to an operator who set {@code redirect}, says nothing about the certificate
     * that is actually missing, and offers no remedy. Neither leg of this test may see it.
     */
    private static final String FRAMEWORK_REFUSAL =
            "Cannot set quarkus.http.insecure-requests without enabling SSL.";

    private static String plainBaseUri() {
        return "http://localhost:" + System.getProperty("test.no.certificate.plain.port", DEFAULT_PLAIN_PORT);
    }

    private static String httpsUri() {
        return "https://localhost:"
                + System.getProperty("test.no.certificate.https.port", DEFAULT_HTTPS_PORT) + PUBLIC_ASSET;
    }

    /**
     * The deliberate deployment: no certificate, and {@code QUARKUS_HTTP_INSECURE_REQUESTS=enabled}
     * saying so. Asserted against the long-lived compose instance.
     */
    @Nested
    @DisplayName("With the opt-in declared, the gateway serves plain HTTP")
    class WithTheOptIn {

        @Test
        @DisplayName("the terminated main listener serves the application over PLAIN HTTP")
        void mainListenerServesOverPlainHttp() {
            given()
                    .baseUri(plainBaseUri())
                    .basePath("")
                    .when()
                    .get(PUBLIC_ASSET)
                    .then()
                    .statusCode(200);
        }

        @Test
        @DisplayName("the HTTPS port does not answer — the plain answer is not a redirect")
        void httpsPortDoesNotAnswer() {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(httpsUri()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            // IOException rather than SSLException specifically, and that is the correct breadth HERE
            // even though the sibling ManagementPlainHttpOptOutIT deliberately narrows to
            // SSLException. The two tests are asking opposite questions. There, something IS
            // listening and the claim is that it speaks cleartext, so the broad type would also pass
            // against a dead port and report success for the wrong reason. Here the claim is that
            // NOTHING terminates TLS on this port, so a connection reset from the
            // published-but-unbacked port and a failed handshake are both the expected outcome and
            // neither is more correct than the other. What keeps the assertion from passing
            // vacuously is the matched positive control in mainListenerServesOverPlainHttp: the
            // instance demonstrably answers on its plain port, so "nothing answered here" cannot be
            // a container that never started.
            assertThrows(IOException.class,
                    () -> client.send(request, HttpResponse.BodyHandlers.discarding()),
                    "no key material was declared, so no terminated HTTPS listener may exist; if "
                            + "this request succeeds the instance is serving HTTPS and the plain-HTTP "
                            + "answer above was a redirect or a second listener");
        }

        @Test
        @DisplayName("boot reported the RESOLVED plain-HTTP listener with WARN ApiSheriff-121")
        void bootReportedTheResolvedPlainHttpListener() {
            String log = readContainerLog();

            assertAll("the cleartext was asked for, and is still said out loud",
                    () -> assertTrue(log.contains("ApiSheriff-121"),
                            "the resolved plain-HTTP state must be announced with the WARN "
                                    + "identifier so it is greppable"),
                    () -> assertTrue(log.contains("Terminated main listener is serving PLAIN HTTP on port"),
                            "the WARN must name what actually happened, not merely fire"),
                    () -> assertFalse(log.contains("ApiSheriff-123"),
                            "ApiSheriff-123 announced an INFERRED downgrade and was retired with the "
                                    + "inference. Its reappearance would mean something is again "
                                    + "projecting an exposure strategy nobody declared"));
        }

        @Test
        @DisplayName("the container reached a running state, and never saw the framework refusal")
        void containerReachedARunningState() {
            String log = readContainerLog();

            assertAll("the opt-in is what makes this boot legal",
                    () -> assertTrue(log.contains(STARTED_MARKER),
                            "the framework's own startup line is the property that separates "
                                    + "'serving plain HTTP' from 'never got that far' — the other "
                                    + "assertions here cannot tell them apart"),
                    () -> assertFalse(log.contains(FRAMEWORK_REFUSAL),
                            () -> "the declared opt-in must satisfy the framework guard outright: "
                                    + FRAMEWORK_REFUSAL));
        }
    }

    /**
     * The matched negative leg: the SAME image and the SAME certificate-less state, with the opt-in
     * withheld.
     * <p>
     * <strong>Why a one-off {@code docker run} rather than a compose service.</strong> A service
     * whose whole purpose is to fail could not live in the stack: the readiness gate selects every
     * {@code api-sheriff*} service and waits for it to report healthy, so a permanently-refusing
     * instance would fail the gate rather than be observed by it. Driving one container directly is
     * also the more honest read — the refusal happens while the configuration is being assembled,
     * long before the file-log handler that {@code readContainerLog()} depends on has been
     * configured, so the message only ever reaches the container's own output.
     */
    @Nested
    @DisplayName("Without the opt-in, the gateway refuses to boot")
    class WithoutTheOptIn {

        /** The message this gate replaces the framework's own with. */
        private static final String OUR_REFUSAL = "No TLS key material is declared";

        @Test
        @DisplayName("it exits non-zero rather than falling back to cleartext")
        void refusesToBoot() {
            RefusalRun run = runWithoutCertificateOrOptIn();

            assertAll("a missing certificate must never resolve itself into a plain-HTTP listener",
                    () -> assertNotEquals(0, run.exitCode(),
                            () -> "the container exited 0, so it started on a configuration that "
                                    + "declares neither key material nor a plain-HTTP opt-in. Output: "
                                    + run.output()),
                    () -> assertFalse(run.output().contains(STARTED_MARKER),
                            () -> "the framework's startup line appeared, so the gateway served "
                                    + "something before giving up — the refusal must land while the "
                                    + "configuration is being assembled, before any listener binds. "
                                    + "Output: " + run.output()));
        }

        @Test
        @DisplayName("the refusal names the missing material and BOTH remedies, not the framework's message")
        void refusalCarriesOurMessage() {
            RefusalRun run = runWithoutCertificateOrOptIn();
            String output = run.output();

            assertAll("the whole point of acting this early is to say something usable",
                    () -> assertTrue(output.contains(OUR_REFUSAL),
                            () -> "the refusal must name what is missing: " + output),
                    () -> assertTrue(output.contains("quarkus.http.ssl.certificate.key-store-file"),
                            () -> "remedy one is 'declare a certificate', which is worthless unless "
                                    + "the supported spellings are named — a keystore deployment "
                                    + "carries no .files key at all: " + output),
                    () -> assertTrue(output.contains("quarkus.http.insecure-requests=enabled"),
                            () -> "remedy two is the explicit plain-HTTP opt-in, spelled as the "
                                    + "operator would type it: " + output),
                    () -> assertFalse(output.contains(FRAMEWORK_REFUSAL),
                            () -> "the framework's own message names 'enabled' to an operator who "
                                    + "set 'redirect' and offers no remedy; reaching it means this "
                                    + "gate did not act first: " + output));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /** Upper bound on the refusal container's whole lifetime; it fails during config assembly. */
    private static final long REFUSAL_TIMEOUT_SECONDS = 90L;

    /** The image every gateway instance in this stack runs, and the one under test here. */
    private static final String IMAGE = "api-sheriff:distroless";

    /**
     * The shipped integration configuration, mounted so the ONLY thing missing from the refusal run
     * is the server key material. Without it the boot could fail for an unrelated reason and the
     * assertions would be measuring that instead.
     */
    private static final Path SHERIFF_CONFIG = Path.of("src", "main", "docker", "sheriff-config");

    /**
     * The outcome of one refusal container.
     *
     * @param exitCode the container's exit status
     * @param output   its merged stdout and stderr
     */
    private record RefusalRun(int exitCode, String output) {
    }

    /**
     * Runs the shipped image once with neither key material nor the plain-HTTP opt-in, and reports
     * how it ended.
     *
     * @return the exit status and merged output of that container
     */
    private static RefusalRun runWithoutCertificateOrOptIn() {
        ProcessBuilder builder = new ProcessBuilder(
                "docker", "run", "--rm",
                "-e", "SHERIFF_CONFIG_DIR=/app/sheriff-config",
                "-e", "OIDC_CLIENT_SECRET=integration-secret",
                "-v", SHERIFF_CONFIG.toAbsolutePath() + ":/app/sheriff-config:ro",
                IMAGE);
        builder.redirectErrorStream(true);
        Path captured = null;
        try {
            captured = Files.createTempFile("api-sheriff-no-optin-", ".out");
            builder.redirectOutput(captured.toFile());
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(REFUSAL_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                return fail("the certificate-less container was still running after "
                        + REFUSAL_TIMEOUT_SECONDS + "s, so it did not refuse at all — it is serving "
                        + "on a configuration that declares neither key material nor an opt-in. "
                        + "Output so far: " + readQuietly(captured));
            }
            return new RefusalRun(process.exitValue(), Files.readString(captured));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run the certificate-less container — is "
                    + IMAGE + " built?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running the certificate-less container", e);
        } finally {
            deleteQuietly(captured);
        }
    }

    private static String readQuietly(Path captured) {
        try {
            return Files.readString(captured);
        } catch (IOException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    /**
     * Removes the capture file without letting its cleanup mask the outcome of the run it served.
     *
     * @param captured the file to remove; ignored when {@code null}
     */
    private static void deleteQuietly(Path captured) {
        if (captured != null && !captured.toFile().delete()) {
            captured.toFile().deleteOnExit();
        }
    }

    private static String readContainerLog() {
        Path logDir = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs"));
        Path logFile = logDir.resolve(LOG_FILE);
        assertTrue(Files.isRegularFile(logFile),
                () -> "expected the opt-in instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }
}
