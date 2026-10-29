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
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.mintIntegrationRealmToken;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.securedAssetStatus;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves per-source enumeration detection end to end in the shipped native image: a source that
 * probes for addresses is tripped, a declared {@code action: block} then refuses it with {@code 429}
 * until the block expires, and the shipped default {@code observe} records the trip without changing
 * a single answer.
 * <p>
 * <strong>Two gateways, on purpose.</strong> The whole IT suite reaches the compose gateways from one
 * docker source address and runs rejection corpora against them, so the compose stack must never carry
 * a declared {@code block}: the first scan would refuse every suite that runs after it. The block leg
 * therefore runs against a one-off gateway this test starts itself over
 * {@code sheriff-config-enumeration/gateway.yaml} ({@code action: block}, {@value #THRESHOLD} recon
 * events, {@value #BLOCK_SECONDS} seconds), on the compose network so its issuer can reach Keycloak.
 * The observe leg runs against the shared primary instance, whose descriptor declares no
 * {@code enumeration_detection} block — {@link EnumerationDetectionActivationWiringTest} pins both
 * descriptors, so neither leg can silently run the other's configuration.
 * <p>
 * <strong>What the block leg proves, in the order the one gateway goes through it.</strong>
 * <ol>
 *   <li><em>The honest caller is never tripped</em> — {@value #HONEST_STATIC_MISSES} misses for the
 *       browser-ambient fetches (favicon, robots.txt, touch icons, on addresses no route serves) and
 *       {@value #HONEST_TOKENLESS_ASSET_FETCHES} tokenless fetches of the bearer-gated asset route — each
 *       more than the threshold on its own — are answered as before, the caller's token still fetches
 *       the asset, and no trip is metered. Browser-ambient fetches are not counted, and neither is a
 *       request whose route serves assets.</li>
 *   <li><em>A scan trips the source</em> — probes of unrouted addresses, alternated with a verb the
 *       global {@code allowed_methods} refuses, are answered as rejections until the source reaches the
 *       threshold; the next request is answered {@code 429} with {@code Retry-After} and the
 *       {@code rate-limiting} problem document, and the caller's valid token is refused alike: the block
 *       is on the source, not on the request.</li>
 *   <li><em>The trip is logged without what the caller sent</em> — exactly one trip {@code WARN}
 *       ({@value #SOURCE_TRIPPED_RECORD}) appears in the container log, naming the action and the folded
 *       host, and carrying neither the scanned path nor the raw {@code Host} the client sent.</li>
 *   <li><em>The block expires</em> — within {@value #BLOCK_EXPIRY_BOUND_SECONDS} seconds, below the
 *       default block of {@value EnumerationDetectionConfig#DEFAULT_BLOCK_SECONDS} seconds, the caller's
 *       token fetches the asset again.</li>
 * </ol>
 * Delete {@code action}, {@code threshold} or {@code block_seconds} from the one-off descriptor and a
 * leg goes red: without {@code action} no {@code 429} comes at all; without {@code threshold} the
 * default of {@value EnumerationDetectionConfig#DEFAULT_THRESHOLD} lies beyond the scan's
 * {@value #SCAN_CAP} requests; without {@code block_seconds} the default outlasts the expiry bound.
 * <p>
 * <strong>The matched control.</strong> The observe leg sends the same kind of scan — unrouted probes,
 * more than the default threshold — to the primary instance: every answer is the uniform {@code 404}
 * with no {@code 429}, {@code sheriff_source_trips_total{action="observe"}} reads at least {@code 1}
 * and nothing is metered as {@code SOURCE_BLOCKED}.
 * <p>
 * <strong>Why the boundaries are not asserted exactly.</strong> Every request of the run shares one
 * bucket from one source address, and the bucket drains continuously between requests, so the request
 * that reaches the threshold and the second at which the block expires are pinned at unit level with a
 * controllable clock ({@code SourceLedgerTest}), not here.
 * <p>
 * Runs in the sequential execution: it reads deltas of the primary instance's metrics, which a
 * concurrent suite could raise.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Enumeration detection: a declared block refuses a scanning source, the shipped observe changes no answer")
class EnumerationDetectionIT extends BaseIntegrationTest {

    /** {@code enumeration_detection.threshold} of the one-off descriptor. */
    static final int THRESHOLD = 20;

    /** {@code enumeration_detection.block_seconds} of the one-off descriptor. */
    static final int BLOCK_SECONDS = 5;

    /** Browser-ambient misses the honest leg sends: twice the threshold, so a counted miss would trip. */
    static final int HONEST_STATIC_MISSES = 2 * THRESHOLD;

    /**
     * Tokenless fetches of the bearer-gated asset route the honest leg sends: twice the threshold, so a
     * counted fetch would trip.
     */
    static final int HONEST_TOKENLESS_ASSET_FETCHES = 2 * THRESHOLD;

    /**
     * The most requests the scan sends before it must have been refused: well past the declared
     * threshold, and below the default threshold, so the scan cannot trip a gateway that runs the
     * default.
     */
    static final int SCAN_CAP = 3 * THRESHOLD;

    /**
     * Upper bound for the block to expire, counted from the first {@code 429}: the declared block plus
     * margin for a loaded runner, and below the default block, which a gateway without
     * {@code block_seconds} would hold.
     */
    static final long BLOCK_EXPIRY_BOUND_SECONDS = 20L;

    /**
     * Unrouted probes the observe leg sends to the primary instance: twice the default threshold, so
     * the drain over the scan cannot keep the source below it.
     */
    static final int OBSERVE_SCAN = 2 * EnumerationDetectionConfig.DEFAULT_THRESHOLD;

    /**
     * The fixed host port of the one-off gateway's application listener, published on every interface
     * (see {@link OneOffGatewayContainers#applicationPortPublication(int)}); used by no other suite.
     */
    private static final int APPLICATION_PORT = 10472;

    private static final Path ENUMERATION_GATEWAY =
            DOCKER.resolve(Path.of("sheriff-config-enumeration", "gateway.yaml"));

    /** WARN — a source tripped enumeration detection. */
    private static final String SOURCE_TRIPPED_RECORD = "ApiSheriff-141";

    private static final String SOURCE_TRIPS_TOTAL = "sheriff_source_trips_total";
    private static final String SOURCE_BLOCKED = "SOURCE_BLOCKED";
    private static final String RATE_LIMITING_PROBLEM_TYPE = "urn:api-sheriff:problem:rate-limiting";
    private static final String BLOCKED_STATUS_REASON = "429";
    private static final int TOO_MANY_REQUESTS = 429;

    /** A method outside the one-off descriptor's global {@code allowed_methods: ["GET"]}. */
    private static final String REFUSED_VERB = "DELETE";

    /** The browser-ambient fetches the honest leg's misses rotate through, none of them routed here. */
    private static final List<String> BROWSER_AMBIENT_PATHS = List.of("/favicon.ico", "/robots.txt",
            "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/apple-touch-icon-180x180.png");

    /** Upper bound for the one-off gateway to report readiness {@code UP} after {@code docker run}. */
    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /** Upper bound for the trip WARN to appear after the first {@code 429} was observed. */
    private static final long LOG_TIMEOUT_SECONDS = 10L;

    private static final long POLL_INTERVAL_MILLIS = 250L;

    @Test
    @DisplayName("block declared: the honest caller is served, a scan is refused 429 until the block expires")
    void declaredBlockRefusesAScanningSourceUntilTheBlockExpires() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String gateway = "enumeration-gateway-" + suffix;
        String scanMarker = "scan-" + suffix;
        try {
            startGateway(gateway, composeNetwork(), ENUMERATION_GATEWAY, APPLICATION_PORT);
            String managementOrigin = "https://localhost:" + publishedPort(gateway, 9000);
            String applicationOrigin = "https://localhost:" + APPLICATION_PORT;
            awaitReadiness(gateway, managementOrigin, response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                    "the enumeration gateway's readiness to report UP");
            assertApplicationPortAnswers(gateway, applicationOrigin);
            String bearer = mintIntegrationRealmToken();

            assertHonestCallerIsNeverTripped(gateway, applicationOrigin, managementOrigin, bearer);
            ExtractableResponse<Response> refused = scanUntilRefused(gateway, applicationOrigin, scanMarker);
            long refusedAt = System.nanoTime();
            assertRefusalIsTheRateLimitingAnswer(gateway, refused);
            assertEquals(TOO_MANY_REQUESTS, securedAssetStatus(applicationOrigin, bearer), () -> "the block refuses "
                    + "the source whatever it asks for, its valid token included. " + gatewayLog(gateway));
            assertEquals(1.0, trips(managementOrigin, "block"),
                    "exactly one trip is metered as sheriff_source_trips_total{action=\"block\"}");
            assertTripIsLoggedWithoutTheRequest(gateway, scanMarker);

            awaitServedAgain(gateway, applicationOrigin, bearer);
            long blockedSeconds = Duration.ofNanos(System.nanoTime() - refusedAt).toSeconds();
            assertTrue(blockedSeconds <= BLOCK_EXPIRY_BOUND_SECONDS,
                    () -> "the source was served again only after " + blockedSeconds + "s");
        } finally {
            removeContainer(gateway);
        }
    }

    @Test
    @DisplayName("no block declared: a scan of the shared stack is answered with the uniform 404 and is metered as observe")
    void shippedObserveDefaultRecordsTheTripAndChangesNoAnswer() {
        String marker = "observe-scan-" + UUID.randomUUID().toString().substring(0, 8);

        for (int index = 0; index < OBSERVE_SCAN; index++) {
            ExtractableResponse<Response> answer = given()
                    .header("Accept", UniformNotFoundAssertions.PROBLEM_JSON)
                    .redirects().follow(false)
                    .when()
                    .get(UniformNotFoundAssertions.UNROUTED_PATH + "/" + marker + "-" + index)
                    .then()
                    .extract();
            UniformNotFoundAssertions.assertUnroutedProblem(answer, "observe-leg probe " + index);
        }
        String metrics = PrometheusScrape.scrape();

        double observeTrips = PrometheusScrape.sum(metrics, SOURCE_TRIPS_TOTAL, "action=\"observe\"");
        double blockedRefusals = PrometheusScrape.sum(metrics, PrometheusScrape.ERRORS_TOTAL,
                "event=\"" + SOURCE_BLOCKED + "\"");
        assertAll("the shipped default records the trip and refuses nothing",
                () -> assertTrue(observeTrips >= 1.0, () -> SOURCE_TRIPS_TOTAL + "{action=\"observe\"} must read at least 1"
                        + " after " + OBSERVE_SCAN + " unrouted probes, was " + observeTrips),
                () -> assertEquals(0.0, blockedRefusals, "nothing is ever refused as SOURCE_BLOCKED under observe"),
                () -> assertEquals(0.0, PrometheusScrape.sum(metrics, SOURCE_TRIPS_TOTAL, "action=\"block\""),
                        "the shared stack runs no block"));
    }

    /**
     * The honest leg: browser-ambient misses and tokenless fetches of the asset route, each more than
     * the threshold on its own, trip nothing, and the caller's token still fetches the asset.
     */
    private static void assertHonestCallerIsNeverTripped(String gateway, String applicationOrigin,
            String managementOrigin, String bearer) {
        assertEquals(200, securedAssetStatus(applicationOrigin, bearer),
                () -> "precondition: the caller's token fetches the asset. " + gatewayLog(gateway));
        for (int index = 0; index < HONEST_STATIC_MISSES; index++) {
            String path = BROWSER_AMBIENT_PATHS.get(index % BROWSER_AMBIENT_PATHS.size());
            int status = request(applicationOrigin, "GET", path).statusCode();
            int missIndex = index;
            assertEquals(404, status, () -> "a browser-ambient miss is answered as an unrouted address, miss "
                    + missIndex + ": " + path + ". " + gatewayLog(gateway));
        }
        for (int index = 0; index < HONEST_TOKENLESS_ASSET_FETCHES; index++) {
            int status = securedAssetStatus(applicationOrigin, null);
            int fetchIndex = index;
            assertEquals(UniformNotFoundAssertions.UNROUTED_STATUS, status, () -> "a tokenless fetch of the "
                    + "bearer-gated asset is refused as before, fetch " + fetchIndex + ". " + gatewayLog(gateway));
        }
        assertAll("the honest caller stays below the threshold",
                () -> assertEquals(200, securedAssetStatus(applicationOrigin, bearer),
                        "the caller's token still fetches the asset"),
                () -> assertEquals(0.0, trips(managementOrigin, "block"),
                        "no trip is metered: browser-ambient fetches and asset-route requests are never counted"));
    }

    /**
     * Sends the scan — unrouted probes alternated with a refused verb — until the first {@code 429},
     * and returns it. Fails when the scan is refused before it can have reached the threshold, when an
     * answer before the refusal is not a rejection, or when no refusal comes within {@link #SCAN_CAP}.
     */
    private static ExtractableResponse<Response> scanUntilRefused(String gateway, String applicationOrigin,
            String scanMarker) {
        List<Integer> answered = new ArrayList<>();
        for (int index = 0; index < SCAN_CAP; index++) {
            String method = index % 2 == 0 ? "GET" : REFUSED_VERB;
            ExtractableResponse<Response> answer = request(applicationOrigin, method,
                    "/" + scanMarker + "/probe-" + index);
            int status = answer.statusCode();
            if (status == TOO_MANY_REQUESTS) {
                int answeredBefore = answered.size();
                assertTrue(answeredBefore >= THRESHOLD - 2, () -> "the source was refused after " + answeredBefore
                        + " scan requests, before it can have reached the threshold of " + THRESHOLD
                        + " — something else was counted: " + answered + ". " + gatewayLog(gateway));
                return answer;
            }
            int probeIndex = index;
            assertTrue(status >= 400 && status < 500, () -> "scan request " + probeIndex + " (" + method
                    + ") must be answered as a rejection before the trip, was " + status + ". " + gatewayLog(gateway));
            answered.add(status);
        }
        return fail("no " + BLOCKED_STATUS_REASON + " within " + SCAN_CAP + " scan requests, past the threshold of "
                + THRESHOLD + ": " + answered + ". " + gatewayLog(gateway));
    }

    /** Asserts the refusal is the {@code 429} rate-limiting answer with a {@code Retry-After} in range. */
    private static void assertRefusalIsTheRateLimitingAnswer(String gateway, ExtractableResponse<Response> refused) {
        String body = refused.asString();
        String retryAfter = refused.header("Retry-After");
        assertNotNull(retryAfter, () -> "the 429 names when to retry: " + body);
        long retryAfterSeconds = Long.parseLong(retryAfter.strip());
        assertAll("the refusal of a blocked source",
                () -> assertEquals(TOO_MANY_REQUESTS, refused.statusCode()),
                () -> assertTrue(retryAfterSeconds >= 1 && retryAfterSeconds <= BLOCK_SECONDS,
                        () -> "Retry-After must name the whole seconds left of the " + BLOCK_SECONDS
                                + "-second block, was " + retryAfterSeconds),
                () -> assertTrue(String.valueOf(refused.contentType()).startsWith(UniformNotFoundAssertions.PROBLEM_JSON),
                        () -> "the refusal is a problem document; content type was " + refused.contentType()),
                () -> assertEquals(RATE_LIMITING_PROBLEM_TYPE, refused.path("type"),
                        () -> "the rate-limiting problem type: " + body + " " + gatewayLog(gateway)),
                () -> assertEquals(TOO_MANY_REQUESTS, ((Number) refused.path("status")).intValue(), body));
    }

    /**
     * Asserts the container log carries exactly one trip WARN, naming the action and the folded host and
     * neither the scanned path nor the raw {@code Host} header the client sent.
     */
    private static void assertTripIsLoggedWithoutTheRequest(String gateway, String scanMarker) {
        awaitLogRecord(gateway, SOURCE_TRIPPED_RECORD, LOG_TIMEOUT_SECONDS,
                "the trip must be logged as WARN " + SOURCE_TRIPPED_RECORD);
        List<String> tripLines = docker("read the enumeration gateway log", "logs", gateway).lines()
                .filter(line -> line.contains(SOURCE_TRIPPED_RECORD)).toList();
        String rawHost = "localhost:" + APPLICATION_PORT;
        assertEquals(1, tripLines.size(), () -> "one trip per period, however long the scan went on: " + tripLines);
        String tripLine = tripLines.getFirst();
        assertAll("the trip WARN",
                () -> assertTrue(tripLine.contains("action: block"), () -> "names the configured action: " + tripLine),
                () -> assertTrue(tripLine.contains("host '*'"),
                        () -> "names the host folded to the declared set, here none: " + tripLine),
                () -> assertFalse(tripLine.contains(scanMarker), () -> "must not carry the scanned path: " + tripLine),
                () -> assertFalse(tripLine.contains(rawHost), () -> "must not carry the raw Host: " + tripLine));
    }

    /**
     * Polls the bearer-gated asset with the caller's token until it is served again. Every poll while the
     * block holds is answered {@code 429}, which is never counted towards a further trip.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the block's expiry is the subject of this wait
    private static void awaitServedAgain(String gateway, String applicationOrigin, String bearer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(BLOCK_EXPIRY_BOUND_SECONDS).toNanos();
        int lastStatus = TOO_MANY_REQUESTS;
        while (System.nanoTime() < deadline) {
            lastStatus = securedAssetStatus(applicationOrigin, bearer);
            if (lastStatus == 200) {
                return;
            }
            assertEquals(TOO_MANY_REQUESTS, lastStatus, "while the block holds the source is refused 429");
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the block to expire", interrupted);
            }
        }
        int finalStatus = lastStatus;
        fail("the source was not served again within " + BLOCK_EXPIRY_BOUND_SECONDS + "s of the first 429; last status "
                + finalStatus + ". " + gatewayLog(gateway));
    }

    private static double trips(String managementOrigin, String action) {
        return PrometheusScrape.sum(PrometheusScrape.scrape(managementOrigin), SOURCE_TRIPS_TOTAL,
                "action=\"" + action + "\"");
    }

    private static ExtractableResponse<Response> request(String applicationOrigin, String method, String path) {
        return given()
                .relaxedHTTPSValidation()
                .baseUri(applicationOrigin)
                .basePath("")
                .header("Accept", UniformNotFoundAssertions.PROBLEM_JSON)
                .redirects().follow(false)
                .when()
                .request(method, path)
                .then()
                .extract();
    }
}
