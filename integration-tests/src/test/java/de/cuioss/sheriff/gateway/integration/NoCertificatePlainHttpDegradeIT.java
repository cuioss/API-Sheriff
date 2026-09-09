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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/**
 * Proves the no-certificate plain-HTTP degrade end to end against the dedicated
 * {@code api-sheriff-no-certificate} compose instance — the one instance in the stack that supplies
 * no server key material at all for the terminated main listener.
 * <p>
 * <strong>What makes this worth an integration test rather than a {@code @QuarkusTest}.</strong> The
 * state under test is not a wrong-port answer; before the degrade existed it was a <em>boot
 * failure</em>. {@code VertxHttpRecorder.initializeMainHttpServer} drops the SSL options when no key
 * material survives the customizer hooks and then throws
 * {@code IllegalStateException("Cannot set quarkus.http.insecure-requests without enabling SSL.")}.
 * The degrade projects {@code quarkus.http.insecure-requests=enabled} at ordinal 275 in exactly that
 * state, so the process starts and serves plain HTTP instead. That whole chain runs inside the
 * shipped distroless native image, through the real {@code ServiceLoader} discovery of the config
 * source factory, and nothing rebuilds anything for this instance — it runs the same
 * {@code api-sheriff:distroless} image as every other gateway service.
 * <p>
 * <strong>Four properties are asserted together, because each alone is satisfiable by an
 * accident.</strong> That the plain port answers; that the HTTPS port does <em>not</em>, so the first
 * assertion is not merely a tolerant client or a redirect; that boot said so out loud with WARN
 * {@code ApiSheriff-123}, since a silent degrade is exactly what the audit exists to prevent; and
 * that the container reached a running state at all, which is the property the pre-degrade behaviour
 * fails on and the other three cannot distinguish from a container that never started.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("No-certificate plain-HTTP degrade")
class NoCertificatePlainHttpDegradeIT {

    /** The plain HTTP port the degrade serves on — compose publishes container 8080 here. */
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

    /** The refusal this seam replaced. Its presence would mean the degrade did not act. */
    private static final String BOOT_REFUSAL =
            "Cannot set quarkus.http.insecure-requests without enabling SSL.";

    private static String plainBaseUri() {
        return "http://localhost:" + System.getProperty("test.no.certificate.plain.port", DEFAULT_PLAIN_PORT);
    }

    private static String httpsUri() {
        return "https://localhost:"
                + System.getProperty("test.no.certificate.https.port", DEFAULT_HTTPS_PORT) + PUBLIC_ASSET;
    }

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
    @DisplayName("the HTTPS port does not answer — the degrade is real, not a redirect")
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
        // even though the sibling ManagementPlainHttpOptOutIT deliberately narrows to SSLException.
        // The two tests are asking opposite questions. There, something IS listening and the claim is
        // that it speaks cleartext, so the broad type would also pass against a dead port and report
        // success for the wrong reason. Here the claim is that NOTHING terminates TLS on this port,
        // so a connection reset from the published-but-unbacked port and a failed handshake are both
        // the expected outcome and neither is more correct than the other. What keeps the assertion
        // from passing vacuously is the matched positive control in mainListenerServesOverPlainHttp:
        // the instance demonstrably answers on its plain port, so "nothing answered here" cannot be
        // a container that never started.
        assertThrows(IOException.class, () -> client.send(request, HttpResponse.BodyHandlers.discarding()),
                "no key material was declared, so no terminated HTTPS listener may exist; if this "
                        + "request succeeds the instance is serving HTTPS and the plain-HTTP answer "
                        + "above was a redirect or a second listener rather than the degrade");
    }

    @Test
    @DisplayName("boot announced the degrade with WARN ApiSheriff-123")
    void bootWarnedAboutTheDegrade() {
        String log = readContainerLog();

        assertTrue(log.contains("ApiSheriff-123"),
                "the degrade must be announced with the WARN identifier so it is greppable");
        assertTrue(log.contains("No-certificate mode: no server key material is DECLARED"),
                "the WARN must name what actually happened, not merely fire");
        assertTrue(log.contains("ApiSheriff-121"),
                "the RESOLVED view must be reported alongside the DECLARED one — ApiSheriff-121 is "
                        + "the authoritative record wherever the two disagree, and folding it into "
                        + "the degrade's own record would silence it exactly when they do");
    }

    @Test
    @DisplayName("the container reached a running state — the pre-degrade behaviour is a boot failure")
    void containerReachedARunningState() {
        String log = readContainerLog();

        assertTrue(log.contains(STARTED_MARKER),
                "without the degrade this instance does not start at all, so the framework's own "
                        + "startup line is the property that separates 'serving plain HTTP' from "
                        + "'never got that far' — the other assertions here cannot tell them apart");
        assertFalse(log.contains(BOOT_REFUSAL),
                () -> "the boot refusal this seam replaced must not appear in the log: " + BOOT_REFUSAL);
    }

    private static String readContainerLog() {
        Path logDir = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs"));
        Path logFile = logDir.resolve(LOG_FILE);
        assertTrue(Files.isRegularFile(logFile),
                () -> "expected the no-certificate instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }
}
