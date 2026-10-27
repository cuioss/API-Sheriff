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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The per-source gate in front of edge admission, driven over a live Vert.x server whose one handler
 * asks the gate first and answers {@code 200} only when the gate let the request through: a blocked
 * source is answered {@code 429} with {@code Retry-After} and the rate-limiting problem document, an
 * unblocked source and a tripped source under the default {@code observe} pass, and a trip is reported
 * as an event, on the trip meter and as the trip WARN.
 */
@EnableTestLogger
@DisplayName("SourceGate — the per-source answer before admission")
@Tag("isolated-fork")
class SourceGateTest {

    private static final String PASSED = "passed";
    private static final int BLOCK_SECONDS = 60;

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
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            boolean answered = gate.get().answersBlocked(ctx);
            stashed.set(ctx.get(SourceGate.SOURCE_KEY));
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

    private SourceGate gate(EnumerationDetectionConfig config) {
        ForwardedHeaderResolver forwardedResolver = new ForwardedHeaderResolver(
                ForwardedResolverConfig.builder().trustedProxies(Set.of())
                        .securityConfig(SecurityConfiguration.defaults()).build(),
                new SecurityEventCounter());
        SourceGate built = new SourceGate(config, new SourceKeyResolver(List.of()), new TcpPeerGate(List.of()),
                forwardedResolver, eventCounter, new SheriffMetrics(meterRegistry), new GatewayJson(new ObjectMapper()));
        gate.set(built);
        return built;
    }

    /** The key a request from this test's client is tracked under: the loopback peer, host folded to {@code *}. */
    private static SourceKey loopbackKey() {
        return new SourceKey(SourceKeyResolver.canonicalSource(LoopbackHost.ADDRESS), SourceKey.UNDECLARED_HOST);
    }

    private Response send() throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(server.actualPort()).setURI("/probe");
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
