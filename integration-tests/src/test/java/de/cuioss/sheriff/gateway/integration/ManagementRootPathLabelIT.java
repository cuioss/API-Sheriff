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
import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.runCapturing;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@value #ROOT_PATH_LABEL} Compose label is <strong>honest</strong>: the management
 * context path it advertises is the path the running gateway actually serves, and the path every
 * hand-maintained literal under {@code integration-tests/} names.
 * <p>
 * There are three such literals, and this IT now guards all of them: {@code prometheus.yml}'s
 * {@code metrics_path}, {@code verify-invalid-config-fails.sh}'s {@code MANAGEMENT_ROOT_PATH}
 * assignment, and {@link BaseIntegrationTest}'s management-root-path default. None of the three can
 * be DERIVED — {@code prometheus.yml} is bind-mounted verbatim and Prometheus interpolates nothing
 * into a scrape config, and the script deliberately drives a bare {@code docker run} against the
 * image rather than bringing the stack up, so it has no resolved Compose model to read the label
 * from — but each of them can be ASSERTED, which is what the corresponding leg below does.
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
    private static final Path INVALID_CONFIG_SCRIPT = Path.of("scripts", "verify-invalid-config-fails.sh");

    /** The shell variable {@link #INVALID_CONFIG_SCRIPT} mirrors the label into. */
    private static final String SCRIPT_ROOT_PATH_VAR = "MANAGEMENT_ROOT_PATH";

    /**
     * A path segment no management interface serves. Fixed rather than generated: the control is only
     * meaningful while the segment is guaranteed NOT to be the label's value, and a generated segment
     * could in principle collide with it.
     */
    private static final String NOT_THE_LABEL_PATH = "/not-the-management-root";

    @Test
    @DisplayName("the advertised management root path is the one readiness is actually served beneath")
    void advertisedRootPathServesReadiness() {
        String rootPath = normalisedRootPathLabel();

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
        String rootPath = normalisedRootPathLabel();
        String metricsPath = prometheusMetricsPath();

        assertEquals(rootPath + "/metrics", metricsPath,
                () -> "prometheus.yml scrapes '" + metricsPath + "' but the " + ROOT_PATH_LABEL
                        + " label advertises '" + rootPath + "'. That file is bind-mounted verbatim and "
                        + "Prometheus interpolates nothing into a scrape config, so its metrics_path "
                        + "CANNOT be derived and must be edited by hand — this assertion is what stops "
                        + "the two drifting apart silently. Update prometheus.yml, not this assertion. "
                        + "It is one of three hand-maintained management paths under integration-tests/; "
                        + "verify-invalid-config-fails.sh and BaseIntegrationTest carry the others and "
                        + "are asserted by the two legs below");
    }

    @Test
    @DisplayName("verify-invalid-config-fails.sh's hand-maintained root path agrees with the advertised label")
    void invalidConfigScriptRootPathAgreesWithLabel() {
        String rootPath = normalisedRootPathLabel();
        String scriptRootPath = normalisePath(invalidConfigScriptRootPath());

        assertEquals(rootPath, scriptRootPath,
                () -> INVALID_CONFIG_SCRIPT + " sets " + SCRIPT_ROOT_PATH_VAR + "='" + scriptRootPath
                        + "' but the " + ROOT_PATH_LABEL + " label advertises '" + rootPath
                        + "'. That script drives a bare `docker run` against the image instead of "
                        + "bringing the stack up, so it has no resolved Compose model to read the label "
                        + "from and CANNOT derive this value — it mirrors the label by hand. Left "
                        + "unasserted a divergence surfaces only as a confusing case-7 failure that "
                        + "reads as a gateway fault. Update the script, not this assertion");
    }

    @Test
    @DisplayName("BaseIntegrationTest's management-root-path default agrees with the advertised label")
    void baseIntegrationTestDefaultAgreesWithLabel() {
        String rootPath = normalisedRootPathLabel();
        String configuredRootPath = managementRootPath();

        assertEquals(rootPath, configuredRootPath,
                () -> "BaseIntegrationTest resolves the management context path to '" + configuredRootPath
                        + "' but the " + ROOT_PATH_LABEL + " label advertises '" + rootPath
                        + "'. This suite probes every management endpoint beneath that value, so a run "
                        + "against a relocated image without -Dtest.management.root-path set probes the "
                        + "wrong path and fails in a way that reads as a gateway fault rather than as a "
                        + "stale default. Update BaseIntegrationTest's DEFAULT_MANAGEMENT_ROOT_PATH (or "
                        + "pass -Dtest.management.root-path), not this assertion. Both sides are compared "
                        + "after the same trailing-slash normalisation, so a '/' label and the empty "
                        + "effective path are not reported as a divergence — they denote the same path");
    }

    /**
     * The label value, with the vacuity guard applied. Every leg above routes through this, so an
     * absent or blank label fails each of them explicitly rather than degrading it into an assertion
     * about the empty string.
     *
     * @return the non-blank, absolute management root path the container advertises
     */
    /**
     * The advertised label, normalised for concatenation and comparison.
     * <p>
     * Every leg that composes a URL from the label, or compares it against another spelling of the
     * same path, MUST route through this rather than through {@link #assertedRootPathLabel()}. The
     * raw accessor is validated but not normalised: it returns {@code "/"} verbatim for a
     * root-mounted management interface, and {@code "/" + "/health/ready"} is {@code //health/ready}
     * — a path the gateway does not serve — while {@code "/" + "/metrics"} compares unequal to
     * {@code prometheus.yml}'s {@code /metrics}. Both legs would fail against a correctly-configured
     * gateway, reporting a drift that does not exist.
     * <p>
     * This is the same trailing-slash rule {@link BaseIntegrationTest#normalisePath(String)} applies
     * to the configured side, applied to the advertised side, so both halves of every comparison are
     * normalised identically.
     *
     * @return the advertised management root path, normalised for concatenation
     */
    private static String normalisedRootPathLabel() {
        return normalisePath(assertedRootPathLabel());
    }

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
                "docker compose ps -q " + GATEWAY_SERVICE, "is the stack up?");

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
        return readLines(PROMETHEUS_CONFIG).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("metrics_path:"))
                .map(line -> line.substring("metrics_path:".length()).strip())
                .map(ManagementRootPathLabelIT::unquote)
                .findFirst()
                .orElseGet(() -> fail("no metrics_path key in " + PROMETHEUS_CONFIG.toAbsolutePath()
                        + " — this IT asserts that literal agrees with the " + ROOT_PATH_LABEL
                        + " label, and it cannot do so if the key has been renamed or removed"));
    }

    /**
     * The management root path {@link #INVALID_CONFIG_SCRIPT} mirrors the label into, read as text: it
     * is a plain shell assignment in a script this module never sources, so parsing the one line is
     * both sufficient and cheaper than invoking a shell to evaluate it.
     * <p>
     * A missing assignment FAILS rather than returning a default. Returning one would turn a rename of
     * the variable into a vacuous pass, which is the exact drift this leg exists to catch.
     *
     * @return the assigned root path, unquoted
     */
    private static String invalidConfigScriptRootPath() {
        String prefix = SCRIPT_ROOT_PATH_VAR + "=";
        return readLines(INVALID_CONFIG_SCRIPT).stream()
                .map(String::strip)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).strip())
                .map(ManagementRootPathLabelIT::unquote)
                .findFirst()
                .orElseGet(() -> fail("no " + SCRIPT_ROOT_PATH_VAR + " assignment in "
                        + INVALID_CONFIG_SCRIPT.toAbsolutePath() + " — this IT asserts that literal "
                        + "agrees with the " + ROOT_PATH_LABEL + " label, and it cannot do so if the "
                        + "variable has been renamed or removed. Restore the assignment, or update "
                        + SCRIPT_ROOT_PATH_VAR + " here to follow the rename"));
    }

    /**
     * Reads a hand-maintained config file this IT asserts against the label.
     *
     * @param path the file to read, relative to the module directory
     * @return the file's lines
     */
    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }

    /**
     * Strips the surrounding quotes a scalar may carry in either source format.
     *
     * @param value the raw scalar
     * @return the value without quote characters
     */
    private static String unquote(String value) {
        return value.replace("'", "").replace("\"", "");
    }
}
