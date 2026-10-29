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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.LogRecord;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.edge.admission.ThreatAlertDocument;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Per-source threat classification wired into the live edge, driven over a Vert.x front server. A scan
 * of malformed requests from one source crosses the threat threshold and raises exactly one
 * {@code ApiSheriff-143} ALERT for its episode, however long it continues, while a single malformed
 * request raises none and is answered exactly as on an edge that does not classify. With no block
 * declared the shipped defaults build the classifier: a scan alerts once it reaches the default
 * threshold, and not one request earlier at the default weight. A scanner that trips
 * the recon ledger as well gives one recon trip and one ALERT, and the recon trip is one more weighted
 * input to the score. The score is kept with {@code enumeration_detection} disabled; with
 * {@code threat_classification} disabled no ALERT is raised and recon detection is unchanged. A
 * rejection past the trust boundary is scored, and a static-resource miss is not.
 * <p>
 * Every edge here declares {@code observe}, or no {@code enumeration_detection} block at all, so no
 * answer is ever refused: the ALERT is the only effect of a crossing. The weights and thresholds are
 * chosen so the drain over a test's run cannot keep a source below its threshold, and so that a weight
 * that is not scored would cross it on its own.
 */
@EnableTestLogger
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — per-source threat classification")
@Tag("isolated-fork")
class GatewayEdgeThreatClassificationTest {

    /**
     * A request the pre-route canonical-path guard refuses as {@code SECURITY_FILTER_VIOLATION}: the raw
     * path carries an encoded separator. Its category is {@code input_validation}.
     */
    private static final String MALFORMED = "/scan%2fprobe";
    private static final String UNROUTED = "/scan/probe";
    private static final String SCOPED = "/scoped/data";
    private static final String NEVER_GRANTED_SCOPE = "sheriff-threat-never-granted";

    /** A threat threshold three default {@code input_validation} weights reach. */
    private static final int MALFORMED_THRESHOLD = 3 * ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION;

    /** The malformed requests of a scan: four times what reaches {@link #MALFORMED_THRESHOLD}. */
    private static final int MALFORMED_SCAN = 4 * MALFORMED_THRESHOLD / ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION;

    /** The recon threshold of the both-ledgers case: a handful of unrouted probes trips it. */
    private static final int RECON_THRESHOLD = 3;

    /**
     * The threat threshold of the both-ledgers case: one above the default recon-trip weight, so only a
     * recon trip plus the probes' own routing weight reach it — the probes alone never do.
     */
    private static final int RECON_FED_THRESHOLD = ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP + 1;

    /** The unrouted probes of the both-ledgers case: well past the recon threshold, far below the threat one. */
    private static final int RECON_SCAN = 10;

    /** Round trips the timing comparison takes the fastest of, on each edge. */
    private static final int TIMING_ATTEMPTS = 3;

    /** How much slower than the non-classifying edge the classifying edge's fastest answer may be. */
    private static final Duration TIMING_TOLERANCE = Duration.ofMillis(250);

    private static final Duration METRIC_WAIT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<HttpServer> fronts = new ArrayList<>();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpClient client;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        registry = new SimpleMeterRegistry();
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        for (HttpServer front : fronts) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("a scan of malformed requests crosses the threshold and logs exactly one ALERT, however long it goes on")
    void scanRaisesExactlyOneAlertPerEpisode() throws Exception {
        HttpServer front = start(gateway(null, ThreatClassificationConfig.builder().threshold(MALFORMED_THRESHOLD).build()),
                registry);

        List<Response> scan = new ArrayList<>();
        for (int index = 0; index < MALFORMED_SCAN; index++) {
            scan.add(send(front, MALFORMED + "-" + index, Map.of()));
        }
        awaitAlertCount(1.0);

        Response first = scan.getFirst();
        assertAll("one ALERT for the episode, and no answer changed",
                () -> assertEquals(1, alertRecords().size(), "exactly one ApiSheriff-143 for the whole scan"),
                () -> assertEquals(1.0, alertCount(), "sheriff_threat_alerts_total counts the one ALERT"),
                () -> assertTrue(first.status() >= 400 && first.status() < 500, "precondition: a rejection, was "
                        + first.status()),
                () -> assertTrue(scan.stream().allMatch(answer -> answer.status() == first.status()
                        && answer.body().equals(first.body())), "every answer of the scan is the first one"),
                () -> assertEquals((double) MALFORMED_SCAN, errorCount(EventType.SECURITY_FILTER_VIOLATION),
                        "precondition: every probe is a SECURITY_FILTER_VIOLATION"),
                () -> assertTrue(recordsOf(ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString()).isEmpty(),
                        "the recon ledger stays below its default threshold"));
        JsonNode document = alertDocument(alertRecords().getFirst());
        JsonNode breakdown = document.path("api_sheriff").path("threat");
        assertAll("the ALERT document of the scan",
                () -> assertEquals(ThreatAlertDocument.EVENT_KIND, document.path("event").path("kind").asText()),
                () -> assertEquals(MALFORMED_THRESHOLD, breakdown.path("threshold").asInt()),
                () -> assertTrue(breakdown.path("categories").path(EventCategory.INPUT_VALIDATION.slug()).asLong()
                        >= MALFORMED_THRESHOLD,
                        () -> "the malformed requests carried the score: " + breakdown),
                () -> assertTrue(breakdown.path("contributions").has(EventType.SECURITY_FILTER_VIOLATION.name()),
                        () -> "scored under the TRUE event: " + breakdown));
    }

    @Test
    @DisplayName("no block declared: the shipped defaults build the classifier, which alerts once a scan reaches the default threshold")
    void omittedBlockRunsTheShippedDefaults() throws Exception {
        HttpServer front = start(gateway(null, null), registry);
        int belowThreshold = ThreatClassificationConfig.DEFAULT_THRESHOLD
                / ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION - 1;

        for (int index = 0; index < belowThreshold; index++) {
            send(front, MALFORMED + "-" + index, Map.of());
        }
        boolean silentBelowThreshold = alertRecords().isEmpty();
        for (int index = belowThreshold; index < belowThreshold + 3; index++) {
            send(front, MALFORMED + "-" + index, Map.of());
        }
        awaitAlertCount(1.0);

        JsonNode breakdown = alertDocument(alertRecords().getFirst()).path("api_sheriff").path("threat");
        assertAll("the omitted block resolves to the defaults at the real edge",
                () -> assertTrue(silentBelowThreshold, () -> belowThreshold + " malformed requests at the default weight "
                        + "stay below the default threshold"),
                () -> assertEquals(1, alertRecords().size(), "the requests past it raise exactly one ALERT"),
                () -> assertEquals(ThreatClassificationConfig.DEFAULT_THRESHOLD, breakdown.path("threshold").asInt()),
                () -> assertEquals(ThreatClassificationConfig.DEFAULT_WINDOW_SECONDS,
                        breakdown.path("window_seconds").asInt()));
    }

    @Test
    @DisplayName("a single malformed request raises no ALERT, and its status, body and timing are those of an edge that does not classify")
    void singleMalformedRequestIsBenign() throws Exception {
        HttpServer classifying = start(gateway(null, null), registry);
        HttpServer reference = start(gateway(null, ThreatClassificationConfig.builder().enabled(false).build()),
                new SimpleMeterRegistry());

        Response classified = send(classifying, MALFORMED, Map.of());
        Response unclassified = send(reference, MALFORMED, Map.of());
        long classifyingFastest = fastestAnswer(classifying, classified.status());
        long referenceFastest = fastestAnswer(reference, unclassified.status());

        assertAll("one malformed request is no threat",
                () -> assertEquals(unclassified.status(), classified.status(), "the status is unchanged"),
                () -> assertEquals(unclassified.body(), classified.body(), "the body is unchanged"),
                () -> assertTrue(classifyingFastest <= referenceFastest + TIMING_TOLERANCE.toNanos(),
                        () -> "the classifying edge's fastest answer took " + classifyingFastest
                                + " ns against " + referenceFastest + " ns: the classifier must not hold an answer"),
                () -> assertTrue(alertRecords().isEmpty(), "no ApiSheriff-143 for a benign mistake"),
                () -> assertNull(registry.find(SheriffMetrics.THREAT_ALERTS_TOTAL).counter(), "no ALERT counted"));
    }

    @Test
    @DisplayName("a scanner tripping both ledgers gives one recon trip and exactly one ALERT, which carries the recon trip")
    void scannerTrippingBothLedgersRaisesOneAlertCarryingTheReconTrip() throws Exception {
        HttpServer front = start(gateway(
                EnumerationDetectionConfig.builder().threshold(RECON_THRESHOLD).build(),
                ThreatClassificationConfig.builder().threshold(RECON_FED_THRESHOLD).build()), registry);

        for (int index = 0; index < RECON_SCAN; index++) {
            assertEquals(404, send(front, UNROUTED + "-" + index, Map.of()).status(), "observe changes no answer");
        }
        awaitAlertCount(1.0);

        JsonNode categories = alertDocument(alertRecords().getFirst()).path("api_sheriff").path("threat")
                .path("categories");
        Counter reconTrips = registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).tag("action", "observe").counter();
        assertAll("one trip of each ledger",
                () -> assertEquals(1, recordsOf(ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString()).size(),
                        "exactly one ApiSheriff-141"),
                () -> assertNotNull(reconTrips, "the recon trip is metered"),
                () -> assertEquals(1.0, reconTrips == null ? 0.0 : reconTrips.count()),
                () -> assertEquals(1, alertRecords().size(), "exactly one ApiSheriff-143: the recon trip is no ALERT of its own"),
                () -> assertEquals(1.0, alertCount()),
                () -> assertEquals(ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP,
                        categories.path(ThreatAlertDocument.RECON_TRIP_CATEGORY).asInt(),
                        () -> "the recon trip is folded under recon_trip: " + categories));
    }

    @Test
    @DisplayName("enumeration_detection disabled: the threat score is still kept and still alerts")
    void scoreIsKeptWithoutEnumerationDetection() throws Exception {
        HttpServer front = start(gateway(EnumerationDetectionConfig.builder().enabled(false).build(),
                ThreatClassificationConfig.builder().threshold(MALFORMED_THRESHOLD).build()), registry);

        for (int index = 0; index < MALFORMED_SCAN; index++) {
            send(front, MALFORMED + "-" + index, Map.of());
        }
        awaitAlertCount(1.0);

        assertAll("the source key does not depend on recon detection",
                () -> assertEquals(1, alertRecords().size(), "the scan alerts once"),
                () -> assertNull(registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).counter(), "no recon ledger runs"));
    }

    @Test
    @DisplayName("threat_classification disabled: no ALERT is raised and recon detection is unchanged")
    void disabledClassificationRaisesNothingAndLeavesReconUnchanged() throws Exception {
        HttpServer front = start(gateway(EnumerationDetectionConfig.builder().threshold(RECON_THRESHOLD).build(),
                ThreatClassificationConfig.builder().enabled(false).build()), registry);

        List<Integer> statuses = new ArrayList<>();
        for (int index = 0; index < RECON_SCAN; index++) {
            statuses.add(send(front, UNROUTED + "-" + index, Map.of()).status());
        }
        Awaitility.await().atMost(METRIC_WAIT)
                .until(() -> registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).counter() != null);

        Counter reconTrips = registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).tag("action", "observe").counter();
        assertAll("no classifier, the recon ledger as before",
                () -> assertTrue(statuses.stream().allMatch(status -> status == 404), () -> "every probe answered: "
                        + statuses),
                () -> assertNotNull(reconTrips, "the recon trip is still metered"),
                () -> assertEquals(1.0, reconTrips == null ? 0.0 : reconTrips.count()),
                () -> assertEquals(1, recordsOf(ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString()).size(),
                        "the recon trip is still logged once"),
                () -> assertTrue(alertRecords().isEmpty(), "no ApiSheriff-143"),
                () -> assertNull(registry.find(SheriffMetrics.THREAT_ALERTS_TOTAL).counter(), "no ALERT counted"));
    }

    @Test
    @DisplayName("a rejection past the trust boundary is scored: scope refusals of a validated caller alert")
    void postTrustRejectionIsScored() throws Exception {
        TestTokenHolder tokenHolder = TestTokenGenerators.accessTokens().next();
        TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(tokenHolder.getIssuerConfig()).build();
        int authorization = ThreatClassificationConfig.Weights.DEFAULT_AUTHORIZATION;
        HttpServer front = startWithValidator(gateway(null, ThreatClassificationConfig.builder()
                .threshold(2 * authorization).build()), tokenValidator);
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + tokenHolder.getRawToken(),
                "Accept", "application/json");

        List<Integer> statuses = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            statuses.add(send(front, SCOPED, bearer).status());
        }
        awaitAlertCount(1.0);

        assertAll("the trust boundary does not end scoring",
                () -> assertEquals(List.of(403, 403, 403, 403), statuses, "precondition: honest scope refusals"),
                () -> assertEquals(4.0, errorCount(EventType.SCOPE_MISSING), "precondition: SCOPE_MISSING each time"),
                () -> assertEquals(1, alertRecords().size(), "the post-trust refusals raised the ALERT"),
                () -> assertTrue(alertDocument(alertRecords().getFirst()).path("api_sheriff").path("threat")
                        .path("contributions").has(EventType.SCOPE_MISSING.name()), "scored under SCOPE_MISSING"));
    }

    @Test
    @DisplayName("a static-resource miss is not scored, while the same weight on a probe alerts at once")
    void staticResourceMissIsNotScored() throws Exception {
        int threshold = MALFORMED_THRESHOLD;
        HttpServer front = start(gateway(null, ThreatClassificationConfig.builder().threshold(threshold)
                .weights(ThreatClassificationConfig.Weights.builder().routing(threshold).build()).build()), registry);

        for (String ambient : List.of("/favicon.ico", "/robots.txt", "/apple-touch-icon.png",
                "/apple-touch-icon-precomposed.png", "/apple-touch-icon-180x180.png")) {
            assertEquals(404, send(front, ambient, Map.of()).status(), ambient);
        }
        boolean silentAfterAmbient = alertRecords().isEmpty();
        send(front, UNROUTED, Map.of());
        awaitAlertCount(1.0);

        assertAll("the static exclusion, against its matched control",
                () -> assertTrue(silentAfterAmbient, "five browser-ambient misses, each weighed at the threshold if "
                        + "scored, raised no ALERT"),
                () -> assertEquals(1, alertRecords().size(), "control: one probe of the same category alerts"));
    }

    private static GatewayConfig gateway(@Nullable EnumerationDetectionConfig detection,
            @Nullable ThreatClassificationConfig classification) {
        return ConfigFixtures.gateway().enumerationDetection(detection).threatClassification(classification).build();
    }

    private HttpServer start(GatewayConfig config, SimpleMeterRegistry meters) throws Exception {
        return serve(EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .gatewayConfig(config)
                .routes(scopedRoute())
                .metrics(new SheriffMetrics(meters)));
    }

    private HttpServer startWithValidator(GatewayConfig config, TokenValidator tokenValidator) throws Exception {
        return serve(EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .gatewayConfig(config)
                .routes(scopedRoute())
                .tokenValidator(tokenValidator)
                .metrics(new SheriffMetrics(registry)));
    }

    private HttpServer serve(EdgeFixtures.Edge edge) throws Exception {
        HttpServer front = edge.serve();
        fronts.add(front);
        return front;
    }

    /** A bearer route whose needed scope no token carries: a validated caller is refused 403 past the trust boundary. */
    private static Route scopedRoute() {
        return ConfigFixtures.route("scoped", "/scoped")
                .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                .neededScopes(Set.of(NEVER_GRANTED_SCOPE))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, 9, ""))
                .build();
    }

    private void awaitAlertCount(double expected) {
        Awaitility.await().atMost(METRIC_WAIT).until(() -> alertCount() >= expected);
    }

    private double alertCount() {
        Counter alerts = registry.find(SheriffMetrics.THREAT_ALERTS_TOTAL).counter();
        return alerts == null ? 0.0 : alerts.count();
    }

    private double errorCount(EventType eventType) {
        return registry.find(SheriffMetrics.ERRORS_TOTAL).tag("event", eventType.name()).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static List<LogRecord> alertRecords() {
        return recordsOf(ApiSheriffLogMessages.WARN.THREAT_ALERT.resolveIdentifierString());
    }

    private static List<LogRecord> recordsOf(String identifier) {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN, identifier);
    }

    /** The ECS document the ALERT record carries after its fixed {@code ECS alert: } marker. */
    private JsonNode alertDocument(LogRecord alert) throws Exception {
        String message = alert.getMessage();
        String marker = "ECS alert: ";
        return objectMapper.readTree(message.substring(message.indexOf(marker) + marker.length()));
    }

    /** The shortest of {@value #TIMING_ATTEMPTS} round trips of the malformed request to {@code front}. */
    private long fastestAnswer(HttpServer front, int expectedStatus) throws Exception {
        long fastest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < TIMING_ATTEMPTS; attempt++) {
            long sentAt = System.nanoTime();
            Response response = send(front, MALFORMED, Map.of());
            fastest = Math.min(fastest, System.nanoTime() - sentAt);
            assertEquals(expectedStatus, response.status(), MALFORMED);
        }
        return fastest;
    }

    private Response send(HttpServer front, String uri, Map<String, String> requestHeaders) throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS).setPort(front.actualPort())
                .setURI(uri);
        CompletableFuture<Response> future = client.request(options)
                .compose(request -> {
                    requestHeaders.forEach(request::putHeader);
                    return request.send();
                })
                .compose(response -> {
                    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                    return response.body().map(buffer -> new Response(response.statusCode(), headers,
                            buffer == null ? "" : buffer.toString()));
                })
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the edge response to GET " + uri);
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }
}
