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
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
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
 * The per-source detection substrate wired into the live edge, driven over a Vert.x front server. A
 * scan — unrouted addresses and pre-trust rejections of a bearer route, all from one source — trips the
 * source. With no {@code enumeration_detection} block declared the trip is recorded and every answer
 * stays byte for byte what it was before the trip: the shipped default is {@code observe}. Under a
 * declared {@code block} the next request is answered {@code 429} before admission; under a declared
 * {@code delay} the rejection answers are held. Browser-ambient fetches are never counted while a path
 * ending in a static extension is, a source is the
 * forwarded client for a trusted proxy and the TCP peer otherwise, and a disabled block never acts.
 */
@EnableTestLogger
@DisplayName("GatewayEdgeRoute — per-source enumeration detection")
@Tag("isolated-fork")
class GatewayEdgeSourceDetectionTest {

    private static final String PROBE = "/scan/probe";
    private static final String SECURE = "/secure/data";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String SCANNER = "203.0.113.7";
    private static final String HONEST = "198.51.100.9";
    private static final int THRESHOLD = 3;
    /**
     * The probes a scan sends before it is expected to be blocked: one past the threshold. The bucket
     * drains continuously, so the level after exactly {@link #THRESHOLD} spaced probes sits a hair below
     * it; the next probe is the one that reaches it.
     */
    private static final int SCAN_LENGTH = THRESHOLD + 1;
    private static final int DELAY_MILLIS = 400;
    private static final Duration METRIC_WAIT = Duration.ofSeconds(10);

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpClient client;
    private SimpleMeterRegistry registry;
    private @Nullable HttpServer front;

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
        if (front != null) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("no block declared: a scan trips the source under observe and every answer stays byte for byte the same")
    void undeclaredBlockObservesWithoutChangingAnyAnswer() throws Exception {
        start(null, List.of());
        Response probeBefore = send(PROBE, Map.of());
        Response secureBefore = send(SECURE, Map.of());

        List<CompletableFuture<Response>> scan = new ArrayList<>();
        for (int index = 0; index < EnumerationDetectionConfig.DEFAULT_THRESHOLD; index++) {
            scan.add(sendAsync(index % 2 == 0 ? PROBE : SECURE, Map.of()));
        }
        Awaits.connect(CompletableFuture.allOf(scan.toArray(new CompletableFuture<?>[0])), "the scan to be answered");
        Response probeAfter = send(PROBE, Map.of());
        Response secureAfter = send(SECURE, Map.of());

        Counter trips = registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).tag("action", "observe").counter();
        assertAll("the trip is recorded and nothing on the wire changes",
                () -> assertEquals(404, probeBefore.status(), "precondition: an unrouted probe"),
                () -> assertEquals(404, secureBefore.status(), "precondition: a pre-trust rejection, rendered 404"),
                () -> assertTrue(scan.stream().allMatch(answer -> answer.join().status() == 404),
                        "no answer of the scan was refused or changed"),
                () -> assertEquals(probeBefore, probeAfter, "the unrouted answer is byte-identical after the trip"),
                () -> assertEquals(secureBefore, secureAfter, "the rejection answer is byte-identical after the trip"),
                () -> assertNotNull(trips, "sheriff_source_trips_total{action=observe} records the trip"),
                () -> assertEquals(1.0, trips.count(), "one trip per period, however long the scan goes on"),
                () -> assertNull(errorsFor(EventType.SOURCE_BLOCKED), "nothing is blocked under observe"));
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString());
    }

    @Test
    @DisplayName("block declared: the next request is answered 429 before admission, the admission count unchanged")
    void declaredBlockAnswers429BeforeAdmission() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(THRESHOLD)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of());
        for (int index = 0; index < SCAN_LENGTH; index++) {
            assertEquals(404, send(PROBE, Map.of()).status(), "a probe up to the trip is answered as before");
        }
        Awaitility.await().atMost(METRIC_WAIT).until(() -> admittedRequests() == SCAN_LENGTH);

        Response blocked = send(PROBE, Map.of());

        Counter blockedErrors = errorsFor(EventType.SOURCE_BLOCKED);
        assertAll("refused before admission",
                () -> assertEquals(429, blocked.status()),
                () -> assertNotNull(blocked.headers().get(ErrorResponseWriter.RETRY_AFTER_HEADER),
                        "the 429 names when to retry"),
                () -> assertTrue(blocked.body().contains("urn:api-sheriff:problem:rate-limiting"), blocked.body()),
                () -> assertEquals(SCAN_LENGTH, admittedRequests(),
                        "the refused request was never admitted, so it never reached the per-request metrics"),
                () -> assertNotNull(blockedErrors, "the refusal is metered as SOURCE_BLOCKED"),
                () -> assertEquals(1.0, blockedErrors.count()));
    }

    @Test
    @DisplayName("delay declared: once the source tripped, its rejection answers are held for the delay")
    void declaredDelayHoldsTheRejectionAnswers() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(2)
                .action(EnumerationDetectionConfig.Action.DELAY).delayMillis(DELAY_MILLIS).build(), List.of());
        send(PROBE, Map.of());
        send(PROBE, Map.of());

        long startedAt = System.nanoTime();
        Response held = send(PROBE, Map.of());
        long elapsedNanos = System.nanoTime() - startedAt;

        assertAll("held, not refused",
                () -> assertEquals(404, held.status(), "the answer itself is unchanged"),
                () -> assertTrue(elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS),
                        () -> "answered after " + elapsedNanos + " ns, before the " + DELAY_MILLIS + " ms delay"));
    }

    @Test
    @DisplayName("browser-ambient fetches are never counted: a run of them does not trip the source")
    void browserAmbientFetchesAreExcluded() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(2)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of());
        for (String ambient : List.of("/favicon.ico", "/robots.txt", "/apple-touch-icon.png",
                "/apple-touch-icon-precomposed.png", "/apple-touch-icon-180x180.png")) {
            assertEquals(404, send(ambient, Map.of()).status(), ambient);
        }

        Response probe = send(PROBE, Map.of());

        assertAll("the ambient fetches left the source untripped",
                () -> assertEquals(404, probe.status(), "the next probe is answered, not refused"),
                () -> assertNull(registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).counter(), "no trip was recorded"));
    }

    @Test
    @DisplayName("a scan of paths ending in static extensions trips the source; an honest caller is not refused")
    void staticExtensionScanTripsTheSource() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(THRESHOLD)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of(LoopbackHost.ADDRESS + "/32"));
        List<String> scan = List.of("/scan/logo.png", "/secure/app.js", "/scan/site.css", "/secure/font.woff2");
        for (int index = 0; index < SCAN_LENGTH; index++) {
            String path = scan.get(index % scan.size());
            assertEquals(404, send(path, Map.of(FORWARDED_FOR, SCANNER)).status(), path);
        }

        Response scanner = send("/scan/next.png", Map.of(FORWARDED_FOR, SCANNER));
        Response honest = send("/scan/next.png", Map.of(FORWARDED_FOR, HONEST));

        assertAll("an extension alone does not exempt a probe",
                () -> assertEquals(429, scanner.status(), "the scanning source is blocked"),
                () -> assertEquals(404, honest.status(), "an honest client behind the same proxy is answered"));
    }

    @Test
    @DisplayName("a trusted proxy's client is keyed by its forwarded address: the scanner is blocked, an honest caller is not")
    void trustedForwardedClientIsKeyedByItsForwardedAddress() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(THRESHOLD)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of(LoopbackHost.ADDRESS + "/32"));
        for (int index = 0; index < SCAN_LENGTH; index++) {
            send(PROBE, Map.of(FORWARDED_FOR, SCANNER));
        }

        Response scanner = send(PROBE, Map.of(FORWARDED_FOR, SCANNER));
        Response honest = send(PROBE, Map.of(FORWARDED_FOR, HONEST));
        Response proxyItself = send(PROBE, Map.of());

        assertAll("one source refused, the others untouched",
                () -> assertEquals(429, scanner.status(), "the forwarded scanner is blocked"),
                () -> assertEquals(404, honest.status(), "an honest client behind the same proxy is answered"),
                () -> assertEquals(404, proxyItself.status(), "the proxy's own address was never the key"));
    }

    @Test
    @DisplayName("an untrusted peer's X-Forwarded-For is ignored: rotating it does not escape the block")
    void untrustedPeerForwardingHeaderIsIgnored() throws Exception {
        start(EnumerationDetectionConfig.builder().threshold(THRESHOLD)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of());
        for (int index = 0; index < SCAN_LENGTH; index++) {
            send(PROBE, Map.of(FORWARDED_FOR, "192.0.2." + (index + 1)));
        }

        Response rotated = send(PROBE, Map.of(FORWARDED_FOR, HONEST));

        assertEquals(429, rotated.status(), "the source is the TCP peer, whatever the header claims");
    }

    @Test
    @DisplayName("enabled: false never acts: a scan neither trips nor blocks")
    void disabledDetectionNeverBlocks() throws Exception {
        start(EnumerationDetectionConfig.builder().enabled(false).threshold(1)
                .action(EnumerationDetectionConfig.Action.BLOCK).build(), List.of());

        List<Integer> statuses = new ArrayList<>();
        for (int index = 0; index < THRESHOLD; index++) {
            statuses.add(send(PROBE, Map.of()).status());
        }

        assertAll("no ledger, no gate",
                () -> assertEquals(List.of(404, 404, 404), statuses),
                () -> assertNull(registry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).counter(), "no trip was recorded"));
    }

    private void start(@Nullable EnumerationDetectionConfig detection, List<String> trustedProxies) throws Exception {
        GatewayConfig.GatewayConfigBuilder config = ConfigFixtures.gateway().enumerationDetection(detection);
        if (!trustedProxies.isEmpty()) {
            config.forwarded(ForwardedConfig.builder().trustedProxies(trustedProxies).build());
        }
        front = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .gatewayConfig(config.build())
                .routes(ConfigFixtures.route("secure", "/secure")
                        .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                        .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, 9, ""))
                        .build())
                .metrics(new SheriffMetrics(registry))
                .serve();
    }

    /** The requests the edge admitted: each admitted request is counted once from its end handler. */
    private double admittedRequests() {
        return registry.find(SheriffMetrics.REQUESTS_TOTAL).counters().stream().mapToDouble(Counter::count).sum();
    }

    private @Nullable Counter errorsFor(EventType eventType) {
        return registry.find(SheriffMetrics.ERRORS_TOTAL).tag("event", eventType.name()).counter();
    }

    private Response send(String uri, Map<String, String> requestHeaders) throws Exception {
        return Awaits.connect(sendAsync(uri, requestHeaders), "the edge response to GET " + uri);
    }

    private CompletableFuture<Response> sendAsync(String uri, Map<String, String> requestHeaders) {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(Objects.requireNonNull(front).actualPort()).setURI(uri);
        return client.request(options)
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
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }
}
