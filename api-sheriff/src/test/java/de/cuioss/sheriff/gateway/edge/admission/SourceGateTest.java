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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The per-source gate in front of edge admission, driven over a live Vert.x server whose one handler
 * stashes the source through {@link SourceKeys}, asks the gate and answers {@code 200} only when the
 * gate let the request through: a blocked source is answered {@code 429} with {@code Retry-After} and
 * the rate-limiting problem document, an unblocked source and a tripped source under the default
 * {@code observe} pass, and a trip is reported as an event, on the trip meter and as the trip WARN, then
 * handed to the recon-trip observer exactly once. Under {@code delay} a tripped source whose share of
 * the held-answer sub-budget is used up gets the same {@code 429}, and a held rejection carries its
 * source for that share. How the source is keyed is {@code SourceKeysTest}'s subject.
 */
@EnableTestLogger
@DisplayName("SourceGate — the per-source answer before admission")
@Tag("isolated-fork")
class SourceGateTest {

    private static final String PASSED = "passed";
    private static final int BLOCK_SECONDS = 60;
    /** A delay with a fractional second, so the rounding of its {@code Retry-After} is observable. */
    private static final int DELAY_MILLIS = 2_500;
    private static final long DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
    private static final long DELAY_RETRY_AFTER_SECONDS = 3L;

    /** The sources the gate is told have used up their held-answer share. */
    private final Set<SourceKey> exhaustedShares = ConcurrentHashMap.newKeySet();
    /** Every trip the gate handed its recon-trip observer. */
    private final List<SourceTrip> observedTrips = new CopyOnWriteArrayList<>();

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    private GatewayEventCounter eventCounter;
    private SimpleMeterRegistry meterRegistry;
    private final AtomicReference<SourceGate> gate = new AtomicReference<>();
    private final AtomicReference<SourceKey> stashed = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        eventCounter = new GatewayEventCounter();
        meterRegistry = new SimpleMeterRegistry();
        SourceKeys sourceKeys = sourceKeys();
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            sourceKeys.resolveAndStash(ctx);
            boolean answered = gate.get().answersBlocked(ctx);
            stashed.set(ctx.get(SourceKeys.SOURCE_KEY));
            if (!answered) {
                ctx.response().setStatusCode(200).end(PASSED);
            }
        });
        server = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the gate test server to start listening");
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(server.close(), "the gate test server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("a blocked source is answered 429 with Retry-After and the rate-limiting problem document")
    void blockedSourceIsAnswered429() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder()
                .threshold(1).action(EnumerationDetectionConfig.Action.BLOCK).blockSeconds(BLOCK_SECONDS).build());
        SourceState tripped = sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);

        Response response = send();

        EventCategory rateLimiting = EventCategory.RATE_LIMITING;
        String expectedBody = ErrorResponseWriter.problemBody(new GatewayJson(new ObjectMapper()),
                rateLimiting.problemType(), rateLimiting.title(), 429, Map.of());
        long retryAfter = Long.parseLong(response.headers().getOrDefault(ErrorResponseWriter.RETRY_AFTER_HEADER, "0"));
        Counter blocked = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                .tags("route", SheriffMetrics.NO_ROUTE, "event", EventType.SOURCE_BLOCKED.name()).counter();
        assertAll("the blocked source's answer",
                () -> assertTrue(tripped.isBlocked(), "precondition: the source is blocked"),
                () -> assertEquals(429, response.status()),
                () -> assertTrue(retryAfter >= 1 && retryAfter <= BLOCK_SECONDS,
                        "Retry-After is the whole seconds left of the block: " + retryAfter),
                () -> assertEquals("application/problem+json", response.headers().get("Content-Type")),
                () -> assertEquals(expectedBody, response.body()),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_BLOCKED)),
                () -> assertNotNull(blocked, "sheriff_errors_total carries SOURCE_BLOCKED under the no-route label"),
                () -> assertEquals(1.0, blocked.count()));
    }

    @Test
    @DisplayName("an unblocked source passes, with its source resolved and stashed")
    void unblockedSourcePasses() throws Exception {
        gate(EnumerationDetectionConfig.builder().action(EnumerationDetectionConfig.Action.BLOCK).build());

        Response response = send();

        assertAll("nothing is refused",
                () -> assertEquals(200, response.status()),
                () -> assertEquals(PASSED, response.body()),
                () -> assertEquals(loopbackKey(), stashed.get(),
                        "the source is the TCP peer, the undeclared IP host folded to *"),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under the default observe a tripped source passes, and the trip is reported once")
    void trippedSourceUnderObservePasses() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(1).build());
        SourceState tripped = sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);

        Response response = send();

        Counter trips = meterRegistry.find(SheriffMetrics.SOURCE_TRIPS_TOTAL).tag("action", "observe").counter();
        assertAll("a trip changes no answer under observe",
                () -> assertEquals(SourceState.Status.TRIPPED, tripped.status(), "precondition: the source tripped"),
                () -> assertEquals(200, response.status()),
                () -> assertEquals(PASSED, response.body()),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_TRIPPED)),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)),
                () -> assertNotNull(trips, "the trip is counted under its action"),
                () -> assertEquals(1.0, trips.count()),
                () -> assertNull(meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                        .tag("event", EventType.SOURCE_BLOCKED.name()).counter(), "nothing was refused"));
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString());
    }

    @Test
    @DisplayName("the recon-trip observer receives each trip exactly once, after it was reported")
    void reconTripObserverReceivesEachTripOnce() {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(2).build());
        Map<String, Object> data = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, loopbackKey()));

        sourceGate.admit(feedContext(data), null, EventType.NO_ROUTE_MATCHED);
        int beforeTrip = observedTrips.size();
        for (int count = 0; count < 5; count++) {
            sourceGate.admit(feedContext(data), null, EventType.NO_ROUTE_MATCHED);
        }

        assertAll("one trip, one observation",
                () -> assertEquals(0, beforeTrip, "control: no observation below the threshold"),
                () -> assertEquals(1, observedTrips.size(), "the episode's further rejections trip nothing"),
                () -> assertEquals(loopbackKey(), observedTrips.getFirst().key()),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_TRIPPED),
                        "the trip was counted as before"));
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.SOURCE_TRIPPED.resolveIdentifierString());
    }

    @Test
    @DisplayName("a request without a stashed source is neither answered nor admitted")
    void requestWithoutAStashedSourceIsIgnored() {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(1)
                .action(EnumerationDetectionConfig.Action.BLOCK).blockSeconds(BLOCK_SECONDS).build());
        sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);
        Map<String, Object> data = new HashMap<>();

        boolean answered = sourceGate.answersBlocked(feedContext(data));
        OptionalLong hold = sourceGate.admit(feedContext(data), null, EventType.NO_ROUTE_MATCHED);

        assertAll("no source, no decision",
                () -> assertFalse(answered, "a blocked source exists, but the request names none"),
                () -> assertTrue(hold.isEmpty()),
                () -> assertEquals(1, sourceGate.ledger().trackedSources(), "nothing new was tracked"));
    }

    @Test
    @DisplayName("under delay a tripped source whose held-answer share is used up is answered the block-style 429")
    void trippedSourcePastItsShareUnderDelayIsAnswered429() throws Exception {
        SourceGate sourceGate = gate(delay());
        SourceState tripped = sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);
        exhaustedShares.add(loopbackKey());

        Response response = send();

        EventCategory rateLimiting = EventCategory.RATE_LIMITING;
        String expectedBody = ErrorResponseWriter.problemBody(new GatewayJson(new ObjectMapper()),
                rateLimiting.problemType(), rateLimiting.title(), 429, Map.of());
        assertAll("the answer past the share",
                () -> assertTrue(tripped.hasTripped() && !tripped.isBlocked(),
                        "precondition: tripped under delay, never blocked"),
                () -> assertEquals(429, response.status()),
                () -> assertEquals(Long.toString(DELAY_RETRY_AFTER_SECONDS),
                        response.headers().get(ErrorResponseWriter.RETRY_AFTER_HEADER),
                        "Retry-After is the whole seconds of delay_millis, rounded up"),
                () -> assertEquals(expectedBody, response.body()),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under delay a tripped source with room in its share, and an untripped source, pass")
    void delaySourcesWithinTheirShareOrUntrippedPass() throws Exception {
        SourceGate sourceGate = gate(delay());
        sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);

        Response withRoom = send();
        SourceGate untrippedGate = gate(delay());
        exhaustedShares.add(loopbackKey());
        Response untripped = send();

        assertAll("the matched controls of the share refusal",
                () -> assertEquals(200, withRoom.status(), "a tripped source with room in its share passes"),
                () -> assertTrue(untrippedGate.ledger().state(loopbackKey()).isEmpty(),
                        "precondition: the second gate has not tracked the source"),
                () -> assertEquals(200, untripped.status(), "an untripped source is never refused for a share"),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under observe a tripped source passes even with its held-answer share used up")
    void observeIgnoresTheShare() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(1).build());
        sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);
        exhaustedShares.add(loopbackKey());

        Response response = send();

        assertAll("observe answers nothing differently",
                () -> assertEquals(200, response.status()),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under delay a tripped source's held rejection carries its source for the share; an untripped one does not")
    void delayHoldCarriesTheSourceForTheShare() {
        SourceGate sourceGate = gate(delay());
        Map<String, Object> trippedData = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, loopbackKey(),
                ResponseDelay.ADMITTED_AT_KEY, 0L));
        Map<String, Object> firstData = new HashMap<>(trippedData);

        OptionalLong first = sourceGate.admit(feedContext(firstData), null, EventType.NO_ROUTE_MATCHED);
        OptionalLong held = sourceGate.admit(feedContext(trippedData), null, EventType.NO_ROUTE_MATCHED);
        OptionalLong failure = sourceGate.admit(feedContext(new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, loopbackKey(),
                ResponseDelay.ADMITTED_AT_KEY, 0L))), null, EventType.PRE_TRUST_INTERNAL_FAILURE);

        assertAll("the delay hold and the source it counts against",
                () -> assertTrue(first.isPresent(), "threshold 1: the first rejection trips the source and is held"),
                () -> assertEquals(loopbackKey(), firstData.get(EdgeAdmission.HELD_SOURCE_KEY)),
                () -> assertEquals(OptionalLong.of(DELAY_NANOS), held, "held until admission plus delay_millis"),
                () -> assertEquals(loopbackKey(), trippedData.get(EdgeAdmission.HELD_SOURCE_KEY),
                        "the hold counts against the tripped source's share"),
                () -> assertEquals(OptionalLong.of(DELAY_NANOS), failure,
                        "a pre-trust internal failure of the tripped source is held the same way"));
    }

    @Test
    @DisplayName("under delay a rejection of an untripped source is not held and carries no source for the share")
    void untrippedRejectionCarriesNoSource() {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(2)
                .action(EnumerationDetectionConfig.Action.DELAY).delayMillis(DELAY_MILLIS).build());
        Map<String, Object> data = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, loopbackKey(),
                ResponseDelay.ADMITTED_AT_KEY, 0L));

        OptionalLong hold = sourceGate.admit(feedContext(data), null, EventType.NO_ROUTE_MATCHED);

        assertAll("a rejection below the threshold",
                () -> assertTrue(hold.isEmpty(), "an untripped source is not delayed"),
                () -> assertNull(data.get(EdgeAdmission.HELD_SOURCE_KEY), "nothing counts against a share"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EnumerationDetectionConfig.Action.class)
    @DisplayName("every configured action maps onto the ledger action of the same name")
    void configuredActionMapsOntoTheLedgerAction(EnumerationDetectionConfig.Action action) {
        assertEquals(action.name(), SourceGate.ledgerAction(action).name());
    }

    @Test
    @DisplayName("only the block action blocks: delay and observe trip without a block")
    void onlyBlockBlocks() {
        for (EnumerationDetectionConfig.Action action : EnumerationDetectionConfig.Action.values()) {
            SourceGate sourceGate = gate(EnumerationDetectionConfig.builder().threshold(1).action(action).build());
            SourceState state = sourceGate.ledger().admit(loopbackKey(), 1, EventType.NO_ROUTE_MATCHED);
            assertEquals(action == EnumerationDetectionConfig.Action.BLOCK, state.isBlocked(), action.name());
        }
        assertEquals(SourceLedger.Action.BLOCK, SourceGate.ledgerAction(EnumerationDetectionConfig.Action.BLOCK));
    }

    /** {@code delay} at threshold 1, so the first counted rejection trips the source. */
    private static EnumerationDetectionConfig delay() {
        return EnumerationDetectionConfig.builder().threshold(1).action(EnumerationDetectionConfig.Action.DELAY)
                .delayMillis(DELAY_MILLIS).build();
    }

    /** The gate, its recon-trip observer recording every trip into {@link #observedTrips}. */
    private SourceGate gate(EnumerationDetectionConfig config) {
        SourceGate built = new SourceGate(config, eventCounter, new SheriffMetrics(meterRegistry),
                new GatewayJson(new ObjectMapper()), exhaustedShares::contains, observedTrips::add);
        gate.set(built);
        return built;
    }

    /** The source-key stage of a gateway without trusted proxies: every request is keyed on its TCP peer. */
    private static SourceKeys sourceKeys() {
        ForwardedHeaderResolver forwardedResolver = new ForwardedHeaderResolver(
                ForwardedResolverConfig.builder().trustedProxies(Set.of())
                        .securityConfig(SecurityConfiguration.defaults()).build(),
                new SecurityEventCounter());
        return new SourceKeys(new SourceKeyResolver(List.of()), new TcpPeerGate(List.of()), forwardedResolver);
    }

    /**
     * A {@link RoutingContext} backed by a map for {@code get} and {@code put}, the only calls the
     * gate makes on it; every other call fails the test.
     */
    private static RoutingContext feedContext(Map<String, Object> data) {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> data.get(args[0]);
                    case "put" -> {
                        data.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    default -> throw new AssertionError("the rejection feed must not call " + method.getName());
                });
    }

    /** The key a request from this test's client is tracked under: the loopback peer, host folded to {@code *}. */
    private static SourceKey loopbackKey() {
        return new SourceKey(SourceKeyResolver.canonicalSource(LoopbackHost.ADDRESS), SourceKey.UNDECLARED_HOST);
    }

    private Response send() throws Exception {
        return send(Map.of());
    }

    private Response send(Map<String, String> requestHeaders) throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(server.actualPort()).setURI("/probe");
        requestHeaders.forEach(options::putHeader);
        CompletableFuture<Response> future = client.request(options)
                .compose(HttpClientRequest::send)
                .compose(response -> {
                    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                    return response.body().map(buffer -> new Response(response.statusCode(), headers,
                            buffer == null ? "" : buffer.toString()));
                })
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the gate's answer");
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }
}
