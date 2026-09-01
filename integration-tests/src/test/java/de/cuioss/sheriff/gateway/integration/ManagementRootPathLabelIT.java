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

import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.inspectContainerLabel;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@value #ROOT_PATH_LABEL} Compose label is <strong>honest</strong>: the management
 * context path it advertises is the path the running gateway actually serves, and the one
 * {@code prometheus.yml}'s hand-maintained {@code metrics_path} literal names.
 * <p>
 * <strong>Why this IT exists.</strong> The label is the single source of truth every host-side
 * consumer derives its probe path from — {@code start-integration-container.sh}'s readiness gate and
 * banner, and {@code demo-client/scripts/start-dev-environment.sh}. A derivation is only as good as
 * the value it derives FROM, so a label that drifted from the image's actual
 * {@code quarkus.management.root-path} would silently repoint every one of those probes at a path
 * that answers 404 — and, because the readiness gate would then fail loudly at bring-up, the failure
 * would be blamed on the gateway rather than on the label. This IT is what makes the label's honesty
 * a machine-checked fact.
 * <p>
 * <strong>The label is read off the CONTAINER, not the image.</strong> It is a Compose <em>service</em>
 * label declared in {@code docker-compose.yml} and stamped onto the container Compose creates; every
 * gateway service shares one {@code api-sheriff:distroless} image, so an image-scoped read would
 * report it absent. That is precisely the distinction
 * {@link ImageLabelInspector#inspectContainerLabel(String, String)} exists to draw — see
 * {@link ImageMetadataIT} for the image-scoped precedent this follows.
 *
 * <h2>Three legs, because no one of them is sufficient</h2>
 *
 * <ul>
 * <li><strong>Positive</strong> — readiness answers 200 beneath the advertised path. Alone this is
 * satisfiable by a gateway that serves readiness at <em>every</em> path.</li>
 * <li><strong>Negative control</strong> — a sibling path the label does NOT name must not answer 200.
 * This is what makes the positive leg mean something: it proves the gateway discriminates on the path,
 * so the 200 above was earned by the advertised path rather than handed out indiscriminately. Point
 * the control at the label's own value and it fails, which is the property that keeps it a control
 * rather than a formality.</li>
 * <li><strong>Vacuity guard</strong> — an absent or blank label fails outright. Without it a lost
 * label would degrade both legs above into assertions about the empty string, and the suite would go
 * green on a label that no longer exists.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Management root-path label honesty")
class ManagementRootPathLabelIT extends BaseIntegrationTest {

    /** The Compose service label advertising the gateway's management context path. */
    static final String ROOT_PATH_LABEL = "de.cuioss.sheriff.management-root-path";

    /** The Compose service whose container carries the label under test. */
    private static final String GATEWAY_SERVICE = "api-sheriff";

    private static final String COMPOSE_FILE = "docker-compose.yml";
    private static final Path PROMETHEUS_CONFIG = Path.of("prometheus.yml");

    /**
     * A path segment no management interface serves. Fixed rather than generated: the control is only
     * meaningful while the segment is guaranteed NOT to be the label's value, and a generated segment
     * could in principle collide with it.
     */
    private static final String NOT_THE_LABEL_PATH = "/not-the-management-root";

    private static final long COMPOSE_TIMEOUT_SECONDS = 30L;

    @Test
    @DisplayName("the advertised management root path is the one readiness is actually served beneath")
    void advertisedRootPathServesReadiness() {
        String rootPath = assertedRootPathLabel();

        int status = given()
                .relaxedHTTPSValidation()
                .baseUri(managementPortBaseUri())
                .basePath("")
                .when()
                .get(rootPath + "/health/ready")
                .then()
                .extract()
                .statusCode();

        assertEquals(200, status,
                () -> "readiness must answer beneath the advertised management root path '" + rootPath
                        + "', but " + managementPortBaseUri() + rootPath + "/health/ready returned "
                        + status + ". The " + ROOT_PATH_LABEL + " label and the image's actual "
                        + "quarkus.management.root-path have drifted apart, so every host-side probe "
                        + "deriving its path from this label is pointing at a path that does not exist");
    }

    @Test
    @DisplayName("control: a sibling path the label does not name does NOT serve readiness")
    void siblingPathDoesNotServeReadiness() {
        String rootPath = assertedRootPathLabel();
        assertNotEquals(NOT_THE_LABEL_PATH, rootPath,
                "the control path must differ from the label value, or this leg asserts the opposite "
                        + "of what it claims");

        int status = given()
                .relaxedHTTPSValidation()
                .baseUri(managementPortBaseUri())
                .basePath("")
                .when()
                .get(NOT_THE_LABEL_PATH + "/health/ready")
                .then()
                .extract()
                .statusCode();

        assertNotEquals(200, status,
                () -> "a path the " + ROOT_PATH_LABEL + " label does not name must NOT serve readiness; "
                        + managementPortBaseUri() + NOT_THE_LABEL_PATH + "/health/ready returned 200. "
                        + "The management interface is answering readiness at every path, so the "
                        + "positive leg's 200 proves nothing about the advertised path");
    }

    @Test
    @DisplayName("prometheus.yml's hand-maintained metrics_path agrees with the advertised label")
    void prometheusScrapePathAgreesWithLabel() {
        String rootPath = assertedRootPathLabel();
        String metricsPath = prometheusMetricsPath();

        assertEquals(rootPath + "/metrics", metricsPath,
                () -> "prometheus.yml scrapes '" + metricsPath + "' but the " + ROOT_PATH_LABEL
                        + " label advertises '" + rootPath + "'. That file is bind-mounted verbatim and "
                        + "Prometheus interpolates nothing into a scrape config, so its metrics_path is "
                        + "the one management path in integration-tests/ that CANNOT be derived and must "
                        + "be edited by hand — this assertion is what stops the two drifting apart "
                        + "silently. Update prometheus.yml, not this assertion");
    }

    /**
     * The label value, with the vacuity guard applied. Every leg above routes through this, so an
     * absent or blank label fails each of them explicitly rather than degrading it into an assertion
     * about the empty string.
     *
     * @return the non-blank, absolute management root path the container advertises
     */
    private static String assertedRootPathLabel() {
        String rootPath = inspectContainerLabel(gatewayContainerId(), ROOT_PATH_LABEL);

        assertFalse(rootPath.isBlank(),
                () -> "the running " + GATEWAY_SERVICE + " container carries no " + ROOT_PATH_LABEL
                        + " label. Every host-side readiness probe derives its path from it, so its "
                        + "absence is a real defect — restore the label in " + COMPOSE_FILE
                        + " rather than defaulting it here, because a default would let exactly this "
                        + "regression pass unnoticed");
        assertTrue(rootPath.startsWith("/"),
                () -> ROOT_PATH_LABEL + " must advertise an ABSOLUTE path; got '" + rootPath
                        + "'. quarkus.management.root-path is an independent absolute key — a relative "
                        + "value would compose into a different URL than the gateway actually serves");
        return rootPath;
    }

    /**
     * Resolves the running container id for the gateway service through Compose itself
     * ({@code compose ps -q}), rather than guessing the container name. Compose derives that name from
     * the project directory, so a hardcoded {@code integration-tests-api-sheriff-1} would break in any
     * checkout whose directory is named differently.
     *
     * @return the running container id of the {@value #GATEWAY_SERVICE} service
     */
    private static String gatewayContainerId() {
        String containerId = runCapturing(
                new ProcessBuilder("docker", "compose", "-f", COMPOSE_FILE, "ps", "-q", GATEWAY_SERVICE),
                "docker compose ps -q " + GATEWAY_SERVICE);

        assertFalse(containerId.isBlank(),
                () -> "no running container for the " + GATEWAY_SERVICE + " Compose service. This IT "
                        + "reads a label off the RUNNING container, so the stack must be up — it runs "
                        + "in the integration-tests lane that brings the stack up, not standalone");
        // `ps -q` prints one id per line; a scaled service would print several. Take the first.
        return containerId.lines().findFirst().orElseThrow().strip();
    }

    /**
     * The {@code metrics_path} declared by the single scrape config in {@code prometheus.yml}, read as
     * text rather than through a YAML parser: the file has one such key, the value is a plain quoted
     * scalar, and this module carries no YAML dependency for the test scope.
     *
     * @return the declared scrape path, unquoted
     */
    private static String prometheusMetricsPath() {
        List<String> lines;
        try {
            lines = Files.readAllLines(PROMETHEUS_CONFIG);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + PROMETHEUS_CONFIG.toAbsolutePath(), e);
        }

        return lines.stream()
                .map(String::strip)
                .filter(line -> line.startsWith("metrics_path:"))
                .map(line -> line.substring("metrics_path:".length()).strip())
                .map(value -> value.replace("'", "").replace("\"", ""))
                .findFirst()
                .orElseGet(() -> fail("no metrics_path key in " + PROMETHEUS_CONFIG.toAbsolutePath()
                        + " — this IT asserts that literal agrees with the " + ROOT_PATH_LABEL
                        + " label, and it cannot do so if the key has been renamed or removed"));
    }

    /**
     * Runs a short-lived command and returns its merged output, stripped. The process is waited on
     * before its output is read, for the same reason
     * {@link ImageLabelInspector#inspectContainerLabel(String, String)} does so — see that method.
     *
     * @param builder     the configured command
     * @param description how to name this command in a failure message
     * @return the command's output, stripped
     */
    private static String runCapturing(ProcessBuilder builder, String description) {
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(COMPOSE_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                fail(() -> description + " did not complete within " + COMPOSE_TIMEOUT_SECONDS + "s");
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(0, process.exitValue(),
                    () -> description + " failed — is the stack up? Output: " + output.strip());
            return output.strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run " + description, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + description, e);
        }
    }
}
