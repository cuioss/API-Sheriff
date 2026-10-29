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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertApplicationPortAnswers;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitLogRecord;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.edge.admission.ThreatAlertDocument;
import de.cuioss.sheriff.gateway.events.EventCategory;

import io.restassured.path.json.JsonPath;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves per-source threat classification end to end in the shipped native image: a single malformed
 * request raises no ALERT, a scan of malformed requests raises exactly one {@code ApiSheriff-143} ALERT
 * however long it continues, every answer stays the rejection it was, and the ALERT's JSON tail is the
 * ECS document the SIEM side reads.
 * <p>
 * <strong>A one-off gateway, on purpose.</strong> The whole IT suite reaches the compose gateways from
 * one docker source address and runs rejection corpora against them, so the compose stack runs the
 * shipped defaults; a threshold low enough for a short scan would raise ALERTs for every suite's own
 * deliberate rejections. This test therefore starts its own gateway over
 * {@code sheriff-config-threat/gateway.yaml} ({@code threshold} {@value #THRESHOLD},
 * {@code window_seconds} {@value #WINDOW_SECONDS}, {@code weights.input_validation}
 * {@value #INPUT_VALIDATION_WEIGHT}), on the compose network so its issuer can reach Keycloak.
 * <p>
 * <strong>What it proves, in the order the one gateway goes through it.</strong>
 * <ol>
 *   <li><em>Benign</em> — one malformed request (an encoded path separator, refused before routing as
 *       {@code SECURITY_FILTER_VIOLATION}) is answered as a rejection, and no {@code ApiSheriff-143} line
 *       appears within the log wait.</li>
 *   <li><em>Scan</em> — malformed probes continue to {@value #SCAN_REQUESTS} requests, many times the
 *       threshold; every answer is the benign request's status, never a {@code 429}; the ALERT must have
 *       appeared once {@value #ALERT_BOUND} malformed requests were answered, and exactly one
 *       {@code ApiSheriff-143} line appears for the whole scan.</li>
 *   <li><em>Field shape</em> — the JSON tail of that line parses and carries {@code event.kind},
 *       {@code event.category}, {@code event.severity}, {@code source.address} (and {@code source.ip}
 *       exactly when that address is an IPv4 literal), {@code threat.framework},
 *       {@code threat.technique.id} and the {@code api_sheriff.threat} breakdown with the declared
 *       threshold and window, and neither the scan marker nor the raw {@code Host}.</li>
 *   <li><em>Meter</em> — {@code sheriff_threat_alerts_total} reads exactly {@code 1}.</li>
 * </ol>
 * Delete {@code threshold}, {@code weights.input_validation} or {@code window_seconds} from the one-off
 * descriptor and a leg goes red: {@value #ALERT_BOUND} requests reach the declared threshold only at
 * the declared weight, and stay below the default threshold at it; the document must name the declared
 * window. {@link ThreatClassificationActivationWiringTest} pins the values and these bounds.
 * <p>
 * <strong>The shipped defaults.</strong> A second leg sends {@value #SHARED_STACK_SCAN} malformed
 * requests — twice the default threshold at the default weight — to the shared primary instance, whose
 * descriptor declares no {@code threat_classification} block: every answer is the first one, and
 * {@code sheriff_threat_alerts_total} reads at least {@code 1}. At least, not exactly: the suite reaches
 * that gateway from one source address, so an earlier suite's rejections may already have raised the
 * source's ALERT for the episode. The wiring test asserts the base descriptor declares no block.
 * <p>
 * Runs in the sequential execution: it starts a gateway on a fixed host port, reads its log and reads
 * the primary instance's metrics.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Threat classification: a scan raises exactly one ECS-shaped ALERT, a benign request none")
class ThreatClassificationIT extends BaseIntegrationTest {

    /** {@code threat_classification.threshold} of the one-off descriptor. */
    static final int THRESHOLD = 40;

    /** {@code threat_classification.window_seconds} of the one-off descriptor. */
    static final int WINDOW_SECONDS = 600;

    /** {@code threat_classification.weights.input_validation} of the one-off descriptor. */
    static final int INPUT_VALIDATION_WEIGHT = 25;

    /**
     * The malformed requests — the benign one included — by which the ALERT must have been raised: past
     * the declared threshold at the declared weight, below it at the default weight, and below the
     * default threshold at the declared weight.
     */
    static final int ALERT_BOUND = 3;

    /** The malformed requests of the whole run, the benign one included: many times the threshold. */
    static final int SCAN_REQUESTS = 12;

    /**
     * The malformed requests the shared-stack leg sends to the primary instance: twice the default
     * threshold at the default weight, so the drain over the scan cannot keep the source below it.
     */
    static final int SHARED_STACK_SCAN = 2 * ThreatClassificationConfig.DEFAULT_THRESHOLD
            / ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION;

    /**
     * The fixed host port of the one-off gateway's application listener, published on every interface
     * (see {@link OneOffGatewayContainers#applicationPortPublication(int)}); used by no other suite.
     */
    static final int APPLICATION_PORT = 10473;

    static final Path THREAT_GATEWAY = DOCKER.resolve(Path.of("sheriff-config-threat", "gateway.yaml"));

    /** WARN — a source reached the threat classification threshold. */
    private static final String THREAT_ALERT_RECORD = "ApiSheriff-143";

    private static final String THREAT_ALERTS_TOTAL = "sheriff_threat_alerts_total";
    private static final String ECS_MARKER = "ECS alert: ";
    private static final int TOO_MANY_REQUESTS = 429;

    /** Upper bound for the one-off gateway to report readiness {@code UP} after {@code docker run}. */
    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /** How long the benign leg watches the log for an ALERT that must not come. */
    private static final Duration BENIGN_LOG_WAIT = Duration.ofSeconds(5);

    /** Upper bound for the ALERT to appear once the alert bound was reached. */
    private static final long LOG_TIMEOUT_SECONDS = 10L;

    private static final long POLL_INTERVAL_MILLIS = 250L;

    @Test
    @DisplayName("one malformed request raises no ALERT; a scan raises exactly one, with the ECS fields, and changes no answer")
    void scanRaisesExactlyOneAlertAndABenignRequestNone() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String gateway = "threat-gateway-" + suffix;
        String scanMarker = "scan-" + suffix;
        try {
            startGateway(gateway, composeNetwork(), THREAT_GATEWAY, APPLICATION_PORT);
            String managementOrigin = "https://localhost:" + publishedPort(gateway, 9000);
            String applicationOrigin = "https://localhost:" + APPLICATION_PORT;
            awaitReadiness(gateway, managementOrigin, response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                    "the threat gateway's readiness to report UP");
            assertApplicationPortAnswers(gateway, applicationOrigin);

            int benignStatus = assertBenignRequestRaisesNoAlert(gateway, applicationOrigin, managementOrigin, scanMarker);
            scanPastTheThreshold(gateway, applicationOrigin, managementOrigin, scanMarker, benignStatus);
            String alertLine = singleAlertLine(gateway);
            assertAlertDocument(alertLine, scanMarker);
            assertEquals(1.0, alerts(managementOrigin), () -> THREAT_ALERTS_TOTAL + " must read exactly 1 after the scan. "
                    + gatewayLog(gateway));
        } finally {
            removeContainer(gateway);
        }
    }

    @Test
    @DisplayName("no block declared: a scan of the shared stack is answered as before and is metered as an ALERT")
    void shippedDefaultsAlertOnTheSharedStack() {
        String marker = "threat-scan-" + UUID.randomUUID().toString().substring(0, 8);

        int firstStatus = malformedOnTheSharedStack(marker, 0);
        for (int index = 1; index < SHARED_STACK_SCAN; index++) {
            int status = malformedOnTheSharedStack(marker, index);
            int probeIndex = index;
            assertEquals(firstStatus, status, () -> "shared-stack probe " + probeIndex + " must be answered as the first "
                    + "was: classification observes only");
        }
        double sharedAlerts = alertsIn(PrometheusScrape.scrape());

        assertAll("the shipped defaults classify and change no answer",
                () -> assertTrue(firstStatus >= 400 && firstStatus < 500 && firstStatus != TOO_MANY_REQUESTS,
                        () -> "a malformed request is answered as a rejection, was " + firstStatus),
                () -> assertTrue(sharedAlerts >= 1.0, () -> THREAT_ALERTS_TOTAL + " must read at least 1 after "
                        + SHARED_STACK_SCAN + " malformed requests at the default weight, was " + sharedAlerts));
    }

    /**
     * The benign leg: one malformed request is answered as a rejection, and within the log wait no ALERT
     * is logged or metered.
     *
     * @return the status the malformed request was answered with
     */
    private static int assertBenignRequestRaisesNoAlert(String gateway, String applicationOrigin,
            String managementOrigin, String scanMarker) {
        int status = malformed(applicationOrigin, scanMarker, 0).statusCode();
        assertTrue(status >= 400 && status < 500 && status != TOO_MANY_REQUESTS,
                () -> "precondition: a malformed request is answered as a rejection, was " + status + ". "
                        + gatewayLog(gateway));
        watchLogFor(BENIGN_LOG_WAIT);
        assertAll("a single malformed request is no threat",
                () -> assertFalse(docker("read the threat gateway log", "logs", gateway).contains(THREAT_ALERT_RECORD),
                        () -> "no " + THREAT_ALERT_RECORD + " for one malformed request. " + gatewayLog(gateway)),
                () -> assertEquals(0.0, alerts(managementOrigin), "no ALERT is metered"));
        return status;
    }

    /**
     * The scan leg: malformed probes up to {@link #SCAN_REQUESTS}, each answered with the benign status;
     * once {@link #ALERT_BOUND} requests were answered the ALERT must be metered.
     */
    private static void scanPastTheThreshold(String gateway, String applicationOrigin, String managementOrigin,
            String scanMarker, int benignStatus) {
        for (int index = 1; index < SCAN_REQUESTS; index++) {
            int status = malformed(applicationOrigin, scanMarker, index).statusCode();
            int probeIndex = index;
            assertEquals(benignStatus, status, () -> "scan request " + probeIndex + " must be answered as the benign "
                    + "request was: classification observes only. " + gatewayLog(gateway));
            if (index + 1 == ALERT_BOUND) {
                awaitLogRecord(gateway, THREAT_ALERT_RECORD, LOG_TIMEOUT_SECONDS, "the ALERT must be logged once "
                        + ALERT_BOUND + " malformed requests reached the declared threshold of " + THRESHOLD);
                assertEquals(1.0, alerts(managementOrigin), "exactly one ALERT is metered at the alert bound");
            }
        }
    }

    /** The one ALERT line of the container log; fails unless there is exactly one. */
    private static String singleAlertLine(String gateway) {
        List<String> alertLines = docker("read the threat gateway log", "logs", gateway).lines()
                .filter(line -> line.contains(THREAT_ALERT_RECORD)).toList();
        assertEquals(1, alertLines.size(), () -> "one ALERT per episode, however long the scan went on: " + alertLines);
        return alertLines.getFirst();
    }

    /**
     * Parses the JSON tail of the ALERT line and asserts the ECS fields, the breakdown with the declared
     * threshold and window, and the absence of anything the caller sent.
     */
    private static void assertAlertDocument(String alertLine, String scanMarker) {
        int marker = alertLine.indexOf(ECS_MARKER);
        assertTrue(marker >= 0, () -> "the ALERT carries its ECS document: " + alertLine);
        JsonPath document = JsonPath.from(alertLine.substring(marker + ECS_MARKER.length()).strip());
        String sourceAddress = document.getString("source.address");
        Map<String, Object> categories = document.getMap("api_sheriff.threat.categories");
        String rawHost = "localhost:" + APPLICATION_PORT;
        assertAll("the ECS document of the ALERT",
                () -> assertEquals(ThreatAlertDocument.EVENT_KIND, document.getString("event.kind")),
                () -> assertEquals(ThreatAlertDocument.EVENT_CATEGORIES, document.getList("event.category")),
                () -> assertEquals(ThreatAlertDocument.EVENT_SEVERITY, document.getInt("event.severity")),
                () -> assertNotNull(sourceAddress, "source.address is always written"),
                () -> assertSourceIpOnlyForIpv4(sourceAddress, document.getString("source.ip")),
                () -> assertEquals(ThreatAlertDocument.THREAT_FRAMEWORK, document.getString("threat.framework")),
                () -> assertEquals(List.of(ThreatAlertDocument.TECHNIQUE_ID), document.getList("threat.technique.id")),
                () -> assertEquals(THRESHOLD, document.getInt("api_sheriff.threat.threshold"),
                        "the declared threshold"),
                () -> assertEquals(WINDOW_SECONDS, document.getInt("api_sheriff.threat.window_seconds"),
                        "the declared window"),
                () -> assertTrue(document.getInt("api_sheriff.threat.score") >= THRESHOLD,
                        () -> "the score at the trip reached the threshold: " + alertLine),
                () -> assertNotNull(categories.get(EventCategory.INPUT_VALIDATION.slug()),
                        () -> "the malformed requests are folded under input-validation: " + categories),
                () -> assertFalse(alertLine.contains(scanMarker), () -> "must not carry the scanned path: " + alertLine),
                () -> assertFalse(alertLine.contains(rawHost), () -> "must not carry the raw Host: " + alertLine));
    }

    /**
     * ECS types {@code source.ip} as an IP address, so it is written exactly when the canonical source
     * is an IPv4 literal; a {@code /64} IPv6 prefix or the unknown-source key appears in
     * {@code source.address} only.
     */
    private static void assertSourceIpOnlyForIpv4(String sourceAddress, String sourceIp) {
        if (sourceAddress != null && sourceAddress.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            assertEquals(sourceAddress, sourceIp, "an IPv4 source is written as source.ip as well");
        } else {
            assertNull(sourceIp, () -> "a non-IPv4 source key " + sourceAddress + " must not be written as source.ip");
        }
        assertNotEquals("", sourceAddress, "source.address is never empty");
    }

    /**
     * Sends one malformed request: its raw path carries an encoded separator, which the pre-route
     * canonical-path guard refuses as {@code SECURITY_FILTER_VIOLATION}. URL encoding is disabled so the
     * gateway sees exactly this spelling.
     */
    private static ExtractableResponse<Response> malformed(String applicationOrigin, String scanMarker, int index) {
        return given()
                .relaxedHTTPSValidation()
                .urlEncodingEnabled(false)
                .baseUri(applicationOrigin)
                .basePath("")
                .header("Accept", UniformNotFoundAssertions.PROBLEM_JSON)
                .redirects().follow(false)
                .when()
                .get("/" + scanMarker + "%2fprobe-" + index)
                .then()
                .extract();
    }

    /**
     * Sends one malformed request to the primary instance of the shared stack, URL encoding disabled.
     *
     * @return the status it was answered with
     */
    private static int malformedOnTheSharedStack(String marker, int index) {
        return given()
                .urlEncodingEnabled(false)
                .header("Accept", UniformNotFoundAssertions.PROBLEM_JSON)
                .redirects().follow(false)
                .when()
                .get("/" + marker + "%2fprobe-" + index)
                .then()
                .extract()
                .statusCode();
    }

    /** The value of {@value #THREAT_ALERTS_TOTAL} on a one-off gateway. */
    private static double alerts(String managementOrigin) {
        return alertsIn(PrometheusScrape.scrape(managementOrigin));
    }

    /**
     * The value of {@value #THREAT_ALERTS_TOTAL} in a scraped exposition. The meter carries no label, so
     * its sample line is the name followed by a space, or by a brace when a common tag is configured; a
     * meter not yet recorded reads {@code 0.0}.
     */
    private static double alertsIn(String metrics) {
        return metrics.lines()
                .filter(line -> line.startsWith(THREAT_ALERTS_TOTAL + " ") || line.startsWith(THREAT_ALERTS_TOTAL + "{"))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1).strip()))
                .sum();
    }

    /** Watches the clock for the given duration: the absence of a log line can only be observed over time. */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the wait is the subject: an ALERT that must not appear
    private static void watchLogFor(Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while watching the threat gateway log", interrupted);
            }
        }
    }
}
